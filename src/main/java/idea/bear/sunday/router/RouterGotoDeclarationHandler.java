package idea.bear.sunday.router;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.jetbrains.php.lang.psi.elements.impl.StringLiteralExpressionImpl;
import idea.bear.sunday.Settings;
import idea.bear.sunday.util.UriUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.WordUtils;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class RouterGotoDeclarationHandler implements GotoDeclarationHandler {

    @Nullable
    @Override
    public PsiElement[] getGotoDeclarationTargets(PsiElement psiElement, int i, Editor editor) {

        if (psiElement == null) {
            return new PsiElement[0];
        }

        Project project = editor.getProject();
        if (project == null) {
            return new PsiElement[0];
        }

        Settings settings = Settings.getInstance(project);
        if (!editor.getVirtualFile().getName().equals(settings.auraRouteFile)) {
            return new PsiElement[0];
        }

        PsiElement context = psiElement.getContext();
        if (context == null) {
            return new PsiElement[0];
        }
        if (!(context instanceof StringLiteralExpressionImpl stringLiteralExpressionImpl)) {
            return new PsiElement[0];
        }

        String contents = stringLiteralExpressionImpl.getContents();
        String resourceName = UriUtil.getUriValue(contents);
        if (resourceName == null) {
            return new PsiElement[0];
        }

        List<PsiElement> psiElements = new ArrayList<>();
        String resourceFileName = StringUtils.remove(WordUtils.capitalizeFully(resourceName, '/', '-'), "-") + ".php";
        PsiManager psiManager = PsiManager.getInstance(project);
        Collection<String> resourcePaths = settings.resourcePaths;
        for (String resourcePath : resourcePaths) {
            String findFilePath = resourcePath + resourceFileName;

            VirtualFile targetFile = project.getBaseDir().findFileByRelativePath(findFilePath);
            if (targetFile == null) {
                continue;
            }

            PsiFile pfiFile = psiManager.findFile(targetFile);
            psiElements.add(pfiFile);
        }

        if (psiElements.isEmpty()) {
            return new PsiElement[0];
        }

        return psiElements.toArray(new PsiElement[0]);
    }
}