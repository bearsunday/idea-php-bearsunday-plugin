package idea.bear.sunday.body;

import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.psi.elements.ArrayAccessExpression;
import com.jetbrains.php.lang.psi.elements.ArrayIndex;
import com.jetbrains.php.lang.psi.elements.AssignmentExpression;
import com.jetbrains.php.lang.psi.elements.FieldReference;
import com.jetbrains.php.lang.psi.elements.Function;
import com.jetbrains.php.lang.psi.elements.MemberReference;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.PhpExpression;
import com.jetbrains.php.lang.psi.elements.PhpPsiElement;
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression;
import com.jetbrains.php.lang.psi.elements.Variable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
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

        Map<Method, MethodBodyState> bodyTypesByMethod = new LinkedHashMap<>();
        Collection<AssignmentExpression> assignmentElements = PsiTreeUtil.findChildrenOfType(phpClass, AssignmentExpression.class);
        List<AssignmentExpression> assignments = assignmentElements.stream()
            .sorted(Comparator.comparingInt(assignment -> assignment.getTextRange().getStartOffset()))
            .toList();
        for (AssignmentExpression assignment : assignments) {
            Optional<Method> method = directMethodOf(assignment, phpClass);
            if (method.isEmpty()) {
                continue;
            }
            MethodBodyState state = bodyTypesByMethod.computeIfAbsent(method.get(), ignored -> new MethodBodyState());
            if (trackLocalVariableAssignment(assignment, state)) {
                continue;
            }

            PhpPsiElement value = assignment.getValue();
            if (!(value instanceof PhpExpression expression)) {
                continue;
            }

            if (isThisBodyAssignment(assignment)) {
                state.bodyTypes.add(inferer.infer(expression, state.localTypes));
                continue;
            }

            Optional<String> bodyOffsetKey = thisBodyOffsetKey(assignment);
            if (bodyOffsetKey.isPresent()) {
                state.addBodyField(new ShapeField(
                    bodyOffsetKey.get(),
                    inferer.infer(expression, state.localTypes)
                ));
            }
        }

        bodyTypesByMethod.entrySet().removeIf(entry -> entry.getValue().bodyTypes.isEmpty());
        if (bodyTypesByMethod.isEmpty()) {
            return Optional.empty();
        }

        List<BodyTypeDeclaration> declarations = new ArrayList<>();
        for (Map.Entry<Method, MethodBodyState> entry : bodyTypesByMethod.entrySet()) {
            declarations.add(new BodyTypeDeclaration(
                BodyTypeName.fromClassAndMethod(phpClass, entry.getKey()),
                BodyTypes.union(entry.getValue().bodyTypes)
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
        return isThisBodyReference(variable);
    }

    private boolean isThisBodyReference(PhpPsiElement variable) {
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

    private boolean trackLocalVariableAssignment(AssignmentExpression assignment, MethodBodyState state) {
        Optional<String> variableName = localVariableName(assignment);
        if (variableName.isEmpty()) {
            return false;
        }
        PhpPsiElement value = assignment.getValue();
        if (!(value instanceof PhpExpression expression)) {
            return true;
        }

        state.localTypes.put(variableName.get(), inferer.infer(expression, state.localTypes));

        return true;
    }

    private Optional<String> localVariableName(AssignmentExpression assignment) {
        PhpPsiElement variable = assignment.getVariable();
        if (!(variable instanceof Variable localVariable)) {
            return Optional.empty();
        }
        String name = localVariable.getName();
        if (Variable.THIS.equals(name) || Variable.$THIS.equals(localVariable.getText())) {
            return Optional.empty();
        }
        if (name == null || name.isBlank()) {
            return Optional.of(localVariable.getText().replaceFirst("^\\$", ""));
        }

        return Optional.of(name);
    }

    private Optional<String> thisBodyOffsetKey(AssignmentExpression assignment) {
        PhpPsiElement variable = assignment.getVariable();
        if (!(variable instanceof ArrayAccessExpression arrayAccessExpression)) {
            return Optional.empty();
        }
        if (!isThisBodyReference(arrayAccessExpression.getValue())) {
            return Optional.empty();
        }

        ArrayIndex index = arrayAccessExpression.getIndex();
        if (index == null) {
            return Optional.empty();
        }

        return literalKey(index.getValue());
    }

    private Optional<String> literalKey(PhpPsiElement keyElement) {
        if (keyElement instanceof StringLiteralExpression stringLiteralExpression) {
            return Optional.of(stringLiteralExpression.getContents());
        }
        if (keyElement == null) {
            return Optional.empty();
        }

        String text = keyElement.getText().trim();
        if (text.matches("[+-]?[0-9][0-9_]*")) {
            return Optional.of(text.replace("_", "").replaceFirst("^\\+", ""));
        }

        return Optional.empty();
    }

    private static final class MethodBodyState {

        private final Map<String, BodyType> localTypes = new HashMap<>();
        private final List<BodyType> bodyTypes = new ArrayList<>();

        private void addBodyField(ShapeField field) {
            if (bodyTypes.isEmpty()) {
                bodyTypes.add(BodyTypes.shape(List.of(field)));
                return;
            }

            int lastIndex = bodyTypes.size() - 1;
            bodyTypes.set(lastIndex, BodyTypes.withShapeField(bodyTypes.get(lastIndex), field));
        }

    }

}
