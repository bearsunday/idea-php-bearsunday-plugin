package idea.bear.sunday.body;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

public class GenerateBodyTypeIntention extends PsiElementBaseIntentionAction {

    private static final String TEXT = "Generate BEAR body type";
    private final BodyTypeCollector collector = new BodyTypeCollector();

    @Override
    public @NotNull String getText() {
        return TEXT;
    }

    @Override
    public @NotNull String getFamilyName() {
        return TEXT;
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        PhpClass phpClass = findPhpClass(element);
        if (phpClass == null) {
            return false;
        }

        return collector.collect(phpClass).isPresent();
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) throws IncorrectOperationException {
        PhpClass phpClass = findPhpClass(element);
        if (phpClass == null) {
            return;
        }

        Optional<BodyTypeCollection> bodyTypes = collector.collect(phpClass);
        if (bodyTypes.isEmpty()) {
            return;
        }

        Runnable updateDocBlock = () -> BodyTypeDocBlockWriter.update(project, phpClass, bodyTypes.get());
        WriteCommandAction.runWriteCommandAction(project, TEXT, null, updateDocBlock);
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    private PhpClass findPhpClass(PsiElement element) {
        if (element instanceof PhpClass phpClass) {
            return phpClass;
        }

        return PsiTreeUtil.getParentOfType(element, PhpClass.class);
    }

}
