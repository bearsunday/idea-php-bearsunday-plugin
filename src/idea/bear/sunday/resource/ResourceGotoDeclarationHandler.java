package idea.bear.sunday.resource;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import idea.bear.sunday.BearSundayProjectComponent;
import idea.bear.sunday.index.ResourceIndex;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ResourceGotoDeclarationHandler implements GotoDeclarationHandler {

    @Nullable
    @Override
    public PsiElement[] getGotoDeclarationTargets(PsiElement psiElement, int i, Editor editor) {

        Project project = psiElement.getProject();
        if(!BearSundayProjectComponent.isEnabled(project)) {
            return new PsiElement[0];
        }

        String resourceName = psiElement.getText();
        if(!resourceName.startsWith("app://") || resourceName.startsWith("page://")) {
            return new PsiElement[0];
        }

        return ResourceIndex.getFileByUri(resourceName, psiElement.getProject(), GlobalSearchScope.projectScope(project));
    }

    @Nullable
    @Override
    public String getActionText(DataContext dataContext) {
        return "Go to Resource class";
    }

}