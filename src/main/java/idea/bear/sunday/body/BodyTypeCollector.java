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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class BodyTypeCollector {

    private static final String RESOURCE_OBJECT_FQN = "\\BEAR\\Resource\\ResourceObject";
    private final BodyTypeInferer inferer = new BodyTypeInferer();

    public Optional<BodyTypeCollection> collect(PhpClass phpClass) {
        if (!isResourceObject(phpClass)) {
            return Optional.empty();
        }

        Map<Method, List<BodyType>> bodyTypesByMethod = new LinkedHashMap<>();
        Collection<AssignmentExpression> assignmentElements = PsiTreeUtil.findChildrenOfType(phpClass, AssignmentExpression.class);
        List<AssignmentExpression> assignments = assignmentElements.stream()
            .sorted(Comparator.comparingInt(assignment -> assignment.getTextRange().getStartOffset()))
            .toList();
        for (AssignmentExpression assignment : assignments) {
            Optional<Method> method = directMethodOf(assignment, phpClass);
            if (method.isEmpty()) {
                continue;
            }
            if (!isThisBodyAssignment(assignment)) {
                continue;
            }

            PhpPsiElement value = assignment.getValue();
            if (value instanceof PhpExpression expression) {
                bodyTypesByMethod.computeIfAbsent(method.get(), ignored -> new ArrayList<>())
                    .add(inferer.infer(expression));
            }
        }

        if (bodyTypesByMethod.isEmpty()) {
            return Optional.empty();
        }

        List<BodyTypeDeclaration> declarations = new ArrayList<>();
        for (Map.Entry<Method, List<BodyType>> entry : bodyTypesByMethod.entrySet()) {
            declarations.add(new BodyTypeDeclaration(
                BodyTypeName.fromClassAndMethod(phpClass, entry.getKey()),
                BodyTypes.union(entry.getValue())
            ));
        }

        return Optional.of(new BodyTypeCollection(declarations));
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

    private Optional<Method> directMethodOf(AssignmentExpression assignment, PhpClass phpClass) {
        Function function = PsiTreeUtil.getParentOfType(assignment, Function.class);
        if (!(function instanceof Method method)) {
            return Optional.empty();
        }

        PhpClass containingClass = method.getContainingClass();
        if (!phpClass.equals(containingClass)) {
            return Optional.empty();
        }

        return Optional.of(method);
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
