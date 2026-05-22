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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the embed-navigation gutter icon next to a Qiq output tag whose expression is a bare
 * {@code $this->var}, when {@code var} is bound by an {@code #[Embed(rel: 'var')]} attribute on
 * the surrounding resource class.
 *
 * <p>Registered for {@code language="Qiq"}, the provider runs on the Qiq host PSI and is invoked
 * once per tag. An earlier attempt registered for {@code language="PHP"} received the injected
 * {@code FieldReference} from two daemon passes — the host file's
 * {@code collectLineMarkersForInjected} and the injected fragment's own line-markers pass — and
 * produced two stacked icons per variable. Living on the host PSI avoids that duplication.
 *
 * <p>{@link QiqSupport#isQiqOutputExpression} filters out plain PHP blocks
 * ({@code QiqPhpHost}) and code tags ({@code QiqCodeHost} with element type
 * {@code CODE_BODY}/{@code CODE_CONTENT}), so only the print variants
 * ({@code RAW_CONTENT}, {@code ESCAPE_*_CONTENT}) reach the matching step.
 *
 * <p>The variable name is taken from a regex over the host's own text, not from walking the
 * injected PHP PSI. The Qiq injector coalesces every host in the file into one PHP fragment, so
 * {@code InjectedLanguageManager#enumerate(host, …)} would surface every {@code $this->X} in the
 * document instead of just this tag's. The regex also rejects chained accesses
 * ({@code $this->X->…}, {@code $this->X[…]}, {@code $this->X(…)}): when the expression
 * dereferences the embed instead of rendering it directly, the icon would point users at the
 * sub-resource template rather than at what's actually being shown, which is misleading.
 */
public class EmbedQiqLineMarkerProvider implements LineMarkerProvider {

    private static final Icon EMBED_ICON =
            IconLoader.getIcon("/icons/embed.svg", EmbedQiqLineMarkerProvider.class);

    /**
     * Matches a Qiq output tag host whose text is solely a {@code $this->name} property access,
     * optionally surrounded by whitespace. Used with {@link Matcher#matches} so the pattern is
     * anchored to the entire host text — any extra expression (further chaining like
     * {@code ->X}, subscripting {@code […]}, invocation {@code (…)}, concatenation, or another
     * subexpression that wraps {@code $this->X}) causes a non-match. That way the gutter icon
     * only fires when the tag is rendering the embedded resource directly, not when it merely
     * dereferences it.
     */
    private static final Pattern STANDALONE_THIS_PROPERTY =
            Pattern.compile("\\s*\\$this->(\\w+)\\s*");

    @Nullable
    @Override
    public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!QiqSupport.isQiqOutputExpression(element)) {
            return null;
        }
        PsiFile psiFile = element.getContainingFile();
        if (psiFile == null) {
            return null;
        }
        VirtualFile hostFile = psiFile.getOriginalFile().getVirtualFile();
        if (hostFile == null) {
            return null;
        }
        Project project = element.getProject();
        if (!QiqSupport.INSTANCE.accepts(hostFile, project)) {
            return null;
        }
        PhpClass parentResource = QiqSupport.INSTANCE.resolveResourceClass(hostFile, project);
        if (parentResource == null) {
            return null;
        }
        EmbedRef ref = findEmbedReference(element.getText(), parentResource);
        if (ref == null) {
            return null;
        }
        String varName = ref.varName();
        String srcUri = ref.srcUri();
        NotNullLazyValue<Collection<? extends PsiElement>> targets = NotNullLazyValue.lazy(
                () -> EmbedResolver.resolveEmbeddedTemplates(srcUri, parentResource, QiqSupport.INSTANCE, project));
        return NavigationGutterIconBuilder.create(EMBED_ICON)
                .setTargets(targets)
                .setTooltipText("Embed: " + varName + " → " + srcUri)
                .setPopupTitle("Embedded template")
                .createLineMarkerInfo(element);
    }

    /**
     * Returns the {@code $this->X} reference when {@code hostText} is entirely a stand-alone
     * {@code $this->X} expression and {@code X} is bound by an {@code #[Embed(rel: 'X')]}
     * attribute on {@code parentResource}.
     */
    @Nullable
    private static EmbedRef findEmbedReference(@NotNull String hostText, @NotNull PhpClass parentResource) {
        Matcher matcher = STANDALONE_THIS_PROPERTY.matcher(hostText);
        if (!matcher.matches()) {
            return null;
        }
        String name = matcher.group(1);
        String src = EmbedResolver.findEmbedSrcUri(parentResource, name);
        return src == null ? null : new EmbedRef(name, src);
    }

    private record EmbedRef(@NotNull String varName, @NotNull String srcUri) {
    }
}
