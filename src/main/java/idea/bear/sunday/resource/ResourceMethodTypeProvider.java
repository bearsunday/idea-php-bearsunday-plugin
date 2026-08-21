package idea.bear.sunday.resource;

import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.psi.elements.AssignmentExpression;
import com.jetbrains.php.lang.psi.elements.FieldReference;
import com.jetbrains.php.lang.psi.elements.Function;
import com.jetbrains.php.lang.psi.elements.MethodReference;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.PhpExpression;
import com.jetbrains.php.lang.psi.elements.PhpNamedElement;
import com.jetbrains.php.lang.psi.elements.PhpPsiElement;
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression;
import com.jetbrains.php.lang.psi.elements.Variable;
import com.jetbrains.php.lang.psi.resolve.types.PhpType;
import com.jetbrains.php.lang.psi.resolve.types.PhpTypeProvider4;
import idea.bear.sunday.body.BodyTypeCollector;
import idea.bear.sunday.body.BodyTypeDeclaration;
import idea.bear.sunday.util.UriUtil;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class ResourceMethodTypeProvider implements PhpTypeProvider4 {

    private static final char KEY = '\uE142';
    private static final String RESOURCE_INTERFACE_FQN = "\\BEAR\\Resource\\ResourceInterface";
    private static final String SIGNATURE_RESOURCE = "resource";
    private static final String SIGNATURE_BODY = "body";
    private static final Set<String> RESOURCE_METHODS = Set.of("get", "post", "put", "patch", "delete", "head", "options");

    private final BodyTypeCollector bodyTypeCollector = new BodyTypeCollector();

    @Override
    public char getKey() {
        return KEY;
    }

    @Override
    public @Nullable PhpType getType(PsiElement element) {
        if (element instanceof FieldReference fieldReference) {
            return getResourceBodyType(fieldReference);
        }
        if (element instanceof MethodReference methodReference) {
            return getResourceMethodType(methodReference);
        }

        return null;
    }

    @Override
    public @Nullable PhpType complete(String expression, Project project) {
        return decode(expression)
            .map(request -> completeRequest(request, project))
            .orElse(null);
    }

    @Override
    public Collection<? extends PhpNamedElement> getBySignature(String expression, Set<String> visited, int depth, Project project) {
        Optional<SignedResourceRequest> decodedRequest = decode(expression);
        if (decodedRequest.isEmpty()) {
            return null;
        }
        if (SIGNATURE_BODY.equals(decodedRequest.get().kind())) {
            return List.of();
        }

        return Optional.of(decodedRequest.get())
            .flatMap(request -> resolveQuietly(project, request.uri()))
            .map(List::of)
            .orElse(null);
    }

    private @Nullable PhpType getResourceMethodType(MethodReference methodReference) {
        return resourceRequest(methodReference, methodReference)
            .map(request -> new PhpType().add(sign(SIGNATURE_RESOURCE, request)))
            .orElse(null);
    }

    private @Nullable PhpType getResourceBodyType(FieldReference fieldReference) {
        if (!"body".equals(fieldReference.getName())) {
            return null;
        }

        return resourceRequestFromBodyField(fieldReference)
            .map(request -> new PhpType().add(sign(SIGNATURE_BODY, request)))
            .orElse(null);
    }

    private @Nullable PhpType completeRequest(SignedResourceRequest request, Project project) {
        if (SIGNATURE_RESOURCE.equals(request.kind())) {
            return resolveQuietly(project, request.uri())
                .map(phpClass -> PhpType.from(phpClass.getFQN()))
                .orElse(null);
        }
        if (SIGNATURE_BODY.equals(request.kind())) {
            return resolveBodyType(project, request)
                .map(declaration -> PhpType.from(declaration.bodyType().render(), PhpType._NULL))
                .orElse(null);
        }

        return null;
    }

    private Optional<BodyTypeDeclaration> resolveBodyType(Project project, SignedResourceRequest request) {
        return resolveQuietly(project, request.uri())
            .flatMap(bodyTypeCollector::collect)
            .flatMap(collection -> collection.declarationForResourceMethod(request.method()));
    }

    /**
     * Type inference is an offer to the editor, not a report to an agent: while the indexes are
     * building there is no type to offer, so the exception the resolver raises for a question it
     * cannot answer yet becomes "nothing" here.
     */
    private static Optional<PhpClass> resolveQuietly(Project project, String uri) {
        try {
            return ResourceClassResolver.resolveCached(project, uri);
        } catch (IndexNotReadyException exception) {
            return Optional.empty();
        }
    }

    private static Optional<ResourceRequest> resourceRequestFromBodyField(FieldReference fieldReference) {
        PhpExpression classReference = fieldReference.getClassReference();
        if (classReference instanceof MethodReference methodReference) {
            return resourceRequest(methodReference, fieldReference);
        }
        if (classReference instanceof Variable variable) {
            return resourceRequestFromLocalVariable(variable, fieldReference);
        }

        return Optional.empty();
    }

    private static Optional<ResourceRequest> resourceRequestFromLocalVariable(Variable variable, FieldReference fieldReference) {
        String variableName = variable.getName();
        if (variableName == null || variableName.isBlank() || Variable.THIS.equals(variableName)) {
            return Optional.empty();
        }

        Function function = PsiTreeUtil.getParentOfType(fieldReference, Function.class);
        if (function == null) {
            return Optional.empty();
        }

        int fieldOffset = fieldReference.getTextRange().getStartOffset();
        // Consider only the most recent assignment: a later reassignment (e.g. `$x = null;`)
        // must invalidate an earlier `$x = $this->resource->get(...)` rather than being skipped.
        return PsiTreeUtil.findChildrenOfType(function, AssignmentExpression.class).stream()
            .filter(assignment -> assignment.getTextRange().getStartOffset() < fieldOffset)
            .filter(assignment -> PsiTreeUtil.getParentOfType(assignment, Function.class) == function)
            .filter(assignment -> assignsToVariable(assignment, variableName))
            .max(Comparator.comparingInt(assignment -> assignment.getTextRange().getStartOffset()))
            .map(AssignmentExpression::getValue)
            .filter(MethodReference.class::isInstance)
            .map(MethodReference.class::cast)
            .flatMap(methodReference -> resourceRequest(methodReference, fieldReference));
    }

    private static boolean assignsToVariable(AssignmentExpression assignment, String variableName) {
        PhpPsiElement assigned = assignment.getVariable();
        return assigned instanceof Variable assignedVariable && variableName.equals(assignedVariable.getName());
    }

    private static Optional<ResourceRequest> resourceRequest(MethodReference methodReference, PsiElement context) {
        String methodName = methodReference.getName();
        if (methodName == null) {
            return Optional.empty();
        }

        methodName = methodName.toLowerCase(Locale.ROOT);
        if (!RESOURCE_METHODS.contains(methodName) || !isResourceAccessor(methodReference)) {
            return Optional.empty();
        }

        String rawUri = stringArgument(methodReference.getParameter(0));
        if (rawUri == null) {
            return Optional.empty();
        }

        String normalizedUri = UriUtil.normalizeSupportedResourceUri(rawUri, pageContext(context));
        if (normalizedUri == null || !isSelfUri(normalizedUri)) {
            return Optional.empty();
        }

        return Optional.of(new ResourceRequest(methodName, normalizedUri));
    }

    private static boolean isResourceAccessor(MethodReference methodReference) {
        PhpExpression classReference = methodReference.getClassReference();
        if (classReference == null) {
            return false;
        }
        if (classReference instanceof FieldReference fieldReference && "resource".equals(fieldReference.getName())) {
            return true;
        }
        if (classReference.getText().endsWith("->resource")) {
            return true;
        }

        return classReference.getType().getTypes().contains(RESOURCE_INTERFACE_FQN);
    }

    private static @Nullable String stringArgument(@Nullable PsiElement element) {
        // Only a direct string literal is a reliable URI. Descendant literals would wrongly match
        // dynamic arguments such as `$prefix . '/user'`.
        if (element instanceof StringLiteralExpression stringLiteralExpression) {
            return stringLiteralExpression.getContents();
        }

        return null;
    }

    private static boolean pageContext(PsiElement element) {
        PsiFile containingFile = element.getContainingFile();
        if (containingFile == null) {
            return false;
        }

        VirtualFile file = containingFile.getVirtualFile();
        VirtualFile baseDir = ResourceClassResolver.projectBaseDir(element.getProject());
        if (file == null || baseDir == null) {
            return false;
        }

        String relativePath = VfsUtil.getRelativePath(file, baseDir, '/');
        return relativePath != null && relativePath.startsWith("src/Resource/Page/");
    }

    private static boolean isSelfUri(String normalizedUri) {
        return normalizedUri.startsWith("app://self/") || normalizedUri.startsWith("page://self/");
    }

    private static String sign(String kind, ResourceRequest request) {
        String payload = kind + "\n" + request.method() + "\n" + request.uri();
        return "#" + KEY + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static Optional<SignedResourceRequest> decode(String expression) {
        String payload = expression;
        String prefix = "#" + KEY;
        if (payload.startsWith(prefix)) {
            payload = payload.substring(prefix.length());
        }

        try {
            String decoded = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\n", 3);
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                return Optional.of(new SignedResourceRequest(SIGNATURE_RESOURCE, parts[0], parts[1]));
            }
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                return Optional.empty();
            }
            if (!SIGNATURE_RESOURCE.equals(parts[0]) && !SIGNATURE_BODY.equals(parts[0])) {
                return Optional.empty();
            }

            return Optional.of(new SignedResourceRequest(parts[0], parts[1], parts[2]));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private record ResourceRequest(String method, String uri) {
    }

    private record SignedResourceRequest(String kind, String method, String uri) {
    }

}
