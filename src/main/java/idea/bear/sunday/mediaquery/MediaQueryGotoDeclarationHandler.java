package idea.bear.sunday.mediaquery;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import idea.bear.sunday.Settings;
import idea.bear.sunday.util.PhpDocAttributeTargets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Navigates from a Ray.MediaQuery / Ray.QueryModule SQL reference ({@code @DbQuery},
 * {@code @Query}, {@code @Named}, or the matching PHP 8 attributes) to the referenced
 * {@code .sql} file under the configured SQL paths.
 */
public class MediaQueryGotoDeclarationHandler implements GotoDeclarationHandler {

    private static final Collection<String> TARGET_ANNOTATIONS = Arrays.asList("@DbQuery", "@Query", "@Named");
    private static final Collection<String> TARGET_ATTRIBUTES = Arrays.asList("DbQuery", "Query", "Named");

    @Nullable
    @Override
    public PsiElement[] getGotoDeclarationTargets(PsiElement psiElement, int i, Editor editor) {

        PhpDocAttributeTargets.Target target = PhpDocAttributeTargets.resolve(psiElement);
        if (target == null) {
            return new PsiElement[0];
        }

        String name = target.name();
        if (!TARGET_ANNOTATIONS.contains(name) && !TARGET_ATTRIBUTES.contains(name)) {
            return new PsiElement[0];
        }

        if (editor == null) {
            return new PsiElement[0];
        }
        VirtualFile editorFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        String currentFilePath = editorFile == null ? "" : editorFile.getPath();

        return this.sqlGoto(target.resourceName(), psiElement.getProject(), currentFilePath);
    }

    private PsiElement @NotNull [] sqlGoto(
            @NotNull String resourceName,
            @NotNull Project project,
            @NotNull String currentFilePath
    ) {

        Settings settings = Settings.getInstance(project);
        final Collection<String> sqlPaths = settings.sqlPaths;
        final String[] names = resourceName.replace("\"", "").split(",");
        final List<PsiElement> psiElements = new ArrayList<>();

        VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
        if (baseDir == null) {
            return new PsiElement[0];
        }

        PsiManager psiManager = PsiManager.getInstance(project);
        for (String name : names) {
            String sqlFileName;
            VirtualFile targetFile;

            if (name.contains("=")) {
                sqlFileName = name.split("=")[1];
            } else if (currentFilePath.toLowerCase().contains("preview") && name.contains("original-")) {
                sqlFileName = name.replace("original-", "");
            } else {
                sqlFileName = name;
            }
            sqlFileName += ".sql";

            for (String sqlPath : sqlPaths) {
                if (!sqlPath.endsWith("/")) {
                    sqlPath += "/";
                }

                targetFile = baseDir.findFileByRelativePath(sqlPath + sqlFileName);
                if (targetFile == null) {
                    continue;
                }

                PsiFile psiFile = psiManager.findFile(targetFile);
                if (psiFile != null) {
                    psiElements.add(psiFile);
                }
            }
        }

        if (psiElements.isEmpty()) {
            return new PsiElement[0];
        }

        return psiElements.toArray(new PsiElement[0]);
    }
}