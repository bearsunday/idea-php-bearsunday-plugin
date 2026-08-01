package idea.bear.sunday.resource;

import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
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
 * backing {@link PhpClass}. Tries, in order, an on-disk NIO lookup, the containing VirtualFile,
 * the PHP class index, and finally a filename-index fallback for cases the index has not yet
 * caught up to a freshly created file.
 */
public final class ResourceClassResolver {

    private ResourceClassResolver() {
    }

    public static Optional<PhpClass> resolve(Project project, String normalizedUri) {
        return resolve(project, normalizedUri, true);
    }

    /**
     * Same as {@link #resolve(Project, String)} but never triggers a synchronous VFS refresh,
     * which the platform forbids under a read lock on a background thread. Callers that run
     * inside {@code ReadAction} off the EDT (e.g. MCP tools) must use this variant.
     */
    public static Optional<PhpClass> resolveCached(Project project, String normalizedUri) {
        return resolve(project, normalizedUri, false);
    }

    private static Optional<PhpClass> resolve(Project project, String normalizedUri, boolean refresh) {
        VirtualFile baseDir = projectBaseDir(project);
        if (baseDir == null) {
            return Optional.empty();
        }

        String relPath = UriUtil.toResourceRelativePath(normalizedUri, false);
        if (relPath == null) {
            return Optional.empty();
        }

        if (refresh) {
            Optional<PhpClass> nioClass = resolveFromNioPath(project, relPath);
            if (nioClass.isPresent()) {
                return nioClass;
            }
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

        Optional<PhpClass> indexedClass = resolveFromIndex(project, relPath);
        if (indexedClass.isPresent()) {
            return indexedClass;
        }

        return resolveFromFilenameIndex(project, relPath);
    }

    /**
     * Resolves a normalized BEAR.Resource URI to its resource class file path, relative to the
     * project root, without touching the PSI/index layers.
     */
    public static @Nullable String toRelativePath(String normalizedUri) {
        return UriUtil.toResourceRelativePath(normalizedUri, false);
    }

    static @Nullable VirtualFile projectBaseDir(Project project) {
        VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
        if (baseDir != null) {
            return baseDir;
        }

        String basePath = project.getBasePath();
        return basePath == null ? null : LocalFileSystem.getInstance().findFileByNioFile(Path.of(basePath));
    }

    private static Optional<PhpClass> resolveFromNioPath(Project project, String relPath) {
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

    private static Optional<PhpClass> resolveFromFilenameIndex(Project project, String relPath) {
        String className = classNameFromRelPath(relPath);
        if (className == null) {
            return Optional.empty();
        }

        String fileName = className + ".php";
        String expectedSuffix = "/" + relPath;
        try {
            PsiManager psiManager = PsiManager.getInstance(project);
            for (VirtualFile virtualFile : FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.allScope(project))) {
                if (!virtualFile.getPath().replace('\\', '/').endsWith(expectedSuffix)) {
                    continue;
                }

                PsiFile psiFile = psiManager.findFile(virtualFile);
                if (psiFile == null) {
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

    private static Optional<PhpClass> resolveFromIndex(Project project, String relPath) {
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
}
