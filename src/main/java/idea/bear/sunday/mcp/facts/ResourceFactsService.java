package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.jetbrains.php.lang.psi.elements.ClassReference;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.Parameter;
import com.jetbrains.php.lang.psi.elements.PhpAttribute;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.PhpTypeDeclaration;
import idea.bear.sunday.relation.ResourceRelation;
import idea.bear.sunday.relation.ResourceRelationIndex;
import idea.bear.sunday.relation.ResourceRelationIndexUtil;
import idea.bear.sunday.resource.ResourceClassResolver;
import idea.bear.sunday.util.UriUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Describes a BEAR.Resource class behind a resource URI: its methods, their parameters and
 * attributes, and the relations that leave and reach it.
 */
@Service(Service.Level.PROJECT)
public final class ResourceFactsService {

    private static final String INDEX_NOT_READY = Status.index_not_ready.name();

    private final Project project;

    public ResourceFactsService(Project project) {
        this.project = project;
    }

    public static ResourceFactsService getInstance(Project project) {
        return project.getService(ResourceFactsService.class);
    }

    public String describe(@Nullable String uri) {
        return answer(() -> describeResource(uri));
    }

    private String describeResource(@Nullable String uri) {
        if (uri == null || uri.isBlank()) {
            return Envelope.notFound("uri is required.").toJson();
        }
        String normalizedUri = UriUtil.normalizeSupportedResourceUri(uri.trim(), false);
        if (normalizedUri == null) {
            return Envelope.notFound("Unsupported resource URI: " + uri).toJson();
        }
        Optional<PhpClass> resolved = ResourceClassResolver.resolveCached(project, normalizedUri);
        if (resolved.isEmpty()) {
            return Envelope.notFound("Resource class not found for " + normalizedUri).toJson();
        }
        PhpClass phpClass = resolved.get();
        PsiFile psiFile = phpClass.getContainingFile();
        VirtualFile file = psiFile == null ? null : psiFile.getVirtualFile();

        JsonObject resource = new JsonObject();
        resource.addProperty("uri", normalizedUri);
        resource.addProperty("classFqn", phpClass.getFQN());
        if (file != null) {
            resource.addProperty("filePath", FactsFiles.relativePath(project, file));
        }
        resource.add("methods", methodsJson(phpClass));
        addRelations(resource, psiFile, normalizedUri);

        JsonObject payload = new JsonObject();
        payload.add("resource", resource);
        Provenance provenance = file == null
            ? Provenance.derived(normalizedUri)
            : Provenance.ofPsi(FactsFiles.relativePath(project, file), FactsFiles.isUnsaved(file));

        return Envelope.ok(provenance, payload).toJson();
    }

    private void addRelations(JsonObject resource, @Nullable PsiFile psiFile, String normalizedUri) {
        boolean unavailable = false;
        try {
            resource.add("relationsOut", outgoingJson(psiFile));
        } catch (IndexNotReadyException exception) {
            unavailable = true;
        }
        try {
            resource.add("relationsIn", incomingJson(normalizedUri));
        } catch (IndexNotReadyException exception) {
            unavailable = true;
        }
        if (unavailable) {
            resource.addProperty("relationsUnavailable", INDEX_NOT_READY);
        }
    }

    private JsonArray outgoingJson(@Nullable PsiFile psiFile) {
        JsonArray relations = new JsonArray();
        if (psiFile == null) {
            return relations;
        }
        Map<String, List<ResourceRelation>> outgoing = ResourceRelationIndexUtil.index(psiFile);
        for (ResourceRelation relation : sorted(outgoing.values().stream().flatMap(List::stream).toList())) {
            JsonObject json = new JsonObject();
            json.addProperty("kind", relation.kind());
            json.addProperty("rel", relation.rel());
            json.addProperty("targetUri", relation.targetUri());
            json.addProperty("targetMethod", relation.targetMethod());
            relations.add(json);
        }

        return relations;
    }

    private JsonArray incomingJson(String normalizedUri) {
        JsonArray relations = new JsonArray();
        String resourcePath = UriUtil.toSupportedResourceRelativePath(normalizedUri, false);
        if (resourcePath == null) {
            return relations;
        }
        for (ResourceRelation relation : sorted(ResourceRelationIndex.findIncoming(resourcePath, project))) {
            JsonObject json = new JsonObject();
            json.addProperty("kind", relation.kind());
            json.addProperty("rel", relation.rel());
            json.addProperty("sourceUri", relation.sourceUri());
            json.addProperty("sourceFqn", relation.sourceFqn());
            relations.add(json);
        }

        return relations;
    }

    /** Relations come from a hash map and from the index, so the answer is ordered explicitly. */
    private static List<ResourceRelation> sorted(List<ResourceRelation> relations) {
        List<ResourceRelation> ordered = new ArrayList<>(relations);
        ordered.sort(Comparator.comparing(ResourceRelation::targetUri)
            .thenComparing(ResourceRelation::sourceUri)
            .thenComparing(ResourceRelation::rel)
            .thenComparing(ResourceRelation::kind));

        return ordered;
    }

    private static JsonArray methodsJson(PhpClass phpClass) {
        JsonArray methods = new JsonArray();
        for (Method method : phpClass.getOwnMethods()) {
            if (!isResourceMethod(method)) {
                continue;
            }
            JsonObject json = new JsonObject();
            json.addProperty("name", method.getName());
            json.add("params", parametersJson(method));
            json.add("attributes", attributesJson(method.getAttributes()));
            methods.add(json);
        }

        return methods;
    }

    /** A resource method is a public {@code on*} method; shared with {@link ResourceAttributeIndexService}. */
    static boolean isResourceMethod(Method method) {
        String name = method.getName();

        return name.length() > 2
            && name.startsWith("on")
            && Character.isUpperCase(name.charAt(2))
            && method.getAccess().isPublic();
    }

    private static JsonArray parametersJson(Method method) {
        JsonArray parameters = new JsonArray();
        for (PsiElement element : method.getParameters()) {
            if (!(element instanceof Parameter parameter)) {
                continue;
            }
            JsonObject json = new JsonObject();
            json.addProperty("name", parameter.getName());
            String type = declaredType(parameter);
            if (type != null) {
                json.addProperty("type", type);
            }
            parameters.add(json);
        }

        return parameters;
    }

    @Nullable
    private static String declaredType(Parameter parameter) {
        PhpTypeDeclaration declaration = parameter.getTypeDeclaration();
        if (declaration != null && !declaration.getText().isBlank()) {
            return declaration.getText().trim();
        }
        String declared = parameter.getDeclaredType().toString();

        return declared.isBlank() ? null : declared;
    }

    private static JsonArray attributesJson(Iterable<PhpAttribute> attributes) {
        JsonArray json = new JsonArray();
        for (PhpAttribute attribute : attributes) {
            JsonObject attributeJson = new JsonObject();
            String fqn = attributeFqn(attribute);
            if (fqn != null) {
                attributeJson.addProperty("fqn", fqn);
            }
            attributeJson.addProperty("text", attribute.getText());
            json.add(attributeJson);
        }

        return json;
    }

    @Nullable
    private static String attributeFqn(PhpAttribute attribute) {
        String fqn = attribute.getFQN();
        if (fqn != null && !fqn.isBlank()) {
            return fqn;
        }
        ClassReference classReference = attribute.getClassReference();

        return classReference == null ? null : classReference.getName();
    }

    /**
     * Runs the read off the write lock and names the one failure the caller must not read as an
     * answer: while the indexes are building, a resource the project does hold cannot be found,
     * and reporting that as not_found would have an agent conclude the resource does not exist.
     *
     * <p>Non-blocking so a pending write action is not made to wait out the read; the read is
     * cancelled and retried instead, paying with its own latency rather than the editor's.
     */
    private static String answer(Computable<String> read) {
        try {
            return ReadAction.nonBlocking(read::compute).executeSynchronously();
        } catch (IndexNotReadyException exception) {
            return Envelope.indexNotReady("The project indexes are still building; ask again once indexing finishes.").toJson();
        }
    }
}
