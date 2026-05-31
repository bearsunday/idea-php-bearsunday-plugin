package idea.bear.sunday.aop;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.navigation.NavigationUtil;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

public class GoToBoundInterceptorAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        boolean enabled = project != null
            && editor != null
            && !DumbService.isDumb(project)
            && InterceptorNavigationUtil.findAttributeFqn(
                InterceptorNavigationUtil.findElementAtCaret(project, editor)
            ) != null;

        event.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null || DumbService.isDumb(project)) {
            return;
        }

        PsiElement element = InterceptorNavigationUtil.findElementAtCaret(project, editor);
        String annotationFqn = InterceptorNavigationUtil.findAttributeFqn(element);
        PsiElement[] targets = InterceptorNavigationUtil.findInterceptorTargets(annotationFqn, project);
        if (targets.length == 0) {
            HintManager.getInstance().showErrorHint(editor, "No bound interceptor found");
            return;
        }

        if (targets.length == 1) {
            NavigationUtil.activateFileWithPsiElement(targets[0], true);
            return;
        }

        NavigationUtil.getPsiElementPopup(targets, "Bound Interceptors").showInBestPositionFor(editor);
    }
}
