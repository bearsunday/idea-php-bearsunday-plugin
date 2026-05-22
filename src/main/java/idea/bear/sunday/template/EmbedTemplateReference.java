package idea.bear.sunday.template;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReferenceBase;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class EmbedTemplateReference extends PsiReferenceBase<PsiElement> {

    private final PhpClass parentResource;
    private final String srcUri;
    private final TemplateEngineSupport support;

    public EmbedTemplateReference(@NotNull PsiElement element,
                                  @NotNull PhpClass parentResource,
                                  @NotNull String srcUri,
                                  @NotNull TemplateEngineSupport support) {
        super(element, new TextRange(0, element.getTextLength()), true);
        this.parentResource = parentResource;
        this.srcUri = srcUri;
        this.support = support;
    }

    @Nullable
    @Override
    public PsiElement resolve() {
        Project project = getElement().getProject();
        PhpClass embeddedClass = EmbedResolver.resolveEmbeddedClass(srcUri, parentResource, project);
        if (embeddedClass == null) {
            return null;
        }
        List<VirtualFile> templates = support.resolveTemplates(embeddedClass);
        PsiManager psiManager = PsiManager.getInstance(project);
        for (VirtualFile candidate : templates) {
            PsiFile pf = psiManager.findFile(candidate);
            if (pf != null) {
                return pf;
            }
        }
        return null;
    }

    @Override
    public Object @NotNull [] getVariants() {
        return EMPTY_ARRAY;
    }
}
