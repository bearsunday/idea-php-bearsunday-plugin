package idea.bear.sunday.mcp.facts;

import com.intellij.psi.PsiElement;
import com.jetbrains.php.lang.psi.elements.ClassConstantReference;
import com.jetbrains.php.lang.psi.elements.ClassReference;
import com.jetbrains.php.lang.psi.elements.FieldReference;
import com.jetbrains.php.lang.psi.elements.MethodReference;
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression;
import com.jetbrains.php.lang.psi.elements.Variable;
import com.jetbrains.php.util.PhpStringUtil;
import idea.bear.sunday.aop.InterceptorBindingIndexUtil;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

/**
 * A Ray.Aop matcher expression, read from the source that builds it. The vocabulary is closed:
 * {@code Ray\Aop\Matcher} declares exactly seven methods, and every pointcut in {@code bear/*} and
 * {@code ray/*} is written in them. An expression outside that vocabulary -- a matcher class of
 * one's own, an argument whose value the source does not state -- is carried as
 * {@link Unreadable} rather than guessed at, so that a pointcut this cannot decide is reported as
 * undecided instead of quietly matching nothing.
 */
sealed interface PointcutMatcher {

    /** Longer than any real matcher expression; only an unreadable one carries source text. */
    int MAX_TEXT = 300;

    /** {@code $this->matcher->any()}. */
    record Any() implements PointcutMatcher {
    }

    /** {@code annotatedWith(Foo::class)}: the attribute class, or one that extends it. */
    record AnnotatedWith(String attribute) implements PointcutMatcher {
    }

    /** {@code subclassesOf(Foo::class)}: the class itself or a subclass. Classes only. */
    record SubclassesOf(String superClass) implements PointcutMatcher {
    }

    /** {@code startsWith('onGet')}: a prefix of the class or method name, compared as written. */
    record StartsWith(String prefix) implements PointcutMatcher {
    }

    record Or(PointcutMatcher left, PointcutMatcher right) implements PointcutMatcher {
    }

    record And(PointcutMatcher left, PointcutMatcher right) implements PointcutMatcher {
    }

    record Not(PointcutMatcher matcher) implements PointcutMatcher {
    }

    /** An expression the source does not state in Ray.Aop's vocabulary, kept with its own text. */
    record Unreadable(String text) implements PointcutMatcher {
    }

    /**
     * Reads a matcher expression. Every argument of {@code bindInterceptor()} is one, so an
     * argument that is not a matcher call at all -- a variable holding one, a matcher class
     * written by hand -- is read as unreadable rather than skipped.
     */
    static PointcutMatcher parse(@Nullable PsiElement expression) {
        if (!(expression instanceof MethodReference call)) {
            return new Unreadable(text(expression));
        }
        // Ray.Aop's matchers are built through AbstractModule's own protected $matcher, so a call
        // on anything else builds something this has not read the semantics of.
        if (!isMatcherReceiver(call.getClassReference())) {
            return new Unreadable(text(call));
        }
        String name = call.getName();
        PsiElement[] arguments = call.getParameters();
        if (name == null) {
            return new Unreadable(text(call));
        }

        return switch (name.toLowerCase(Locale.ROOT)) {
            case "any" -> arguments.length == 0 ? new Any() : new Unreadable(text(call));
            case "annotatedwith" -> className(arguments)
                .<PointcutMatcher>map(AnnotatedWith::new)
                .orElseGet(() -> new Unreadable(text(call)));
            case "subclassesof" -> className(arguments)
                .<PointcutMatcher>map(SubclassesOf::new)
                .orElseGet(() -> new Unreadable(text(call)));
            case "startswith" -> literal(arguments)
                .<PointcutMatcher>map(StartsWith::new)
                .orElseGet(() -> new Unreadable(text(call)));
            case "logicalor" -> arguments.length == 2
                ? new Or(parse(arguments[0]), parse(arguments[1]))
                : new Unreadable(text(call));
            case "logicaland" -> arguments.length == 2
                ? new And(parse(arguments[0]), parse(arguments[1]))
                : new Unreadable(text(call));
            case "logicalnot" -> arguments.length == 1
                ? new Not(parse(arguments[0]))
                : new Unreadable(text(call));
            // Ray.Aop's Matcher declares no other method, so a call this does not know is not one
            // whose meaning can be assumed from its name.
            default -> new Unreadable(text(call));
        };
    }

    /** How the expression is written back into the answer, in Ray.Aop's own spelling. */
    default String text() {
        return switch (this) {
            case Any ignored -> "any()";
            case AnnotatedWith(String attribute) -> "annotatedWith(" + attribute + ")";
            case SubclassesOf(String superClass) -> "subclassesOf(" + superClass + ")";
            case StartsWith(String prefix) -> "startsWith('" + prefix + "')";
            case Or(PointcutMatcher left, PointcutMatcher right) ->
                "logicalOr(" + left.text() + ", " + right.text() + ")";
            case And(PointcutMatcher left, PointcutMatcher right) ->
                "logicalAnd(" + left.text() + ", " + right.text() + ")";
            case Not(PointcutMatcher matcher) -> "logicalNot(" + matcher.text() + ")";
            case Unreadable(String text) -> text;
        };
    }

    /**
     * Whether the receiver is the module's own matcher. {@code AbstractModule} declares
     * {@code $matcher} protected and builds it itself, so {@code $this->matcher} is how every
     * module in {@code bear/*} and {@code ray/*} reaches one.
     */
    private static boolean isMatcherReceiver(@Nullable PsiElement receiver) {
        return receiver instanceof FieldReference field
            && "matcher".equals(field.getName())
            && field.getClassReference() instanceof Variable variable
            && "this".equals(variable.getName());
    }

    /** The class a single {@code Foo::class} or {@code 'Foo'} argument names. */
    private static Optional<String> className(PsiElement[] arguments) {
        if (arguments.length != 1) {
            return Optional.empty();
        }
        PsiElement argument = arguments[0];
        if (argument instanceof ClassConstantReference reference
            && "class".equalsIgnoreCase(reference.getName())
            && reference.getClassReference() instanceof ClassReference resolved) {
            return Optional.ofNullable(InterceptorBindingIndexUtil.normalizeFqn(resolved.getFQN()));
        }

        // Ray.Aop declares the argument as a string, so a plain literal names a class as well as
        // ::class does -- and the two are the same name to PHP.
        return literal(arguments).map(InterceptorBindingIndexUtil::normalizeFqn);
    }

    /**
     * The string a single literal argument stands for. An interpolated string states a template
     * rather than a value, so it is not one: {@code "on{$verb}"} is a prefix only at runtime.
     */
    private static Optional<String> literal(PsiElement[] arguments) {
        if (arguments.length != 1
            || !(arguments[0] instanceof StringLiteralExpression string)
            || string.getFirstPsiChild() != null) {
            return Optional.empty();
        }
        String value = PhpStringUtil.unescapeText(string);

        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /** Source text on one line, as the binding lookup reports its own calls. */
    private static String text(@Nullable PsiElement element) {
        if (element == null) {
            return "";
        }
        String text = element.getText().replaceAll("\\s+", " ").trim();

        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "…";
    }
}
