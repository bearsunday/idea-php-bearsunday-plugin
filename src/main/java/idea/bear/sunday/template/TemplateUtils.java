package idea.bear.sunday.template;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;

final class TemplateUtils {

    static final String RESOURCE_DIR_SEGMENT = "/src/Resource/";

    private TemplateUtils() {
    }

    /** App root = directory containing src/Resource/, derived from a class file path. */
    @Nullable
    static String appRootOfPath(@NotNull String classPath) {
        int idx = classPath.lastIndexOf(RESOURCE_DIR_SEGMENT);
        return idx < 0 ? null : classPath.substring(0, idx);
    }

    /** Relative path under src/Resource/ (e.g., "Page/Index.php"), or null. */
    @Nullable
    static String relativeFromResourcePath(@NotNull String classPath) {
        int idx = classPath.lastIndexOf(RESOURCE_DIR_SEGMENT);
        return idx < 0 ? null : classPath.substring(idx + RESOURCE_DIR_SEGMENT.length());
    }

    /**
     * True when {@code candidatePath} lives under {@code appRoot} and ends with the relative path
     * {@code rel}. The leading slash on {@code rel} keeps "SubPage/Index" from matching "Page/Index".
     */
    static boolean isUnderAppRootWithRel(@NotNull String candidatePath,
                                         @NotNull String appRoot,
                                         @NotNull String rel) {
        return candidatePath.startsWith(appRoot + "/") && candidatePath.endsWith("/" + rel);
    }

    /** Last path segment (the file name) of a relative or absolute path. */
    @NotNull
    static String fileNameOf(@NotNull String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    @Nullable
    static String appRootOf(@NotNull PhpClass resourceClass) {
        VirtualFile classFile = virtualFileOf(resourceClass);
        return classFile == null ? null : appRootOfPath(classFile.getPath());
    }

    @Nullable
    static String relativeFromResource(@NotNull PhpClass resourceClass) {
        VirtualFile classFile = virtualFileOf(resourceClass);
        return classFile == null ? null : relativeFromResourcePath(classFile.getPath());
    }

    /**
     * Project files (libraries excluded) with the given file name, looked up via the name index.
     * Returns empty while indexing (dumb mode): the name index is unavailable then and querying it
     * would throw IndexNotReadyException. Callers run from line markers / goto / reference resolution,
     * which the daemon re-runs once indexing finishes, so navigation simply re-resolves.
     */
    @NotNull
    static Collection<VirtualFile> filesNamed(@NotNull Project project, @NotNull String fileName) {
        if (DumbService.isDumb(project)) {
            return Collections.emptyList();
        }
        return FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project));
    }

    @Nullable
    static PhpClass findClass(@NotNull Project project, @NotNull VirtualFile file) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile == null) {
            return null;
        }
        return PsiTreeUtil.findChildOfType(psiFile, PhpClass.class);
    }

    @Nullable
    static PhpClass findClassByAbsolutePath(@NotNull Project project, @NotNull String absolutePath) {
        VirtualFile classFile = LocalFileSystem.getInstance().findFileByPath(absolutePath);
        return classFile == null ? null : findClass(project, classFile);
    }

    @Nullable
    private static VirtualFile virtualFileOf(@NotNull PhpClass resourceClass) {
        PsiFile containingFile = resourceClass.getContainingFile();
        if (containingFile == null) {
            return null;
        }
        return containingFile.getVirtualFile();
    }
}
