package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.PhpAttribute;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import idea.bear.sunday.aop.InterceptorBindingIndex;
import idea.bear.sunday.aop.InterceptorBindingIndexUtil;
import idea.bear.sunday.util.UriUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists the PHP attributes carried by the resource classes under a resource root, with each
 * attribute resolved to the class it names. A text search cannot do this: {@code #[JsonSchema]}
 * is a short name a file's {@code use} statements alias, so two files can write the same text and
 * mean two different classes.
 */
@Service(Service.Level.PROJECT)
public final class ResourceAttributeIndexService {

    static final String DEFAULT_RESOURCE_ROOT = "src/Resource";

    private static final String PHP_EXTENSION = "php";
    private static final String TARGET_CLASS = "class";
    private static final String TARGET_METHOD = "method";

    private final Project project;

    public ResourceAttributeIndexService(Project project) {
        this.project = project;
    }

    public static ResourceAttributeIndexService getInstance(Project project) {
        return project.getService(ResourceAttributeIndexService.class);
    }

    // Non-blocking so a pending write action is not made to wait out the read; the read is
    // cancelled and retried instead. Every other MCP facts service reads the same way.
    public String index(@Nullable String attribute, @Nullable String method, @Nullable String resourceRoot) {
        return ReadAction.nonBlocking(() -> indexAttributes(attribute, method, resourceRoot))
            .executeSynchronously();
    }

    private String indexAttributes(@Nullable String attribute, @Nullable String method, @Nullable String resourceRoot) {
        String root = normalizeRoot(resourceRoot);
        if (root == null) {
            return Envelope.notFound("Unsupported resource root: " + resourceRoot).toJson();
        }
        VirtualFile rootDir = FactsFiles.find(project, root);
        if (rootDir == null || !rootDir.isDirectory()) {
            return Envelope.notFound("Resource root not found: " + root).toJson();
        }

        AttributeFilter filter = AttributeFilter.of(attribute);
        String methodFilter = onMethodName(method);
        InterceptorLookup interceptors = new InterceptorLookup(project);
        List<VirtualFile> files = phpFilesUnder(rootDir);
        JsonArray entries = new JsonArray();
        int classes = 0;
        boolean unsaved = false;

        for (VirtualFile file : files) {
            // Every walked file counts towards freshness: the walk reads PSI, which shows unsaved
            // editor buffers, and an unsaved edit can just as well remove a class from the answer.
            unsaved |= FactsFiles.isUnsaved(file);
            PhpClass phpClass = resourceClassOf(file);
            String uri = phpClass == null ? null : UriUtil.toResourceUri(phpClass);
            if (uri == null) {
                continue;
            }
            classes++;
            String filePath = FactsFiles.relativePath(project, file);

            // Class-level attributes belong to no method, so a method filter excludes them.
            if (methodFilter == null) {
                addEntry(entries, uri, phpClass, filePath, null, phpClass.getAttributes(), filter, interceptors);
            }
            for (Method resourceMethod : resourceMethods(phpClass)) {
                if (methodFilter != null && !methodFilter.equalsIgnoreCase(resourceMethod.getName())) {
                    continue;
                }
                addEntry(
                    entries,
                    uri,
                    phpClass,
                    filePath,
                    resourceMethod.getName(),
                    resourceMethod.getAttributes(),
                    filter,
                    interceptors
                );
            }
        }

        JsonObject scan = new JsonObject();
        scan.addProperty("resourceRoot", root);
        scan.addProperty("files", files.size());
        scan.addProperty("classes", classes);

        JsonObject payload = new JsonObject();
        payload.add("scan", scan);
        payload.add("entries", entries);
        if (interceptors.unavailable()) {
            payload.addProperty("interceptorsUnavailable", Status.index_not_ready.name());
        }

        return Envelope.ok(Provenance.derived(root, unsaved), payload).toJson();
    }

    private void addEntry(
        JsonArray entries,
        String uri,
        PhpClass phpClass,
        String filePath,
        @Nullable String methodName,
        Collection<PhpAttribute> attributes,
        AttributeFilter filter,
        InterceptorLookup interceptors
    ) {
        JsonArray attributesJson = attributesJson(attributes, filter, interceptors);
        if (attributesJson.isEmpty()) {
            return;
        }

        JsonObject entry = new JsonObject();
        entry.addProperty("uri", uri);
        entry.addProperty("classFqn", phpClass.getFQN());
        entry.addProperty("filePath", filePath);
        entry.addProperty("target", methodName == null ? TARGET_CLASS : TARGET_METHOD);
        if (methodName != null) {
            entry.addProperty("method", methodName);
        }
        entry.add("attributes", attributesJson);
        entries.add(entry);
    }

    private static JsonArray attributesJson(
        Collection<PhpAttribute> attributes,
        AttributeFilter filter,
        InterceptorLookup interceptors
    ) {
        JsonArray json = new JsonArray();
        for (PhpAttribute attribute : attributes) {
            String fqn = Attributes.fqn(attribute);
            String name = Attributes.shortName(attribute);
            if (!filter.matches(fqn, name)) {
                continue;
            }

            JsonObject attributeJson = new JsonObject();
            if (name != null) {
                attributeJson.addProperty("name", name);
            }
            if (fqn != null) {
                attributeJson.addProperty("fqn", fqn);
            }
            String argsText = Attributes.argsText(attribute);
            if (argsText != null) {
                attributeJson.addProperty("argsText", argsText);
            }
            // The bindings are keyed by the attribute class, so an unresolved attribute gets no
            // interceptor list at all: an empty one would claim nothing is bound to it.
            List<String> bound = fqn == null ? null : interceptors.of(fqn);
            if (bound != null) {
                JsonArray boundJson = new JsonArray();
                bound.forEach(boundJson::add);
                attributeJson.add("interceptors", boundJson);
            }
            json.add(attributeJson);
        }

        return json;
    }

    /**
     * The class a resource file declares. A file may declare a trait or an interface before it,
     * so the first class-like node is not necessarily the one: interfaces, traits and enums name
     * no resource and are skipped rather than published under a URI they do not answer to.
     */
    @Nullable
    private PhpClass resourceClassOf(VirtualFile file) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile == null) {
            return null;
        }
        for (PhpClass phpClass : PsiTreeUtil.findChildrenOfType(psiFile, PhpClass.class)) {
            if (!phpClass.isInterface() && !phpClass.isTrait() && !phpClass.isEnum()) {
                return phpClass;
            }
        }

        return null;
    }

    private static List<Method> resourceMethods(PhpClass phpClass) {
        List<Method> methods = new ArrayList<>();
        for (Method method : phpClass.getOwnMethods()) {
            if (ResourceFactsService.isResourceMethod(method)) {
                methods.add(method);
            }
        }

        return methods;
    }

    /**
     * The PHP files under the root, walked rather than looked up so a building index cannot empty
     * the answer. Symbolic links are not followed: a link out of the project would put another
     * project's classes in this project's index.
     */
    private static List<VirtualFile> phpFilesUnder(VirtualFile rootDir) {
        List<VirtualFile> files = new ArrayList<>();
        VfsUtilCore.visitChildrenRecursively(rootDir, new VirtualFileVisitor<Void>(VirtualFileVisitor.NO_FOLLOW_SYMLINKS) {
            @Override
            public boolean visitFile(@NotNull VirtualFile file) {
                ProgressManager.checkCanceled();
                if (!file.isDirectory() && PHP_EXTENSION.equalsIgnoreCase(file.getExtension())) {
                    files.add(file);
                }

                return true;
            }
        });
        files.sort(Comparator.comparing(VirtualFile::getPath));

        return files;
    }

    /**
     * The resource root as a project-relative path. The answer reaches into the file tree, so a
     * root that escapes the project, or that walks back up to it, is refused rather than read:
     * {@code "."} resolves to the project itself and would parse every PHP file in it, vendor
     * directories included.
     */
    @Nullable
    private static String normalizeRoot(@Nullable String resourceRoot) {
        if (resourceRoot == null || resourceRoot.isBlank()) {
            return DEFAULT_RESOURCE_ROOT;
        }
        String trimmed = resourceRoot.trim().replace('\\', '/');
        if (trimmed.startsWith("/")) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        for (String segment : trimmed.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (".".equals(segment) || "..".equals(segment)) {
                return null;
            }
            segments.add(segment);
        }

        return segments.isEmpty() ? null : String.join("/", segments);
    }

    /**
     * Both {@code get} and {@code onGet} name the same resource method; {@code null} is no filter.
     * The result is compared case-insensitively, as PHP compares method names, so a name that
     * already starts with {@code on} is left exactly as it was written.
     */
    @Nullable
    private static String onMethodName(@Nullable String method) {
        if (method == null || method.isBlank()) {
            return null;
        }
        String trimmed = method.trim();

        return trimmed.length() > 2 && trimmed.regionMatches(true, 0, "on", 0, 2) ? trimmed : "on" + trimmed;
    }

    /**
     * Which attributes the answer keeps. A query holding a backslash is a class name and matches
     * only attributes that resolved to it; a query without one is a short name, matched against
     * the last segment of the resolved class name, or against the name an unresolved attribute is
     * written under.
     */
    private record AttributeFilter(@Nullable String fqn, @Nullable String shortName) {

        static AttributeFilter of(@Nullable String attribute) {
            if (attribute == null || attribute.isBlank()) {
                return new AttributeFilter(null, null);
            }
            String trimmed = attribute.trim();

            return trimmed.indexOf('\\') >= 0
                ? new AttributeFilter(InterceptorBindingIndexUtil.normalizeFqn(trimmed), null)
                : new AttributeFilter(null, trimmed);
        }

        boolean matches(@Nullable String attributeFqn, @Nullable String attributeName) {
            if (fqn != null) {
                return fqn.equalsIgnoreCase(attributeFqn);
            }
            if (shortName != null) {
                return shortName.equalsIgnoreCase(attributeName);
            }

            return true;
        }
    }

    /**
     * The Ray.Aop interceptors a module binds to an attribute with {@code annotatedWith()}, read
     * once per attribute class. Bindings made by another matcher are not indexed, so an empty
     * list means no {@code annotatedWith} binding names the attribute, not that no interceptor
     * runs on the method.
     */
    private static final class InterceptorLookup {

        private final Project project;
        private final Map<String, List<String>> cache = new HashMap<>();
        private boolean unavailable;

        InterceptorLookup(Project project) {
            this.project = project;
        }

        /** {@code null} while the project index is still building and the bindings cannot be read. */
        @Nullable
        List<String> of(String attributeFqn) {
            if (unavailable) {
                return null;
            }
            List<String> cached = cache.get(attributeFqn);
            if (cached != null) {
                return cached;
            }
            try {
                List<String> found = InterceptorBindingIndex.findInterceptors(attributeFqn, project).stream()
                    .distinct()
                    .sorted()
                    .toList();
                cache.put(attributeFqn, found);

                return found;
            } catch (IndexNotReadyException exception) {
                unavailable = true;

                return null;
            }
        }

        boolean unavailable() {
            return unavailable;
        }
    }
}
