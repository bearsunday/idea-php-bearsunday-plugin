package idea.bear.sunday.template;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReferenceBase;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

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
        Collection<? extends PsiElement> targets =
                EmbedResolver.resolveEmbeddedTemplates(srcUri, parentResource, support, project);
        return targets.isEmpty() ? null : targets.iterator().next();
    }

    @Override
    public Object @NotNull [] getVariants() {
        return EMPTY_ARRAY;
    }
}
