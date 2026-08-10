package idea.bear.sunday.mcp.facts;

import com.jetbrains.php.lang.psi.elements.ClassReference;
import com.jetbrains.php.lang.psi.elements.ParameterList;
import com.jetbrains.php.lang.psi.elements.PhpAttribute;
import idea.bear.sunday.aop.InterceptorBindingIndexUtil;
import org.jetbrains.annotations.Nullable;

/**
 * Reads a PHP attribute the way the fact tools report it. An attribute is written under a short
 * name that the file's {@code use} statements alias to a class, so the same text can name
 * different classes in different files; {@link #fqn} is that class, resolved, and is
 * {@code null} when the reference does not resolve rather than a guess.
 */
final class Attributes {

    private Attributes() {
    }

    /** The class an attribute resolves to, or {@code null} when the reference does not resolve. */
    @Nullable
    static String fqn(PhpAttribute attribute) {
        String fqn = attribute.getFQN();

        return fqn == null || fqn.isBlank() ? null : InterceptorBindingIndexUtil.normalizeFqn(fqn);
    }

    /**
     * The last segment of the resolved class name, falling back to the name the attribute is
     * written under when the reference does not resolve.
     */
    @Nullable
    static String shortName(PhpAttribute attribute) {
        String fqn = fqn(attribute);
        if (fqn != null) {
            return fqn.substring(fqn.lastIndexOf('\\') + 1);
        }
        ClassReference classReference = attribute.getClassReference();

        return classReference == null ? null : classReference.getName();
    }

    /** The source text between the parentheses, or {@code null} when the attribute has none. */
    @Nullable
    static String argsText(PhpAttribute attribute) {
        ParameterList parameterList = attribute.getParameterList();
        if (parameterList == null) {
            return null;
        }
        String text = parameterList.getText().trim();
        if (text.startsWith("(") && text.endsWith(")")) {
            text = text.substring(1, text.length() - 1).trim();
        }

        return text.isEmpty() ? null : text;
    }
}
