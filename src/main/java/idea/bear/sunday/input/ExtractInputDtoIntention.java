package idea.bear.sunday.input;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.util.IncorrectOperationException;
import idea.bear.sunday.BearSundayBundle;
import org.jetbrains.annotations.NotNull;

public final class ExtractInputDtoIntention extends PsiElementBaseIntentionAction {

    private final ExtractInputDtoAction action = new ExtractInputDtoAction();

    @Override
    public @NotNull String getText() {
        return BearSundayBundle.message("action.extract.input.dto.text");
    }

    @Override
    public @NotNull String getFamilyName() {
        return BearSundayBundle.message("action.extract.input.dto.text");
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        return action.isAvailable(project, editor);
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) throws IncorrectOperationException {
        action.perform(project, editor);
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

}
