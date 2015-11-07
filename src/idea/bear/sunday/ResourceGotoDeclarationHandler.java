package idea.bear.sunday;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class ResourceGotoDeclarationHandler implements GotoDeclarationHandler {

    @Nullable
    @Override
    public PsiElement[] getGotoDeclarationTargets(PsiElement psiElement, int i, Editor editor) {
        String resourceName = psiElement.getText();
        if(!resourceName.startsWith("app://") || resourceName.startsWith("page://")) {
            return new PsiElement[0];
        }

        String roDir = "/src/Resource/App/";
        String roClass = resourceName.replace("app://self/", "");

        if(resourceName.startsWith("page://")) {
            roDir = "/src/Resource/Page/";
            roClass = resourceName.replace("page://self/", "");
        }

        String[] roArray = roClass.split("/");
        StringBuffer stringBuffer = new StringBuffer();
        for (i = 0; i < roArray.length; i++) {
            stringBuffer.append(Character.toUpperCase(roArray[i].charAt(0))).append(roArray[i].substring(1));
            if (i + 1 != roArray.length) {
                stringBuffer.append("/");
            }
        }
        roClass = stringBuffer.toString() + ".php";

        Project project = psiElement.getProject();
        VirtualFile targetFile = project.getBaseDir().findFileByRelativePath(roDir + roClass);
        PsiFile psiFile = PsiManager.getInstance(psiElement.getProject()).findFile(targetFile);
        List<PsiElement> psiElements = new ArrayList<PsiElement>();
        psiElements.add(psiFile.getFirstChild());

        return psiElements.toArray(new PsiElement[psiElements.size()]);
    }

    @Nullable
    @Override
    public String getActionText(DataContext dataContext) {
        return null;
    }

}