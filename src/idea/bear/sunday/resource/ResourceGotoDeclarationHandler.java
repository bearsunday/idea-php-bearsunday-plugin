package idea.bear.sunday.resource;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.jetbrains.php.lang.documentation.phpdoc.lexer.PhpDocTokenTypes;
import idea.bear.sunday.BearSundayProjectComponent;
import idea.bear.sunday.index.ResourceIndex;
import org.jetbrains.annotations.Nullable;

public class ResourceGotoDeclarationHandler implements GotoDeclarationHandler {

    @Nullable
    @Override
    public PsiElement[] getGotoDeclarationTargets(PsiElement psiElement, int i, Editor editor) {

        if (psiElement == null) {
            return new PsiElement[0];
        }

        Project project = psiElement.getProject();
        if(!BearSundayProjectComponent.isEnabled(project)) {
            return new PsiElement[0];
        }

        String resourceName = psiElement.getText();
        if(((LeafPsiElement) psiElement).getElementType().toString().equals("single quoted string")
                && !resourceName.startsWith("app://") && !resourceName.startsWith("page://")) {
            return new PsiElement[0];
        } else if (((LeafPsiElement) psiElement).getElementType().equals(PhpDocTokenTypes.DOC_STRING)) {
            resourceName = resourceName.replaceAll("\"", "");
        }

        return ResourceIndex.getFileByUri(resourceName, psiElement.getProject(), editor);
    }

    @Nullable
    @Override
    public String getActionText(DataContext dataContext) {
        return "Go to Resource class";
    }

}