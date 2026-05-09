package idea.bear.sunday.template;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.util.ProcessingContext;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.twig.TwigLanguage;
import org.jetbrains.annotations.NotNull;

public class EmbedTemplateReferenceContributor extends PsiReferenceContributor {

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement().withLanguage(TwigLanguage.INSTANCE),
                new EmbedReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY
        );
    }

    private static final class EmbedReferenceProvider extends PsiReferenceProvider {

        @Override
        public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element,
                                                               @NotNull ProcessingContext context) {
            PsiFile psiFile = element.getContainingFile();
            if (psiFile == null) {
                return PsiReference.EMPTY_ARRAY;
            }
            VirtualFile file = psiFile.getOriginalFile().getVirtualFile();
            if (file == null) {
                return PsiReference.EMPTY_ARRAY;
            }
            Project project = element.getProject();
            TemplateEngineSupport support = TemplateEngineSupport.forFile(file, project);
            if (support == null) {
                return PsiReference.EMPTY_ARRAY;
            }
            String varName = support.extractVariableName(element);
            if (varName == null) {
                return PsiReference.EMPTY_ARRAY;
            }
            PhpClass parentResource = support.resolveResourceClass(file, project);
            if (parentResource == null) {
                return PsiReference.EMPTY_ARRAY;
            }
            String srcUri = EmbedResolver.findEmbedSrcUri(parentResource, varName);
            if (srcUri == null) {
                return PsiReference.EMPTY_ARRAY;
            }
            return new PsiReference[]{new EmbedTemplateReference(element, parentResource, srcUri, support)};
        }
    }
}
