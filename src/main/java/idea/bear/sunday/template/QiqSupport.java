package idea.bear.sunday.template;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.jetbrains.php.lang.psi.elements.FieldReference;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.Variable;
import idea.bear.sunday.Settings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * Qiq template engine support (bear/qiq-module 2.x).
 *
 * <p>Unlike Twig, Qiq templates use the plain {@code .php} extension and live under a single
 * template directory, so a file counts as a Qiq template only when it sits under that
 * directory. Qiq exposes template data as properties of the template object, so a variable
 * is written {@code {{= $this->foo }}}; its PHP fragment is injected by the third-party Qiq
 * plugin and read here through the platform {@link InjectedLanguageManager}. No compile-time
 * dependency on the Qiq plugin is needed; when it is absent no injection happens, so the Qiq
 * features simply stay inactive.
 */
public class QiqSupport implements TemplateEngineSupport {

    public static final QiqSupport INSTANCE = new QiqSupport();

    private static final String EXTENSION = ".php";

    private QiqSupport() {
    }

    @Override
    public boolean accepts(@NotNull VirtualFile file, @NotNull Project project) {
        if (!file.getName().endsWith(EXTENSION)) {
            return false;
        }
        return file.getPath().contains("/" + templateDirSegment(project) + "/");
    }

    @Nullable
    @Override
    public String extractVariableName(@NotNull PsiElement element) {
        if (!(element instanceof FieldReference fieldReference)) {
            return null;
        }
        // A Qiq template variable is a property of $this: $this->foo.
        if (!isThisQualified(fieldReference)) {
            return null;
        }
        // It must live inside a Qiq tag (any tag). Tag-kind filtering — distinguishing output
        // tags ({{= ... }}) from code tags ({{ ... }}) — is the line marker provider's concern;
        // for Cmd+click navigation it's useful to jump from $this->X regardless of which tag
        // the reference sits in. We require the injection host to be a Qiq template file —
        // otherwise any PHP fragment injected by another language plugin (e.g. plain HEREDOC or
        // a different template engine) would be misclassified as a Qiq variable.
        if (qiqHostFile(element, element.getProject()) == null) {
            return null;
        }
        String name = fieldReference.getName();
        return (name == null || name.isEmpty()) ? null : name;
    }

    @NotNull
    @Override
    public List<VirtualFile> resolveTemplates(@NotNull PhpClass resourceClass) {
        String appRoot = TemplateUtils.appRootOf(resourceClass);
        String relPath = TemplateUtils.relativeFromResource(resourceClass);
        if (appRoot == null || relPath == null || !relPath.endsWith(EXTENSION)) {
            return Collections.emptyList();
        }
        // Qiq keeps the .php extension, so the resource-relative path maps over verbatim.
        String absPath = appRoot + "/" + templateDirSegment(resourceClass.getProject()) + "/" + relPath;
        VirtualFile candidate = LocalFileSystem.getInstance().findFileByPath(absPath);
        return candidate == null ? Collections.emptyList() : List.of(candidate);
    }

    @Nullable
    @Override
    public PhpClass resolveResourceClass(@NotNull VirtualFile templateFile, @NotNull Project project) {
        if (!templateFile.getName().endsWith(EXTENSION)) {
            return null;
        }
        String filePath = templateFile.getPath();
        String segment = "/" + templateDirSegment(project) + "/";
        int idx = filePath.lastIndexOf(segment);
        if (idx < 0) {
            return null;
        }
        String appRoot = filePath.substring(0, idx);
        String relInTemplates = filePath.substring(idx + segment.length());
        String classAbsPath = appRoot + TemplateUtils.RESOURCE_DIR_SEGMENT + relInTemplates;
        return TemplateUtils.findClassByAbsolutePath(project, classAbsPath);
    }

    /**
     * Returns the Qiq template file hosting this injected element, or {@code null} when the
     * element is not injected into a Qiq template (plain PHP, or injected elsewhere). Accepts
     * both output ({@code {{= ... }}}) and code ({@code {{ ... }}}) tag hosts — callers that
     * care about the tag kind (e.g. the line marker provider) should additionally check
     * {@link #isQiqOutputExpression}.
     */
    @Nullable
    public static VirtualFile qiqHostFile(@NotNull PsiElement element, @NotNull Project project) {
        PsiLanguageInjectionHost host =
                InjectedLanguageManager.getInstance(project).getInjectionHost(element);
        if (host == null) {
            return null;
        }
        PsiFile hostPsi = host.getContainingFile();
        if (hostPsi == null) {
            return null;
        }
        VirtualFile hostFile = hostPsi.getOriginalFile().getVirtualFile();
        if (hostFile == null || !INSTANCE.accepts(hostFile, project)) {
            return null;
        }
        return hostFile;
    }

    /**
     * True when {@code element} is injected into a Qiq output tag, or when {@code element} is
     * itself the output-tag host PSI. Used by both the line marker provider (host PSI is passed
     * directly) and helpers that start from injected PHP (host is resolved via {@code
     * InjectedLanguageManager}).
     */
    public static boolean isQiqOutputExpression(@NotNull PsiElement element) {
        if (isQiqOutputHost(element)) {
            return true;
        }
        PsiLanguageInjectionHost host =
                InjectedLanguageManager.getInstance(element.getProject()).getInjectionHost(element);
        return host != null && isQiqOutputHost(host);
    }

    /**
     * The Qiq plugin's {@code QiqCodeHost} backs both output tags ({@code {{= ... }}},
     * {@code {{h ... }}}, etc.) and code tags ({@code {{ ... }}}); the distinction is the
     * underlying AST element type. Output tags use {@code RAW_CONTENT} or
     * {@code ESCAPE_*_CONTENT}; code tags use {@code CODE_BODY} / {@code CODE_CONTENT}. The class
     * is matched by simple name so no compile-time dependency on the Qiq plugin is required.
     * ({@code QiqPhpHost} — which backs {@code <?php ?>} blocks — is not an output expression.)
     */
    private static boolean isQiqOutputHost(@NotNull PsiElement host) {
        if (!"QiqCodeHost".equals(host.getClass().getSimpleName())) {
            return false;
        }
        if (host.getNode() == null) {
            return false;
        }
        String tokenType = host.getNode().getElementType().toString();
        return "RAW_CONTENT".equals(tokenType) || tokenType.startsWith("ESCAPE_");
    }

    private static boolean isThisQualified(@NotNull FieldReference fieldReference) {
        return fieldReference.getClassReference() instanceof Variable variable
                && "this".equals(variable.getName());
    }

    @NotNull
    private static String templateDirSegment(@NotNull Project project) {
        return TemplateUtils.trimSlash(Settings.getInstance(project).qiqTemplatePath);
    }
}
