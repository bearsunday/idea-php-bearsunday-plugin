package idea.bear.sunday.body;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

public final class GenerateBodyTypesAction extends AnAction {

    private final BodyTypeBatchGenerator generator = new BodyTypeBatchGenerator();

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile[] files = selectedFiles(event);
        boolean enabled = project != null
            && files.length > 0
            && Arrays.stream(files).anyMatch(BodyTypeBatchGenerator::isCandidateRoot);
        event.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }

        VirtualFile[] files = selectedFiles(event);
        if (files.length == 0) {
            return;
        }

        generator.generate(project, files);
    }

    private static VirtualFile[] selectedFiles(AnActionEvent event) {
        VirtualFile[] files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (files != null) {
            return files;
        }

        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        return file == null ? VirtualFile.EMPTY_ARRAY : new VirtualFile[]{file};
    }

}
