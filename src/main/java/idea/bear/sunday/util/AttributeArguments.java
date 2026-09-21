package idea.bear.sunday.util;

import com.intellij.psi.PsiElement;
import com.jetbrains.php.lang.psi.elements.PhpAttribute;
import org.jetbrains.annotations.Nullable;

/**
 * Reads one argument of a PHP attribute by the parameter it belongs to.
 */
public final class AttributeArguments {

    private AttributeArguments() {
    }

    /**
     * The argument written for a parameter, by name or by position, or {@code null} when the
     * attribute gives that parameter none.
     *
     * <p>{@code PhpAttribute#getParameter(String, int)} alone answers with whatever argument sits
     * at the index, even when that argument belongs to another parameter written by name: for
     * {@code #[JsonSchema(key: 'user')]} it answers {@code user} for {@code schema}, reading a body
     * slot as a file name. Only arguments written without a name are counted towards the index.
     */
    @Nullable
    public static PsiElement of(PhpAttribute attribute, String name, int index) {
        return declares(attribute, name, index) ? attribute.getParameter(name, index) : null;
    }

    private static boolean declares(PhpAttribute attribute, String name, int index) {
        int positional = 0;
        for (PhpAttribute.PhpAttributeArgument argument : attribute.getArguments()) {
            String argumentName = argument.getName();
            if (name.equals(argumentName)) {
                return true;
            }
            if (argumentName == null || argumentName.isEmpty()) {
                if (positional == index) {
                    return true;
                }
                positional++;
            }
        }

        return false;
    }
}
