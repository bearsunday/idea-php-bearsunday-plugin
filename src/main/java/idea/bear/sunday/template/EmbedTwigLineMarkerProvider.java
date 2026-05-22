package idea.bear.sunday.template;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.NotNullLazyValue;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.twig.elements.TwigElementTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.Collection;

public class EmbedTwigLineMarkerProvider implements LineMarkerProvider {

    private static final Icon EMBED_ICON =
            IconLoader.getIcon("/icons/embed.svg", EmbedTwigLineMarkerProvider.class);

    @Nullable
    @Override
    public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        // Only inspect leaves: getLineMarkerInfo is expected to anchor on the smallest PSI.
        if (element.getFirstChild() != null) {
            return null;
        }
        PsiElement parent = element.getParent();
        if (parent == null || parent.getNode() == null) {
            return null;
        }
        // The leaf identifier sits inside a VARIABLE_REFERENCE composite element when used
        // as a Twig variable. Re-use TwigSupport's name extraction (validates print block too).
        if (parent.getNode().getElementType() != TwigElementTypes.VARIABLE_REFERENCE) {
            return null;
        }
        PsiFile psiFile = element.getContainingFile();
        if (psiFile == null) {
            return null;
        }
        VirtualFile file = psiFile.getOriginalFile().getVirtualFile();
        if (file == null) {
            return null;
        }
        Project project = element.getProject();
        if (!TwigSupport.INSTANCE.accepts(file, project)) {
            return null;
        }
        String varName = TwigSupport.INSTANCE.extractVariableName(parent);
        if (varName == null) {
            return null;
        }
        PhpClass parentResource = TwigSupport.INSTANCE.resolveResourceClass(file, project);
        if (parentResource == null) {
            return null;
        }
        String srcUri = EmbedResolver.findEmbedSrcUri(parentResource, varName);
        if (srcUri == null) {
            return null;
        }
        NotNullLazyValue<Collection<? extends PsiElement>> targets = NotNullLazyValue.lazy(
                () -> EmbedResolver.resolveEmbeddedTemplates(srcUri, parentResource, TwigSupport.INSTANCE, project));
        return NavigationGutterIconBuilder.create(EMBED_ICON)
                .setTargets(targets)
                .setTooltipText("Embed: " + varName + " → " + srcUri)
                .setPopupTitle("Embedded template")
                .createLineMarkerInfo(element);
    }
}
