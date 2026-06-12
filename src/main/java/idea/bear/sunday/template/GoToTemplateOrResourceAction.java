package idea.bear.sunday.template;

import com.intellij.codeInsight.navigation.NavigationUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import idea.bear.sunday.BearSundayBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Navigates between a BEAR.Resource class and its template (Twig or Qiq), in both directions,
 * from the Project View and editor context menus. The label is "Open Template" on a resource
 * file and "Open Resource" on a template; the item is hidden unless the counterpart actually
 * exists. A single match opens directly; multiple matches (e.g. both a Twig and a Qiq template)
 * are offered in a popup.
 */
public class GoToTemplateOrResourceAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        Project project = event.getProject();
        VirtualFile file = targetFile(event);
        if (project == null || file == null || DumbService.isDumb(project)) {
            presentation.setEnabledAndVisible(false);
            return;
        }
        Resolution resolution = resolve(file, project);
        if (resolution == null) {
            presentation.setEnabledAndVisible(false);
            return;
        }
        presentation.setText(resolution.actionText());
        presentation.setEnabledAndVisible(true);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile file = targetFile(event);
        if (project == null || file == null || DumbService.isDumb(project)) {
            return;
        }
        Resolution resolution = resolve(file, project);
        if (resolution == null) {
            return;
        }
        PsiElement[] targets = resolution.targets();
        if (targets.length == 1) {
            NavigationUtil.activateFileWithPsiElement(targets[0], true);
            return;
        }
        JBPopup popup = NavigationUtil.getPsiElementPopup(targets, resolution.popupTitle());
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (editor != null) {
            popup.showInBestPositionFor(editor);
        } else {
            popup.showInBestPositionFor(event.getDataContext());
        }
    }

    /**
     * The single selected file, or {@code null}. Falls back from {@code VIRTUAL_FILE} to the
     * containing file of {@code PSI_FILE} (the Project View does not always provide the former),
     * and rejects directories and multi-file selections so the action stays unambiguous.
     */
    @Nullable
    private static VirtualFile targetFile(@NotNull AnActionEvent event) {
        VirtualFile[] selection = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (selection != null && selection.length > 1) {
            return null;
        }
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (file == null) {
            PsiFile psiFile = event.getData(CommonDataKeys.PSI_FILE);
            file = psiFile == null ? null : psiFile.getVirtualFile();
        }
        return file == null || file.isDirectory() ? null : file;
    }

    @Nullable
    static Resolution resolve(@NotNull VirtualFile file, @NotNull Project project) {
        String path = file.getPath();
        if (path.endsWith(".php") && path.contains(TemplateUtils.RESOURCE_DIR_SEGMENT)) {
            return resolveTemplates(file, project);
        }
        return resolveResource(file, project);
    }

    @Nullable
    private static Resolution resolveTemplates(@NotNull VirtualFile resourceFile, @NotNull Project project) {
        PhpClass resourceClass = TemplateUtils.findClass(project, resourceFile);
        if (resourceClass == null) {
            return null;
        }
        // A resource class gives no hint which engine renders it, so collect every engine's templates.
        PsiManager psiManager = PsiManager.getInstance(project);
        List<PsiElement> targets = new ArrayList<>();
        for (TemplateEngineSupport support : TemplateEngineSupport.SUPPORTS) {
            for (VirtualFile template : support.resolveTemplates(resourceClass)) {
                PsiFile psiFile = psiManager.findFile(template);
                if (psiFile != null) {
                    targets.add(psiFile);
                }
            }
        }
        return targets.isEmpty()
                ? null
                : new Resolution(BearSundayBundle.message("action.goto.template.open"),
                        BearSundayBundle.message("action.goto.popup.templates"),
                        targets.toArray(PsiElement.EMPTY_ARRAY));
    }

    @Nullable
    private static Resolution resolveResource(@NotNull VirtualFile templateFile, @NotNull Project project) {
        TemplateEngineSupport support = TemplateEngineSupport.forFile(templateFile, project);
        if (support == null) {
            return null;
        }
        PhpClass resourceClass = support.resolveResourceClass(templateFile, project);
        if (resourceClass == null) {
            return null;
        }
        return new Resolution(BearSundayBundle.message("action.goto.resource.open"),
                BearSundayBundle.message("action.goto.popup.resources"),
                new PsiElement[]{resourceClass});
    }

    record Resolution(@NotNull String actionText, @NotNull String popupTitle, PsiElement @NotNull [] targets) {
    }
}
