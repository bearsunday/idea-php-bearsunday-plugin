package idea.bear.sunday.mcp.facts;

import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.PhpModifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The members of a PHP class as PHP itself hands them out, rather than as the PSI declares them.
 *
 * <p>Two libraries in this stack ask the same question of a class and get the same answer, because
 * both go through {@code get_class_methods()}: {@code Ray\Aop\Bind} picks the methods it can weave,
 * and {@code Ray\Di\AnnotatedClass} picks the ones it can inject through. So the rule lives once --
 * public methods, {@code __construct} aside, the inherited ones included and the ones a trait
 * brings in with them, which PHP treats as declared by the class that uses the trait.
 */
final class PhpMembers {

    /** Deep enough for any real hierarchy, and a stop for one that refers back to itself. */
    private static final int MAX_HIERARCHY = 30;

    private static final String CONSTRUCT = "__construct";

    private PhpMembers() {
    }

    /**
     * Every public method of a class and of what it inherits or uses, {@code __construct} aside,
     * with the nearest declaration of a name winning -- which is the one PHP dispatches to.
     */
    static List<Method> publicMethods(PhpClass target) {
        List<Method> methods = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        PhpClass current = target;
        for (int depth = 0; current != null && depth < MAX_HIERARCHY; depth++) {
            // The class's own first, then the traits it uses: a method declared in both is the
            // class's own, and `seen` keeps the first of a name.
            collect(current, seen, methods);
            for (PhpClass trait : traitsOf(current)) {
                collect(trait, seen, methods);
            }
            current = current.getSuperClass();
        }

        return methods;
    }

    /**
     * The constructor a class is built through, its own or an inherited one, or {@code null} for a
     * class that declares none anywhere -- which Ray.Di builds with no arguments at all.
     */
    static Method constructorOf(PhpClass target) {
        PhpClass current = target;
        for (int depth = 0; current != null && depth < MAX_HIERARCHY; depth++) {
            Method constructor = current.findOwnMethodByName(CONSTRUCT);
            if (constructor != null) {
                return constructor;
            }
            for (PhpClass trait : traitsOf(current)) {
                Method fromTrait = trait.findOwnMethodByName(CONSTRUCT);
                if (fromTrait != null) {
                    return fromTrait;
                }
            }
            current = current.getSuperClass();
        }

        return null;
    }

    /** The traits a class uses, and the traits those use, which PHP flattens into the class alike. */
    static List<PhpClass> traitsOf(PhpClass phpClass) {
        List<PhpClass> traits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<PhpClass> queue = new ArrayList<>(List.of(phpClass));
        while (!queue.isEmpty() && seen.size() < MAX_HIERARCHY) {
            for (PhpClass trait : queue.remove(0).getTraits()) {
                String fqn = trait.getFQN();
                if (fqn != null && seen.add(fqn.toLowerCase(Locale.ROOT))) {
                    traits.add(trait);
                    queue.add(trait);
                }
            }
        }

        return traits;
    }

    private static void collect(PhpClass declaring, Set<String> seen, List<Method> methods) {
        for (Method method : declaring.getOwnMethods()) {
            String name = method.getName();
            if (method.getModifier().getAccess() != PhpModifier.Access.PUBLIC
                || CONSTRUCT.equalsIgnoreCase(name)
                // PHP compares method names case-insensitively, so two spellings are one method.
                || !seen.add(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            methods.add(method);
        }
    }
}
