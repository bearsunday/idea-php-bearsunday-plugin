package idea.bear.sunday.mcp.facts;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression;
import com.jetbrains.php.lang.psi.elements.ArrayHashElement;
import com.jetbrains.php.lang.psi.elements.FieldReference;
import com.jetbrains.php.lang.psi.elements.ForeachStatement;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.MethodReference;
import com.jetbrains.php.lang.psi.elements.NewExpression;
import com.jetbrains.php.lang.psi.elements.Parameter;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.PhpPsiElement;
import com.jetbrains.php.lang.psi.elements.Variable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A module that binds the entries of an array it was handed, read from the two halves the source
 * really states: the array at the {@code install()} call, and the loop in the module's own body.
 *
 * <p>{@code BEAR\Sunday\Module\Constant\NamedModule} is the one every BEAR application installs:
 *
 * <pre>{@code
 * $this->install(new NamedModule(['S3_BUCKET' => getenv('S3_BUCKET'), ...]));
 * // and, in NamedModule:
 * foreach ($this->names as $annotatedWith => $instance) {
 *     $this->bind()->annotatedWith($annotatedWith)->toInstance($instance);
 * }
 * }</pre>
 *
 * <p>Read as one chain, that binds under a qualifier the source does not state, and the key it is
 * filed under cannot be made -- so the whole loop used to be given up on, and every name it binds
 * reported as bound by nobody. But the two halves together do state the keys: the VALUES are
 * {@code getenv()} calls no reading of the source can evaluate, while the KEYS are string literals
 * sitting in the installing module's own file. A key is all the container needs.
 *
 * <p>So the loop is expanded once per array entry, with the loop's key variable standing for that
 * entry's key and nothing else substituted. What the entry is bound TO stays as unread as it was,
 * which is the honest half of the answer: this says who sets a name, never what the value is.
 *
 * <p>The module is found by its SHAPE and not by its name. Nothing here mentions
 * {@code NamedModule}: a module of one's own written the same way is read the same way, and a
 * BEAR.Sunday that rewrote the class would stop matching and go back to reporting the binding as
 * unreadable rather than quietly answering from a shape that no longer holds.
 */
final class ArrayBindings {

    private static final String ANNOTATED_WITH = "annotatedWith";
    private static final String BIND = "bind";
    private static final String THIS = "this";

    /** Past any real constant list, and a stop for a generated file that is not one. */
    private static final int MAX_ENTRIES = 500;

    private ArrayBindings() {
    }

    /**
     * The loop a module binds an array through: which constructor parameter holds the array, and
     * the {@code bind()} call the chain starts at. The chain is kept rather than taken apart,
     * because reading what it binds to is {@link DiBindingLookupService}'s reading and not a
     * second one made here.
     */
    record Shape(int parameterIndex, String parameterName, MethodReference bindCall) {
    }

    /** One array entry: the name it binds under, and the entry it was read from. */
    record Entry(String key, PsiElement anchor) {
    }

    /**
     * What one {@code install()} call expands to. {@code keysUnreadable} counts the entries whose
     * key the source does not state -- a constant, an integer, a spread -- which are left as
     * unreadable as the whole loop used to be rather than passed over in silence.
     */
    record Expansion(MethodReference bindCall, List<Entry> entries, int keysUnreadable) {
    }

    /**
     * The shape a module binds an array through, or {@code null} when it binds no such array. Only
     * the class's own body is read: a loop written inside an anonymous class declared in it belongs
     * to that class, and a base module's loop is that module's to expand.
     *
     * <p>The property has to be a PROMOTED constructor parameter. A property assigned in the body
     * can be assigned anything -- a merge, a default, a value from somewhere else entirely -- and
     * reading the constructor argument as though it were the property would claim keys the module
     * never binds. Promotion is the one form where the source states that the two are the same.
     */
    @Nullable
    static Shape shapeOf(PhpClass module) {
        Method constructor = module.getConstructor();
        if (constructor == null) {
            return null;
        }
        for (ForeachStatement loop : PsiTreeUtil.findChildrenOfType(module, ForeachStatement.class)) {
            if (PsiTreeUtil.getParentOfType(loop, PhpClass.class) != module) {
                continue;
            }
            Variable key = loop.getKey();
            String keyName = key == null ? null : key.getName();
            String field = promotedField(loop.getArray());
            if (keyName == null || field == null) {
                continue;
            }
            int index = parameterIndex(constructor, field);
            if (index < 0) {
                continue;
            }
            MethodReference bindCall = chainKeyedBy(loop, keyName);
            if (bindCall != null) {
                return new Shape(index, field, bindCall);
            }
        }

        return null;
    }

    /**
     * The entries one {@code new FooModule([...])} states, or {@code null} when it states no array
     * at that argument -- a variable, a call, a named argument, or no argument at all. The caller
     * reports that as an install whose argument could not be read, which is what it is: this
     * refuses to guess at an array it was not shown.
     */
    @Nullable
    static Expansion expand(Shape shape, NewExpression call) {
        PsiElement[] arguments = call.getParameters();
        if (shape.parameterIndex() >= arguments.length) {
            return null;
        }
        if (!(arguments[shape.parameterIndex()] instanceof ArrayCreationExpression array)) {
            return null;
        }

        List<Entry> entries = new ArrayList<>();
        int unreadable = 0;
        for (ArrayHashElement element : array.getHashElements()) {
            String key = keyOf(element.getKey());
            if (key == null || entries.size() >= MAX_ENTRIES) {
                unreadable++;

                continue;
            }
            entries.add(new Entry(key, element));
        }
        // An entry written without a key -- ['a', 'b'], or a spread -- is bound under the integer
        // PHP would number it, or under keys from an array this cannot see. Counted rather than
        // passed over, so a partial expansion never reads as a whole one.
        unreadable += listElements(array);

        return new Expansion(shape.bindCall(), List.copyOf(entries), unreadable);
    }

    /**
     * The name an array key states. A string literal is the name itself; {@code Foo::class} is
     * read the way {@code annotatedWith(Foo::class)} is read, so a name written either way is
     * matched against a {@code #[Named(Foo::class)]} that asks for it. Anything else -- a constant,
     * an integer, an interpolated string -- states no name here.
     */
    @Nullable
    private static String keyOf(@Nullable PhpPsiElement key) {
        String literal = PhpSource.stringValue(key);

        return literal != null ? literal : PhpSource.classConstFqn(key);
    }

    /** How many entries of an array are written without a key of their own. */
    private static int listElements(ArrayCreationExpression array) {
        int count = 0;
        for (PhpPsiElement child : PsiTreeUtil.getChildrenOfTypeAsList(array, PhpPsiElement.class)) {
            if (!(child instanceof ArrayHashElement)) {
                count++;
            }
        }

        return count;
    }

    /** The property a {@code foreach ($this->names as ...)} walks, or {@code null}. */
    @Nullable
    private static String promotedField(@Nullable PsiElement array) {
        if (!(array instanceof FieldReference reference)) {
            return null;
        }
        if (!(reference.getClassReference() instanceof Variable receiver) || !THIS.equals(receiver.getName())) {
            return null;
        }

        return reference.getName();
    }

    /** Where a promoted constructor parameter of this name stands in the argument list, or -1. */
    private static int parameterIndex(Method constructor, String field) {
        Parameter[] parameters = constructor.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isPromotedField() && field.equals(parameters[i].getName())) {
                return i;
            }
        }

        return -1;
    }

    /**
     * The {@code bind()} a chain in this loop starts at, when the chain names the loop's KEY as its
     * qualifier. Any other chain in the loop binds under something this expansion does not supply,
     * and expanding it would file every entry under one name.
     */
    @Nullable
    private static MethodReference chainKeyedBy(ForeachStatement loop, String keyName) {
        for (MethodReference call : PsiTreeUtil.findChildrenOfType(loop, MethodReference.class)) {
            if (!ANNOTATED_WITH.equalsIgnoreCase(call.getName())) {
                continue;
            }
            PsiElement[] arguments = call.getParameters();
            if (arguments.length != 1
                || !(arguments[0] instanceof Variable qualifier)
                || !keyName.equals(qualifier.getName())) {
                continue;
            }
            MethodReference bindCall = baseBind(call);
            if (bindCall != null) {
                return bindCall;
            }
        }

        return null;
    }

    /** The {@code $this->bind(...)} a chain of calls was built on, or {@code null}. */
    @Nullable
    private static MethodReference baseBind(MethodReference call) {
        PsiElement current = call.getClassReference();
        while (current instanceof MethodReference reference) {
            if (BIND.equalsIgnoreCase(reference.getName())
                && reference.getClassReference() instanceof Variable receiver
                && THIS.equals(receiver.getName())) {
                return reference;
            }
            current = reference.getClassReference();
        }

        return null;
    }
}
