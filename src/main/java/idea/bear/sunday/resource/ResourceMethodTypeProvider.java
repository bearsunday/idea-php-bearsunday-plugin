package idea.bear.sunday.resource;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.PhpIndex;
import com.jetbrains.php.lang.psi.elements.FieldReference;
import com.jetbrains.php.lang.psi.elements.MethodReference;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.PhpExpression;
import com.jetbrains.php.lang.psi.elements.PhpNamedElement;
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression;
import com.jetbrains.php.lang.psi.resolve.types.PhpType;
import com.jetbrains.php.lang.psi.resolve.types.PhpTypeProvider4;
import idea.bear.sunday.util.UriUtil;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class ResourceMethodTypeProvider implements PhpTypeProvider4 {

    private static final char KEY = '\uE142';
    private static final String RESOURCE_INTERFACE_FQN = "\\BEAR\\Resource\\ResourceInterface";
    private static final Set<String> RESOURCE_METHODS = Set.of("get");

    @Override
    public char getKey() {
        return KEY;
    }

    @Override
    public @Nullable PhpType getType(PsiElement element) {
        if (!(element instanceof MethodReference methodReference)) {
            return null;
        }
        String methodName = methodReference.getName();
        if (methodName == null || !RESOURCE_METHODS.contains(methodName.toLowerCase(Locale.ROOT))) {
            return null;
        }
        if (!isResourceAccessor(methodReference)) {
            return null;
        }

        String rawUri = stringArgument(methodReference.getParameter(0));
        if (rawUri == null) {
            return null;
        }

        String normalizedUri = UriUtil.normalizeSupportedResourceUri(rawUri, pageContext(element));
        if (normalizedUri == null || !isSelfUri(normalizedUri)) {
            return null;
        }

        return new PhpType().add(sign(new ResourceRequest(methodName.toLowerCase(Locale.ROOT), normalizedUri)));
    }

    @Override
    public @Nullable PhpType complete(String expression, Project project) {
        return decode(expression)
            .flatMap(request -> resolveResourceClass(project, request.uri()))
            .map(phpClass -> PhpType.from(phpClass.getFQN()))
            .orElse(null);
    }

    @Override
    public Collection<? extends PhpNamedElement> getBySignature(String expression, Set<String> visited, int depth, Project project) {
        return decode(expression)
            .flatMap(request -> resolveResourceClass(project, request.uri()))
            .map(List::of)
            .orElse(null);
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
        if (element == null) {
            return null;
        }
        if (element instanceof StringLiteralExpression stringLiteralExpression) {
            return stringLiteralExpression.getContents();
        }

        StringLiteralExpression stringLiteralExpression = PsiTreeUtil.findChildOfType(element, StringLiteralExpression.class);
        return stringLiteralExpression == null ? null : stringLiteralExpression.getContents();
    }

    private static boolean pageContext(PsiElement element) {
        PsiFile containingFile = element.getContainingFile();
        if (containingFile == null) {
            return false;
        }

        VirtualFile file = containingFile.getVirtualFile();
        VirtualFile baseDir = projectBaseDir(element.getProject());
        if (file == null || baseDir == null) {
            return false;
        }

        String relativePath = VfsUtil.getRelativePath(file, baseDir, '/');
        return relativePath != null && relativePath.startsWith("src/Resource/Page/");
    }

    private static boolean isSelfUri(String normalizedUri) {
        return normalizedUri.startsWith("app://self/") || normalizedUri.startsWith("page://self/");
    }

    private static Optional<PhpClass> resolveResourceClass(Project project, String normalizedUri) {
        VirtualFile baseDir = projectBaseDir(project);
        if (baseDir == null) {
            return Optional.empty();
        }

        String relPath = UriUtil.toResourceRelativePath(normalizedUri, false);
        if (relPath == null) {
            return Optional.empty();
        }

        Optional<PhpClass> nioClass = resolveResourceClassFromNioPath(project, relPath);
        if (nioClass.isPresent()) {
            return nioClass;
        }

        VirtualFile targetFile = baseDir.findFileByRelativePath(relPath);
        if (targetFile == null) {
            return Optional.empty();
        }

        PsiFile psiFile = PsiManager.getInstance(project).findFile(targetFile);
        if (psiFile == null) {
            return Optional.empty();
        }

        PhpClass phpClass = PsiTreeUtil.findChildOfType(psiFile, PhpClass.class);
        if (phpClass != null) {
            return Optional.of(phpClass);
        }

        Optional<PhpClass> indexedClass = resolveResourceClassFromIndex(project, relPath);
        if (indexedClass.isPresent()) {
            return indexedClass;
        }

        return resolveResourceClassFromFilenameIndex(project, relPath);
    }

    private static Optional<PhpClass> resolveResourceClassFromNioPath(Project project, String relPath) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            return Optional.empty();
        }

        VirtualFile targetFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(basePath, relPath));
        if (targetFile == null) {
            return Optional.empty();
        }

        PsiFile psiFile = PsiManager.getInstance(project).findFile(targetFile);
        if (psiFile == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(PsiTreeUtil.findChildOfType(psiFile, PhpClass.class));
    }

    private static Optional<PhpClass> resolveResourceClassFromFilenameIndex(Project project, String relPath) {
        String className = classNameFromRelPath(relPath);
        if (className == null) {
            return Optional.empty();
        }

        String fileName = className + ".php";
        String expectedSuffix = "/" + relPath;
        try {
            for (PsiFile psiFile : FilenameIndex.getFilesByName(project, fileName, GlobalSearchScope.allScope(project))) {
                VirtualFile virtualFile = psiFile.getVirtualFile();
                if (virtualFile == null || !virtualFile.getPath().replace('\\', '/').endsWith(expectedSuffix)) {
                    continue;
                }

                PhpClass phpClass = PsiTreeUtil.findChildOfType(psiFile, PhpClass.class);
                if (phpClass != null) {
                    return Optional.of(phpClass);
                }
            }
        } catch (IndexNotReadyException exception) {
            return Optional.empty();
        }

        return Optional.empty();
    }

    private static Optional<PhpClass> resolveResourceClassFromIndex(Project project, String relPath) {
        String className = classNameFromRelPath(relPath);
        if (className == null) {
            return Optional.empty();
        }

        String expectedFqnSuffix = "\\" + relPath
            .replaceFirst("^src/", "")
            .replaceFirst("\\.php$", "")
            .replace('/', '\\');

        try {
            return PhpIndex.getInstance(project).getClassesByName(className).stream()
                .filter(phpClass -> phpClass.getFQN().endsWith(expectedFqnSuffix))
                .findFirst();
        } catch (IndexNotReadyException exception) {
            return Optional.empty();
        }
    }

    private static @Nullable String classNameFromRelPath(String relPath) {
        int slash = relPath.lastIndexOf('/');
        String fileName = slash >= 0 ? relPath.substring(slash + 1) : relPath;
        if (!fileName.endsWith(".php")) {
            return null;
        }

        return fileName.substring(0, fileName.length() - 4);
    }

    private static @Nullable VirtualFile projectBaseDir(Project project) {
        VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
        if (baseDir != null) {
            return baseDir;
        }

        String basePath = project.getBasePath();
        return basePath == null ? null : LocalFileSystem.getInstance().findFileByNioFile(Path.of(basePath));
    }

    private static String sign(ResourceRequest request) {
        String payload = request.method() + "\n" + request.uri();
        return "#" + KEY + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static Optional<ResourceRequest> decode(String expression) {
        String payload = expression;
        String prefix = "#" + KEY;
        if (payload.startsWith(prefix)) {
            payload = payload.substring(prefix.length());
        }

        try {
            String decoded = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\n", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                return Optional.empty();
            }

            return Optional.of(new ResourceRequest(parts[0], parts[1]));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private record ResourceRequest(String method, String uri) {
    }

}
