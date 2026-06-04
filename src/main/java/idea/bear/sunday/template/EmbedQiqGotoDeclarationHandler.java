package idea.bear.sunday.template;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.jetbrains.php.lang.psi.elements.FieldReference;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * Cmd+click on {@code embedded} in a Qiq template's {@code {{= $this->embedded }}} jumps to the
 * template of the resource embedded via {@code #[Embed(rel: 'embedded')]} on the surrounding
 * resource class.
 *
 * <p>The property access is a PHP fragment injected by the Qiq plugin. A GotoDeclarationHandler
 * is language-agnostic and is invoked for injected PHP, and—unlike a contributed PsiReference—it
 * does not compete with the FieldReference's own member resolution.
 */
public class EmbedQiqGotoDeclarationHandler implements GotoDeclarationHandler {

    @Nullable
    @Override
    public PsiElement[] getGotoDeclarationTargets(@Nullable PsiElement sourceElement, int offset, Editor editor) {
        if (sourceElement == null) {
            return null;
        }
        if (!(sourceElement.getParent() instanceof FieldReference fieldReference)) {
            return null;
        }
        String varName = QiqSupport.INSTANCE.extractVariableName(fieldReference);
        // Act only on the property-name leaf ($this->[name]), not the "->" or $this leaves.
        if (varName == null || !varName.equals(sourceElement.getText())) {
            return null;
        }
        Project project = sourceElement.getProject();
        VirtualFile hostFile = QiqSupport.qiqHostFile(fieldReference, project);
        if (hostFile == null) {
            return null;
        }
        PhpClass parentResource = QiqSupport.INSTANCE.resolveResourceClass(hostFile, project);
        if (parentResource == null) {
            return null;
        }
        String srcUri = EmbedResolver.findEmbedSrcUri(parentResource, varName);
        if (srcUri == null) {
            return null;
        }
        Collection<? extends PsiElement> targets =
                EmbedResolver.resolveEmbeddedTemplates(srcUri, parentResource, QiqSupport.INSTANCE, project);
        return targets.isEmpty() ? null : targets.toArray(PsiElement.EMPTY_ARRAY);
    }

    @Nullable
    @Override
    public String getActionText(@NotNull DataContext context) {
        return null;
    }
}
