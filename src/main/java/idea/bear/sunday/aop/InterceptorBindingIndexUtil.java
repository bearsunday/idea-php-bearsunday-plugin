package idea.bear.sunday.aop;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression;
import com.jetbrains.php.lang.psi.elements.ClassConstantReference;
import com.jetbrains.php.lang.psi.elements.ClassReference;
import com.jetbrains.php.lang.psi.elements.MethodReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts Ray.Aop interceptor bindings from a PHP module file.
 *
 * <p>A binding such as
 * <pre>
 * $this-&gt;bindInterceptor(
 *     $this-&gt;matcher-&gt;any(),
 *     $this-&gt;matcher-&gt;annotatedWith(Transactional::class),
 *     [TransactionalInterceptor::class]
 * );
 * </pre>
 * is indexed as {@code \Transactional -> [\TransactionalInterceptor]} so that clicking the
 * {@code #[Transactional]} attribute can jump to the bound interceptor(s).
 */
public final class InterceptorBindingIndexUtil {

    private static final String BIND_INTERCEPTOR = "bindInterceptor";
    private static final String ANNOTATED_WITH = "annotatedWith";

    private InterceptorBindingIndexUtil() {
    }

    public static Map<String, List<String>> index(PsiFile psiFile) {
        Map<String, List<String>> result = new HashMap<>();

        for (MethodReference call : PsiTreeUtil.findChildrenOfType(psiFile, MethodReference.class)) {
            // PHP method names are case-insensitive, so match accordingly.
            if (!BIND_INTERCEPTOR.equalsIgnoreCase(call.getName())) {
                continue;
            }

            List<String> annotations = collectAnnotationFqns(call);
            if (annotations.isEmpty()) {
                continue;
            }

            List<String> interceptors = collectInterceptorFqns(call);
            if (interceptors.isEmpty()) {
                continue;
            }

            for (String annotation : annotations) {
                List<String> bound = result.computeIfAbsent(annotation, key -> new ArrayList<>());
                for (String interceptor : interceptors) {
                    if (!bound.contains(interceptor)) {
                        bound.add(interceptor);
                    }
                }
            }
        }

        return result;
    }

    private static List<String> collectAnnotationFqns(MethodReference bindInterceptorCall) {
        List<String> result = new ArrayList<>();

        for (MethodReference matcher : PsiTreeUtil.findChildrenOfType(bindInterceptorCall, MethodReference.class)) {
            if (!ANNOTATED_WITH.equalsIgnoreCase(matcher.getName())) {
                continue;
            }

            PsiElement[] parameters = matcher.getParameters();
            if (parameters.length == 0) {
                continue;
            }

            String fqn = classConstFqn(parameters[0]);
            if (fqn != null && !result.contains(fqn)) {
                result.add(fqn);
            }
        }

        return result;
    }

    private static List<String> collectInterceptorFqns(MethodReference bindInterceptorCall) {
        List<String> result = new ArrayList<>();

        for (PsiElement parameter : bindInterceptorCall.getParameters()) {
            if (!(parameter instanceof ArrayCreationExpression array)) {
                continue;
            }

            for (ClassConstantReference reference : PsiTreeUtil.findChildrenOfType(array, ClassConstantReference.class)) {
                String fqn = classConstFqn(reference);
                if (fqn != null && !result.contains(fqn)) {
                    result.add(fqn);
                }
            }
        }

        return result;
    }

    /**
     * Returns the normalized FQN referenced by a {@code Foo::class} expression, or {@code null}.
     */
    private static String classConstFqn(PsiElement element) {
        if (!(element instanceof ClassConstantReference reference)) {
            return null;
        }

        PsiElement classReference = reference.getClassReference();
        if (classReference instanceof ClassReference resolved) {
            return normalizeFqn(resolved.getFQN());
        }

        return null;
    }

    /**
     * Normalizes a PHP FQN to a single leading backslash form (e.g. {@code \App\Foo}).
     */
    public static String normalizeFqn(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return null;
        }

        return fqn.startsWith("\\") ? fqn : "\\" + fqn;
    }
}
