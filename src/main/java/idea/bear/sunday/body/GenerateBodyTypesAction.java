package idea.bear.sunday.body;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

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
        event.getPresentation().setVisible(project != null);
        event.getPresentation().setEnabled(enabled);
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
        Map<String, VirtualFile> selectedFiles = new LinkedHashMap<>();
        VirtualFile[] files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (files != null) {
            for (VirtualFile file : files) {
                addFile(selectedFiles, file);
            }
        }

        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        addFile(selectedFiles, file);

        PsiElement[] elements = event.getData(LangDataKeys.PSI_ELEMENT_ARRAY);
        if (elements != null) {
            for (PsiElement element : elements) {
                addFile(selectedFiles, virtualFile(element));
            }
        }

        addFile(selectedFiles, virtualFile(event.getData(CommonDataKeys.PSI_ELEMENT)));

        return selectedFiles.values().toArray(VirtualFile.EMPTY_ARRAY);
    }

    private static void addFile(Map<String, VirtualFile> files, @Nullable VirtualFile file) {
        if (file != null) {
            files.put(file.getPath(), file);
        }
    }

    private static @Nullable VirtualFile virtualFile(@Nullable PsiElement element) {
        if (element instanceof PsiDirectory directory) {
            return directory.getVirtualFile();
        }
        if (element instanceof PsiFile file) {
            return file.getVirtualFile();
        }
        PsiFile containingFile = element == null ? null : element.getContainingFile();

        return containingFile == null ? null : containingFile.getVirtualFile();
    }

}
