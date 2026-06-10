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
import idea.bear.sunday.BearSundayBundle;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ExtractInputDtoAction extends AnAction {
    private final ExtractInputDtoTextRefactoring refactoring = new ExtractInputDtoTextRefactoring();

    public ExtractInputDtoAction() {
        super(
            BearSundayBundle.message("action.extract.input.dto.text"),
            BearSundayBundle.message("action.extract.input.dto.description"),
            null
        );
    }

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
            HintManager.getInstance().showErrorHint(editor, BearSundayBundle.message("input.error.place.caret"));
            return;
        }

        List<ExtractInputDtoTextRefactoring.ParamInfo> params = refactoring.parseParams(method.paramsText());
        if (params.isEmpty()) {
            HintManager.getInstance().showErrorHint(editor, BearSundayBundle.message("input.error.no.parameters"));
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
            HintManager.getInstance().showErrorHint(editor, BearSundayBundle.message("input.error.resolve.files"));
            return;
        }
        String dtoRelativePath = "src/Input/" + dtoClass + ".php";
        if (baseDir.findFileByRelativePath(dtoRelativePath) != null) {
            HintManager.getInstance().showErrorHint(editor, BearSundayBundle.message("input.error.file.exists", dtoRelativePath));
            return;
        }

        String finalDtoClass = dtoClass;
        WriteCommandAction.runWriteCommandAction(project, () -> {
            document.setText(result.resourceText());
            try {
                updateQueryInterfaces(baseDir, result, selectedNames, finalDtoClass, dtoFqn);
                VirtualFile inputDir = ensureDirectory(baseDir, "src/Input");
                VirtualFile dtoFile = inputDir.createChildData(this, finalDtoClass + ".php");
                dtoFile.setBinaryContent(result.dtoText().getBytes(StandardCharsets.UTF_8));
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });
    }


    private void updateQueryInterfaces(
        VirtualFile baseDir,
        ExtractInputDtoTextRefactoring.RefactoringResult result,
        Set<String> selectedNames,
        String dtoClass,
        String dtoFqn
    ) throws IOException {
        if (result.collapsedMethodNames().isEmpty()) {
            return;
        }
        VirtualFile queryDir = baseDir.findFileByRelativePath("src/Query");
        if (queryDir == null || !queryDir.isDirectory()) {
            return;
        }
        for (VirtualFile file : phpFiles(queryDir)) {
            String text = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            String updated = refactoring.refactorQueryInterface(text, result.collapsedMethodNames(), selectedNames, dtoClass, dtoFqn);
            if (!updated.equals(text)) {
                file.setBinaryContent(updated.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static List<VirtualFile> phpFiles(VirtualFile dir) {
        List<VirtualFile> files = new ArrayList<>();
        collectPhpFiles(dir, files);
        return files;
    }

    private static void collectPhpFiles(VirtualFile dir, List<VirtualFile> files) {
        for (VirtualFile child : dir.getChildren()) {
            if (child.isDirectory()) {
                collectPhpFiles(child, files);
            } else if (child.getName().endsWith(".php")) {
                files.add(child);
            }
        }
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
