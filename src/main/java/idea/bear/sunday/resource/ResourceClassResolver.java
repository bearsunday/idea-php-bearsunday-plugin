package idea.bear.sunday.resource;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.PhpIndex;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import idea.bear.sunday.util.UriUtil;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves a normalized BEAR.Resource URI (e.g. {@code app://self/blog-posting}) to its
 * backing {@link PhpClass}. Tries, in order, the file the URI names, the PHP class index, and
 * finally a filename-index lookup. The two index stages answer for layouts that keep resources
 * off the path their URI spells, and they look only inside the project.
 */
public final class ResourceClassResolver {

    private ResourceClassResolver() {
    }

    /**
     * Answers empty when the URI names no class this project holds. Never triggers a synchronous
     * VFS refresh, which the platform forbids under a read lock on a background thread, so it is
     * safe from inside a {@code ReadAction} off the EDT -- where every caller now runs.
     *
     * <p>While the indexes are still building the question has no answer yet, and
     * {@code IndexNotReadyException} is left to reach the caller rather than being turned into an
     * empty answer: "not here" and "ask again once the index is built" are different things to
     * tell an agent.
     */
    public static Optional<PhpClass> resolveCached(Project project, String normalizedUri) {
        // The authority names which app answers the URI, and this resolver only knows this
        // project's classes: a non-self authority must answer empty, not the local class that
        // happens to share the path.
        if (!isSelfUri(normalizedUri)) {
            return Optional.empty();
        }
        String relPath = UriUtil.toResourceRelativePath(normalizedUri, false);
        if (relPath == null) {
            return Optional.empty();
        }

        // Every stage is a fallback for the one before it: a stage that finds nothing hands over
        // rather than ending the lookup, so a missing file still reaches the index stages.
        Optional<PhpClass> fileClass = resolveFromVirtualFile(project, relPath);
        if (fileClass.isPresent()) {
            return fileClass;
        }

        Optional<PhpClass> indexedClass = resolveFromIndex(project, relPath);
        if (indexedClass.isPresent()) {
            return indexedClass;
        }

        return resolveFromFilenameIndex(project, relPath);
    }

    static @Nullable VirtualFile projectBaseDir(Project project) {
        VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
        if (baseDir != null) {
            return baseDir;
        }

        String basePath = project.getBasePath();
        return basePath == null ? null : LocalFileSystem.getInstance().findFileByNioFile(Path.of(basePath));
    }

    private static Optional<PhpClass> resolveFromVirtualFile(Project project, String relPath) {
        VirtualFile baseDir = projectBaseDir(project);
        VirtualFile targetFile = baseDir == null ? null : baseDir.findFileByRelativePath(relPath);
        if (targetFile == null) {
            return Optional.empty();
        }

        return concreteClassIn(PsiManager.getInstance(project).findFile(targetFile));
    }

    private static Optional<PhpClass> resolveFromFilenameIndex(Project project, String relPath) {
        String className = classNameFromRelPath(relPath);
        if (className == null) {
            return Optional.empty();
        }

        String fileName = className + ".php";
        String expectedSuffix = "/" + relPath;
        PsiManager psiManager = PsiManager.getInstance(project);
        // Project scope only, and never inside `vendor/`: an installed BEAR application carries
        // `src/Resource/App/*.php` of its own, which matches the path suffix by shape alone and
        // would resolve the URI to a dependency's resource.
        for (VirtualFile virtualFile : FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))) {
            String path = virtualFile.getPath().replace('\\', '/');
            if (!path.endsWith(expectedSuffix) || isInVendor(project, virtualFile)) {
                continue;
            }

            Optional<PhpClass> phpClass = concreteClassIn(psiManager.findFile(virtualFile));
            if (phpClass.isPresent()) {
                return phpClass;
            }
        }

        return Optional.empty();
    }

    private static Optional<PhpClass> resolveFromIndex(Project project, String relPath) {
        String className = classNameFromRelPath(relPath);
        if (className == null) {
            return Optional.empty();
        }

        String expectedFqnSuffix = "\\" + relPath
            .replaceFirst("^src/", "")
            .replaceFirst("\\.php$", "")
            .replace('/', '\\');

        return PhpIndex.getInstance(project).getClassesByName(className).stream()
            .filter(phpClass -> phpClass.getFQN().endsWith(expectedFqnSuffix))
            .filter(phpClass -> !isInVendor(project, phpClass))
            .filter(ResourceClassResolver::isConcrete)
            .findFirst();
    }

    /**
     * A dependency's own resources share the {@code \Resource\App\Foo} namespace tail with the
     * project's, so an FQN suffix match alone would resolve a URI to an installed package.
     */
    private static boolean isInVendor(Project project, PhpClass phpClass) {
        PsiFile containingFile = phpClass.getContainingFile();
        if (containingFile == null) {
            return false;
        }

        VirtualFile virtualFile = containingFile.getVirtualFile();
        if (virtualFile == null) {
            return false;
        }

        return isInVendor(project, virtualFile);
    }

    /**
     * Read from the path relative to the project directory rather than from the absolute one: a
     * project that itself sits under a directory named {@code vendor/} would otherwise have every
     * one of its own resources taken for a dependency's.
     */
    private static boolean isInVendor(Project project, VirtualFile file) {
        VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
        if (baseDir == null) {
            return false;
        }

        String relativePath = VfsUtil.getRelativePath(file, baseDir, '/');

        return relativePath != null && ("/" + relativePath).contains("/vendor/");
    }

    /**
     * The concrete class a resource file declares. A file may put an interface, a trait or an
     * enum before it, and none of those answers to a resource URI, so the first class-like node
     * is not necessarily the one.
     */
    private static Optional<PhpClass> concreteClassIn(@Nullable PsiFile psiFile) {
        if (psiFile == null) {
            return Optional.empty();
        }
        for (PhpClass phpClass : PsiTreeUtil.findChildrenOfType(psiFile, PhpClass.class)) {
            if (isConcrete(phpClass)) {
                return Optional.of(phpClass);
            }
        }

        return Optional.empty();
    }

    private static boolean isConcrete(PhpClass phpClass) {
        return !phpClass.isInterface() && !phpClass.isTrait() && !phpClass.isEnum();
    }

    private static boolean isSelfUri(String normalizedUri) {
        return normalizedUri.startsWith("app://self/") || normalizedUri.startsWith("page://self/");
    }

    private static @Nullable String classNameFromRelPath(String relPath) {
        int slash = relPath.lastIndexOf('/');
        String fileName = slash >= 0 ? relPath.substring(slash + 1) : relPath;
        if (!fileName.endsWith(".php")) {
            return null;
        }

        return fileName.substring(0, fileName.length() - 4);
    }
}
