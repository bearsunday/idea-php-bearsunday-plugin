package idea.bear.sunday.aop;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.PhpIndex;
import com.jetbrains.php.lang.psi.elements.ClassReference;
import com.jetbrains.php.lang.psi.elements.PhpAttribute;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class InterceptorNavigationUtil {

    private static final String RESOURCE_EMBED = "\\BEAR\\Resource\\Annotation\\Embed";
    private static final String RESOURCE_LINK = "\\BEAR\\Resource\\Annotation\\Link";

    private InterceptorNavigationUtil() {
    }

    @Nullable
    static PsiElement findElementAtCaret(@NotNull Project project, @NotNull Editor editor) {
        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (psiFile == null) {
            return null;
        }

        int offset = editor.getCaretModel().getOffset();
        PsiElement element = psiFile.findElementAt(offset);
        if (element == null && offset > 0) {
            element = psiFile.findElementAt(offset - 1);
        }

        return element;
    }

    /**
     * Returns the FQN of the attribute whose name was clicked, or {@code null} when the clicked
     * element is not a PHP attribute name.
     */
    @Nullable
    static String findAttributeFqn(@Nullable PsiElement psiElement) {
        if (psiElement == null) {
            return null;
        }

        ClassReference classReference = findAttributeClassReference(psiElement);
        if (classReference != null && !isResourceRelationAttribute(classReference)) {
            return InterceptorBindingIndexUtil.normalizeFqn(classReference.getFQN());
        }

        return null;
    }

    @Nullable
    static ClassReference findAttributeClassReference(@Nullable PsiElement psiElement) {
        if (psiElement == null) {
            return null;
        }

        ClassReference classReference = PsiTreeUtil.getParentOfType(psiElement, ClassReference.class);
        if (classReference != null && classReference.getParent() instanceof PhpAttribute) {
            return classReference;
        }

        return null;
    }

    static boolean isResourceRelationAttribute(@NotNull ClassReference classReference) {
        String fqn = InterceptorBindingIndexUtil.normalizeFqn(classReference.getFQN());
        return RESOURCE_EMBED.equals(fqn) || RESOURCE_LINK.equals(fqn);
    }

    @NotNull
    static PsiElement findLineMarkerAnchor(@NotNull ClassReference classReference) {
        PsiElement anchor = classReference;
        while (anchor.getFirstChild() != null) {
            anchor = anchor.getFirstChild();
        }

        return anchor;
    }

    @NotNull
    static PsiElement[] findInterceptorTargets(@Nullable String annotationFqn, @NotNull Project project) {
        List<String> interceptorFqns = findInterceptorFqns(annotationFqn, project);
        return findInterceptorTargets(interceptorFqns, project);
    }

    @NotNull
    static PsiElement[] findInterceptorTargets(@NotNull List<String> interceptorFqns, @NotNull Project project) {
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

    @NotNull
    static List<String> findInterceptorFqns(@Nullable String annotationFqn, @NotNull Project project) {
        if (annotationFqn == null || DumbService.isDumb(project)) {
            return Collections.emptyList();
        }

        return InterceptorBindingIndex.findInterceptors(annotationFqn, project);
    }
}
