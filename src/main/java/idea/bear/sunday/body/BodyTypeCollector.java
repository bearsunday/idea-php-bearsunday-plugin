package idea.bear.sunday.body;

import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.psi.elements.AssignmentExpression;
import com.jetbrains.php.lang.psi.elements.FieldReference;
import com.jetbrains.php.lang.psi.elements.Function;
import com.jetbrains.php.lang.psi.elements.MemberReference;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.PhpExpression;
import com.jetbrains.php.lang.psi.elements.PhpPsiElement;
import com.jetbrains.php.lang.psi.elements.Variable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class BodyTypeCollector {

    private static final String RESOURCE_OBJECT_FQN = "\\BEAR\\Resource\\ResourceObject";
    private final BodyTypeInferer inferer = new BodyTypeInferer();

    public Optional<BodyType> collect(PhpClass phpClass) {
        if (!isResourceObject(phpClass)) {
            return Optional.empty();
        }

        List<BodyType> bodyTypes = new ArrayList<>();
        Collection<AssignmentExpression> assignments = PsiTreeUtil.findChildrenOfType(phpClass, AssignmentExpression.class);
        for (AssignmentExpression assignment : assignments) {
            if (!isDirectMethodAssignment(assignment, phpClass)) {
                continue;
            }
            if (!isThisBodyAssignment(assignment)) {
                continue;
            }

            PhpPsiElement value = assignment.getValue();
            if (value instanceof PhpExpression expression) {
                bodyTypes.add(inferer.infer(expression));
            }
        }

        if (bodyTypes.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(BodyTypes.union(bodyTypes));
    }

    public boolean isResourceObject(PhpClass phpClass) {
        String fqn = phpClass.getFQN();
        if (RESOURCE_OBJECT_FQN.equals(fqn)) {
            return true;
        }

        String superFqn = phpClass.getSuperFQN();
        if (RESOURCE_OBJECT_FQN.equals(superFqn)) {
            return true;
        }

        PhpClass superClass = phpClass.getSuperClass();
        return superClass != null && isResourceObject(superClass);
    }

    private boolean isDirectMethodAssignment(AssignmentExpression assignment, PhpClass phpClass) {
        Function function = PsiTreeUtil.getParentOfType(assignment, Function.class);
        if (!(function instanceof Method method)) {
            return false;
        }

        PhpClass containingClass = method.getContainingClass();
        return phpClass.equals(containingClass);
    }

    private boolean isThisBodyAssignment(AssignmentExpression assignment) {
        PhpPsiElement variable = assignment.getVariable();
        if (!(variable instanceof FieldReference fieldReference)) {
            return false;
        }
        if (!"body".equals(fieldReference.getName())) {
            return false;
        }

        PhpExpression classReference = ((MemberReference) fieldReference).getClassReference();
        if (classReference instanceof Variable variableReference) {
            return Variable.THIS.equals(variableReference.getName());
        }

        return classReference != null && Variable.$THIS.equals(classReference.getText());
    }

}
