package idea.bear.sunday.input;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ExtractInputDtoAction extends AnAction {
    private final ExtractInputDtoTextRefactoring refactoring = new ExtractInputDtoTextRefactoring();

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        boolean enabled = project != null && editor != null && !DumbService.isDumb(project)
            && isResourceFile(editor)
            && refactoring.findMethodAtOffset(editor.getDocument().getText(), editor.getCaretModel().getOffset()) != null;
        event.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null || DumbService.isDumb(project) || !isResourceFile(editor)) {
            return;
        }

        Document document = editor.getDocument();
        String text = document.getText();
        int offset = editor.getCaretModel().getOffset();
        ExtractInputDtoTextRefactoring.MethodInfo method = refactoring.findMethodAtOffset(text, offset);
        if (method == null) {
            HintManager.getInstance().showErrorHint(editor, "Place caret inside a BEAR.Resource onXxx method");
            return;
        }

        List<ExtractInputDtoTextRefactoring.ParamInfo> params = refactoring.parseParams(method.paramsText());
        boolean hasSupportedParam = params.stream().anyMatch(ExtractInputDtoTextRefactoring.ParamInfo::supported);
        if (!hasSupportedParam) {
            HintManager.getInstance().showErrorHint(editor, "No supported parameters to extract");
            return;
        }

        Set<String> initiallySelectedNames = selectedNames(editor, params);
        ExtractInputDtoDialog dialog = new ExtractInputDtoDialog(project, params, initiallySelectedNames);
        if (!dialog.showAndGet()) {
            return;
        }

        Set<String> selectedNames = dialog.getSelectedParameterNames();
        String dtoClass = dialog.getDtoClass();
        String dtoVar = dialog.getDtoVariable();
        String dtoNamespace = refactoring.defaultInputNamespace(text);
        String dtoFqn = dtoNamespace + "\\" + dtoClass;
        ExtractInputDtoTextRefactoring.RefactoringResult result;
        try {
            result = refactoring.refactorResource(text, offset, selectedNames, dtoClass, dtoVar, dtoFqn);
        } catch (IllegalArgumentException ex) {
            HintManager.getInstance().showErrorHint(editor, ex.getMessage());
            return;
        }

        VirtualFile resourceFile = FileDocumentManager.getInstance().getFile(document);
        VirtualFile baseDir = project.getBaseDir();
        if (resourceFile == null || baseDir == null) {
            HintManager.getInstance().showErrorHint(editor, "Cannot resolve project files");
            return;
        }
        String dtoRelativePath = "src/Input/" + dtoClass + ".php";
        if (baseDir.findFileByRelativePath(dtoRelativePath) != null) {
            HintManager.getInstance().showErrorHint(editor, dtoRelativePath + " already exists");
            return;
        }

        String finalDtoClass = dtoClass;
        WriteCommandAction.runWriteCommandAction(project, () -> {
            document.setText(result.resourceText());
            try {
                VirtualFile inputDir = ensureDirectory(baseDir, "src/Input");
                VirtualFile dtoFile = inputDir.createChildData(this, finalDtoClass + ".php");
                dtoFile.setBinaryContent(result.dtoText().getBytes(StandardCharsets.UTF_8));
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    private static boolean isResourceFile(Editor editor) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (file == null) {
            return false;
        }
        String path = file.getPath();
        return path.endsWith(".php") && (path.contains("/src/Resource/App/") || path.contains("/src/Resource/Page/"));
    }

    private Set<String> selectedNames(Editor editor, List<ExtractInputDtoTextRefactoring.ParamInfo> params) {
        String selected = editor.getSelectionModel().hasSelection() ? editor.getSelectionModel().getSelectedText() : null;
        if (selected == null || selected.isBlank()) {
            return new LinkedHashSet<>();
        }
        return refactoring.selectedNamesFromText(selected, params);
    }

    private static VirtualFile ensureDirectory(VirtualFile baseDir, String path) throws IOException {
        VirtualFile current = baseDir;
        for (String part : path.split("/")) {
            VirtualFile child = current.findChild(part);
            if (child == null) {
                child = current.createChildDirectory(ExtractInputDtoAction.class, part);
            }
            current = child;
        }
        return current;
    }
}
