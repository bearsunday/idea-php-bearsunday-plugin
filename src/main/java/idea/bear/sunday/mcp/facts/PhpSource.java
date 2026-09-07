package idea.bear.sunday.mcp.facts;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.psi.elements.ClassConstantReference;
import com.jetbrains.php.lang.psi.elements.ClassReference;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression;
import com.jetbrains.php.util.PhpStringUtil;
import idea.bear.sunday.aop.InterceptorBindingIndexUtil;
import org.jetbrains.annotations.Nullable;

/**
 * The readings of PHP source that every fact tool needs, in one place. Each of these is a question
 * about what the source STATES rather than about what it would evaluate to at runtime, and each
 * answers {@code null} when the source does not state it -- which is the answer these tools are
 * built on, because a guess is reported as a fact.
 */
final class PhpSource {

    private PhpSource() {
    }

    /**
     * The string a literal stands for, or {@code null} when the element is not a literal whose
     * value the source states. An interpolated string states a template, not a value: its text is
     * {@code "{$this->qualifier}_dsn"} while the value is whatever the property held. Escapes are
     * decoded, because {@code "a\tb"} stands for a tab, not for a backslash and a t.
     */
    @Nullable
    static String stringValue(@Nullable PsiElement element) {
        if (!(element instanceof StringLiteralExpression literal) || literal.getFirstPsiChild() != null) {
            return null;
        }

        return PhpStringUtil.unescapeText(literal);
    }

    /**
     * The normalized class name a {@code Foo::class} expression names, or {@code null}. Any other
     * class constant is refused: {@code Registry::DEFAULT_IMPL} holds a value this cannot read,
     * and reporting the class that declares it would name the wrong class with full confidence.
     */
    @Nullable
    static String classConstFqn(@Nullable PsiElement element) {
        if (!(element instanceof ClassConstantReference reference)) {
            return null;
        }
        // PHP writes ::class case-insensitively, as it writes every constant fetch on a class.
        if (!"class".equalsIgnoreCase(reference.getName())) {
            return null;
        }
        if (!(reference.getClassReference() instanceof ClassReference resolved)) {
            return null;
        }

        return InterceptorBindingIndexUtil.normalizeFqn(resolved.getFQN());
    }

    /** Source text on one line. A call chain spans several, and may carry a docblock between them. */
    static String oneLine(@Nullable PsiElement element) {
        return element == null ? "" : element.getText().replaceAll("\\s+", " ").trim();
    }

    /** The same, cut to a length an answer can carry, with an ellipsis where it was cut. */
    static String oneLine(@Nullable PsiElement element, int max) {
        String text = oneLine(element);

        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /**
     * The class an element sits in. An anonymous module class has no name, and its FQN is the
     * namespace alone -- a name no class in the project answers to, so none is reported for it.
     */
    @Nullable
    static String enclosingClassFqn(PsiElement element) {
        PhpClass phpClass = PsiTreeUtil.getParentOfType(element, PhpClass.class);
        String fqn = phpClass == null ? null : phpClass.getFQN();

        return fqn == null || fqn.isBlank() || fqn.endsWith("\\") ? null : fqn;
    }
}
