package idea.bear.sunday.resource;

import com.intellij.patterns.ElementPattern;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.parser.PhpElementTypes;
import com.jetbrains.php.lang.psi.elements.ClassReference;
import com.jetbrains.php.lang.psi.elements.MethodReference;
import com.jetbrains.php.lang.psi.elements.ParameterList;
import com.jetbrains.php.lang.psi.elements.PhpAttribute;
import com.jetbrains.php.lang.psi.elements.PhpNamedElement;
import com.jetbrains.php.lang.psi.elements.PhpReference;
import com.jetbrains.php.lang.psi.elements.PhpTypedElement;
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression;
import idea.bear.sunday.util.ResourceHttpMethods;
import org.jetbrains.annotations.Nullable;

/**
 * Decides where BEAR.Resource URI completion is offered: the positions goto already navigates
 * from -- a request call, {@code uri()}, and the {@code #[Embed]} / {@code #[Link]} relations that
 * spell a resource URI.
 *
 * <p>The pattern stays broad and {@link #accepts} narrows it, the split
 * {@link QueryCompletionContributor} documents. Resolving the called method inside the pattern
 * instead would tie completion to the BEAR classes being resolvable, and would still not say
 * which class declared the method.
 */
class UriElementPatternHelper {

    private static final String EMBED = "Embed";
    private static final String LINK = "Link";
    private static final String RESOURCE_TYPE_MARKER = "BEAR\\Resource";
    private static final String RESOURCE_RECEIVER = "resource";

    static ElementPattern<PsiElement> getUriDefinition() {
        return PlatformPatterns.psiElement()
            .withParent(PlatformPatterns.psiElement(StringLiteralExpression.class))
            .inside(
                PlatformPatterns.or(
                    PlatformPatterns.psiElement(PhpElementTypes.PARAMETER_LIST),
                    PlatformPatterns.psiElement(PhpAttribute.class)
                )
            );
    }

    /**
     * {@code true} when the caret sits in a string that names a resource URI.
     *
     * <p>{@code uri()} and {@code toInstance()} are named distinctly enough to answer on the name
     * alone. A request verb is not: {@code ->get('...')} is a common call on containers,
     * collections and caches, so it answers only for a receiver that reads as a BEAR resource
     * client, which keeps resource URIs out of unrelated completion popups.
     */
    static boolean accepts(@Nullable PsiElement position) {
        StringLiteralExpression literal = literalAt(position);
        if (literal == null) {
            return false;
        }

        PhpAttribute attribute = PsiTreeUtil.getParentOfType(literal, PhpAttribute.class);
        if (attribute != null) {
            return isRelationTarget(attribute, literal);
        }

        return isRequestUriArgument(literal);
    }

    @Nullable
    private static StringLiteralExpression literalAt(@Nullable PsiElement position) {
        if (position instanceof StringLiteralExpression literal) {
            return literal;
        }

        return position == null ? null : PsiTreeUtil.getParentOfType(position, StringLiteralExpression.class);
    }

    /**
     * The URI argument of a relation: {@code #[Embed(src: ...)]} and {@code #[Link(href: ...)]}.
     * The {@code rel} of the same attribute is a relation name, not a URI.
     */
    private static boolean isRelationTarget(PhpAttribute attribute, StringLiteralExpression literal) {
        String name = attributeShortName(attribute);
        if (EMBED.equals(name)) {
            return isArgument(attribute, literal, "src", 0);
        }
        if (LINK.equals(name)) {
            return isArgument(attribute, literal, "href", 1);
        }

        return false;
    }

    /**
     * Only the argument itself, never a string inside it: a fragment of a concatenation or an
     * element of an array is not the URI the relation names. Descending would be right for
     * reading a relation's target out of the attribute, which is what the relation index does,
     * and wrong here, where the question is whether this string is that argument.
     */
    private static boolean isArgument(PhpAttribute attribute, StringLiteralExpression literal, String name, int index) {
        PsiElement argument = attribute.getParameter(name, index);

        return argument == literal;
    }

    @Nullable
    private static String attributeShortName(PhpAttribute attribute) {
        String fqn = attribute.getFQN();
        if (fqn == null || fqn.isBlank()) {
            ClassReference classReference = attribute.getClassReference();

            return classReference == null ? null : classReference.getName();
        }
        int separator = fqn.lastIndexOf('\\');

        return separator >= 0 ? fqn.substring(separator + 1) : fqn;
    }

    private static boolean isRequestUriArgument(StringLiteralExpression literal) {
        if (!(literal.getParent() instanceof ParameterList parameterList)) {
            return false;
        }
        if (!(parameterList.getParent() instanceof MethodReference methodReference)) {
            return false;
        }

        String name = methodReference.getName();
        if (name == null) {
            return false;
        }
        if (name.equals("uri")) {
            return isFirstArgument(parameterList, literal);
        }
        if (name.equals("toInstance")) {
            return true;
        }

        return ResourceHttpMethods.isVerb(name)
            && isFirstArgument(parameterList, literal)
            && isResourceReceiver(methodReference);
    }

    private static boolean isFirstArgument(ParameterList parameterList, StringLiteralExpression literal) {
        PsiElement[] parameters = parameterList.getParameters();

        return parameters.length > 0 && parameters[0] == literal;
    }

    /**
     * A receiver named {@code resource} covers the idiomatic {@code $this->resource->get(...)} and
     * {@code $resource->get(...)} without needing the BEAR classes on the path; a declared type
     * naming {@code BEAR\Resource} covers the rest.
     */
    private static boolean isResourceReceiver(MethodReference methodReference) {
        PsiElement receiver = methodReference.getClassReference();
        if (receiver == null) {
            return false;
        }
        if (RESOURCE_RECEIVER.equals(receiverName(receiver))) {
            return true;
        }

        return receiver instanceof PhpTypedElement typed
            && typed.getType().toString().contains(RESOURCE_TYPE_MARKER);
    }

    /**
     * A variable is a named element, a field access such as {@code $this->resource} is a
     * reference; both name the receiver.
     */
    @Nullable
    private static String receiverName(PsiElement receiver) {
        if (receiver instanceof PhpReference reference) {
            return reference.getName();
        }

        return receiver instanceof PhpNamedElement named ? named.getName() : null;
    }
}
