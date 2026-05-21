package idea.bear.sunday.template;

import com.damnhandy.uri.template.MalformedUriTemplateException;
import com.damnhandy.uri.template.UriTemplateComponent;
import com.damnhandy.uri.template.impl.UriTemplateParser;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.PhpAttribute;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.WordUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EmbedResolver {

    private static final Pattern REL_PATTERN = Pattern.compile("rel\\s*:\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern SRC_PATTERN = Pattern.compile("src\\s*:\\s*['\"]([^'\"]+)['\"]");

    private EmbedResolver() {
    }

    @Nullable
    public static String findEmbedSrcUri(@NotNull PhpClass resourceClass, @NotNull String relName) {
        for (Method method : resourceClass.getOwnMethods()) {
            String src = findEmbedSrcOnAttributes(method.getAttributes(), relName);
            if (src != null) {
                return src;
            }
        }
        return findEmbedSrcOnAttributes(resourceClass.getAttributes(), relName);
    }

    @Nullable
    public static PhpClass resolveEmbeddedClass(@NotNull String srcUri,
                                                @NotNull PhpClass parentResource,
                                                @NotNull Project project) {
        String appRoot = TemplateUtils.appRootOf(parentResource);
        if (appRoot == null) {
            return null;
        }
        String stripped = stripUriTemplate(srcUri);
        URI uri;
        try {
            uri = new URI(stripped);
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme();
        String path = uri.getPath();
        if (scheme == null || path == null) {
            return null;
        }
        String classRel = WordUtils.capitalize(scheme)
                + StringUtils.remove(WordUtils.capitalizeFully(path, new char[]{'/', '-'}), "-");
        if (classRel.endsWith("/")) {
            classRel += "index";
        }
        classRel += ".php";
        String classAbsPath = appRoot + TemplateUtils.RESOURCE_DIR_SEGMENT + classRel;
        return TemplateUtils.findClassByAbsolutePath(project, classAbsPath);
    }

    /**
     * Resolves the resource embedded at {@code srcUri} to its template PSI files, for use as
     * gutter-icon navigation targets. Shared by the Twig and Qiq line marker providers.
     */
    @NotNull
    public static Collection<? extends PsiElement> resolveEmbeddedTemplates(@NotNull String srcUri,
                                                                            @NotNull PhpClass parentResource,
                                                                            @NotNull TemplateEngineSupport support,
                                                                            @NotNull Project project) {
        PhpClass embedded = resolveEmbeddedClass(srcUri, parentResource, project);
        if (embedded == null) {
            return Collections.emptyList();
        }
        List<VirtualFile> templates = support.resolveTemplates(embedded);
        if (templates.isEmpty()) {
            return Collections.emptyList();
        }
        List<PsiElement> targets = new ArrayList<>(templates.size());
        PsiManager psiManager = PsiManager.getInstance(project);
        for (VirtualFile vf : templates) {
            PsiFile pf = psiManager.findFile(vf);
            if (pf != null) {
                targets.add(pf);
            }
        }
        return targets;
    }

    @Nullable
    private static String findEmbedSrcOnAttributes(@NotNull Collection<? extends PhpAttribute> attributes,
                                                   @NotNull String relName) {
        for (PhpAttribute attribute : attributes) {
            String name = attribute.getName();
            if (name == null) {
                continue;
            }
            if (!name.equals("Embed") && !name.endsWith("\\Embed")) {
                continue;
            }
            String text = attribute.getText();
            Matcher relMatcher = REL_PATTERN.matcher(text);
            Matcher srcMatcher = SRC_PATTERN.matcher(text);
            if (relMatcher.find() && srcMatcher.find()) {
                if (relName.equals(relMatcher.group(1))) {
                    return srcMatcher.group(1);
                }
            }
        }
        return null;
    }

    @NotNull
    private static String stripUriTemplate(@NotNull String uri) {
        UriTemplateParser parser = new UriTemplateParser();
        try {
            LinkedList<UriTemplateComponent> components = parser.scan(uri);
            if (!components.isEmpty() && components.get(0) != null) {
                return components.get(0).getValue();
            }
        } catch (MalformedUriTemplateException ignored) {
        }
        return uri;
    }
}
