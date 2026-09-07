package idea.bear.sunday.mcp.facts;

import com.intellij.psi.PsiElement;
import com.jetbrains.php.lang.psi.elements.FieldReference;
import com.jetbrains.php.lang.psi.elements.MethodReference;
import com.jetbrains.php.lang.psi.elements.Variable;
import idea.bear.sunday.aop.InterceptorBindingIndexUtil;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;
import java.util.function.BinaryOperator;

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
            case "logicalor" -> folded(arguments, call, Or::new);
            case "logicaland" -> folded(arguments, call, And::new);
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

    /**
     * {@code logicalAnd(a, b, c)} read as {@code (a and b) and c}. Ray.Aop's {@code Matcher}
     * declares two parameters but collects them with {@code func_get_args()}, and
     * {@code LogicalAndMatcher} folds every argument it was given -- which is how
     * {@code ray/aura-sql-module} writes a three-argument {@code logicalAnd}. Reading only the
     * two-argument form would carry that whole pointcut as unreadable.
     */
    private static PointcutMatcher folded(
        PsiElement[] arguments,
        MethodReference call,
        BinaryOperator<PointcutMatcher> combine
    ) {
        // PHP itself refuses fewer than the two the signature declares.
        if (arguments.length < 2) {
            return new Unreadable(text(call));
        }
        PointcutMatcher matcher = parse(arguments[0]);
        for (int i = 1; i < arguments.length; i++) {
            matcher = combine.apply(matcher, parse(arguments[i]));
        }

        return matcher;
    }

    /** The class a single {@code Foo::class} or {@code 'Foo'} argument names. */
    private static Optional<String> className(PsiElement[] arguments) {
        if (arguments.length != 1) {
            return Optional.empty();
        }
        String fqn = PhpSource.classConstFqn(arguments[0]);
        if (fqn != null) {
            return Optional.of(fqn);
        }

        // Ray.Aop declares the argument as a string, so a plain literal names a class as well as
        // ::class does -- and the two are the same name to PHP.
        return literal(arguments).map(InterceptorBindingIndexUtil::normalizeFqn);
    }

    /**
     * The string a single literal argument stands for. An empty one is not a prefix any name
     * starts with in a way worth reporting, and {@code annotatedWith('')} names no class.
     */
    private static Optional<String> literal(PsiElement[] arguments) {
        String value = arguments.length == 1 ? PhpSource.stringValue(arguments[0]) : null;

        return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /** Source text on one line, as the binding lookup reports its own calls. */
    private static String text(@Nullable PsiElement element) {
        return PhpSource.oneLine(element, MAX_TEXT);
    }
}
