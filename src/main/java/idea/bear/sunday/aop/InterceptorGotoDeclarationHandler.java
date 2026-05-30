package idea.bear.sunday.aop;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.PhpIndex;
import com.jetbrains.php.lang.psi.elements.ClassReference;
import com.jetbrains.php.lang.psi.elements.PhpAttribute;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Ctrl/Cmd-click on a Ray.Aop binding attribute (e.g. {@code #[Transactional]}) jumps to the
 * interceptor class(es) bound to that annotation in a module via {@code bindInterceptor()}.
 */
public class InterceptorGotoDeclarationHandler implements GotoDeclarationHandler {

    @Nullable
    @Override
    public PsiElement[] getGotoDeclarationTargets(PsiElement psiElement, int offset, Editor editor) {
        if (psiElement == null) {
            return PsiElement.EMPTY_ARRAY;
        }

        String annotationFqn = findClickedAttributeFqn(psiElement);
        if (annotationFqn == null) {
            return PsiElement.EMPTY_ARRAY;
        }

        Project project = psiElement.getProject();
        if (DumbService.isDumb(project)) {
            return PsiElement.EMPTY_ARRAY;
        }

        List<String> interceptorFqns = InterceptorBindingIndex.findInterceptors(annotationFqn, project);
        if (interceptorFqns.isEmpty()) {
            return PsiElement.EMPTY_ARRAY;
        }

        PhpIndex phpIndex = PhpIndex.getInstance(project);
        List<PsiElement> targets = new ArrayList<>();
        for (String fqn : interceptorFqns) {
            targets.addAll(phpIndex.getClassesByFQN(fqn));
        }

        return targets.toArray(PsiElement.EMPTY_ARRAY);
    }

    /**
     * Returns the FQN of the attribute whose name was clicked, or {@code null} when the clicked
     * element is not a PHP attribute name.
     */
    @Nullable
    private static String findClickedAttributeFqn(@NotNull PsiElement psiElement) {
        ClassReference classReference = PsiTreeUtil.getParentOfType(psiElement, ClassReference.class);
        if (classReference != null && classReference.getParent() instanceof PhpAttribute attribute) {
            return InterceptorBindingIndexUtil.normalizeFqn(attribute.getFQN());
        }

        return null;
    }

    @Nullable
    @Override
    public String getActionText(@NotNull DataContext context) {
        return "Go to Bound Interceptor";
    }
}
