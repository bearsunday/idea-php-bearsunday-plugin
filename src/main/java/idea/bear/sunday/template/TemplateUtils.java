package idea.bear.sunday.template;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class TemplateUtils {

    static final String RESOURCE_DIR_SEGMENT = "/src/Resource/";

    private TemplateUtils() {
    }

    @NotNull
    static String trimSlash(@NotNull String path) {
        String s = path.startsWith("/") ? path.substring(1) : path;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** App root = directory containing src/Resource/, derived from the class's file path. */
    @Nullable
    static String appRootOf(@NotNull PhpClass resourceClass) {
        VirtualFile classFile = virtualFileOf(resourceClass);
        if (classFile == null) {
            return null;
        }
        String classPath = classFile.getPath();
        int idx = classPath.lastIndexOf(RESOURCE_DIR_SEGMENT);
        return idx < 0 ? null : classPath.substring(0, idx);
    }

    /** Relative path under src/Resource/ (e.g., "Page/Index.php"), or null. */
    @Nullable
    static String relativeFromResource(@NotNull PhpClass resourceClass) {
        VirtualFile classFile = virtualFileOf(resourceClass);
        if (classFile == null) {
            return null;
        }
        String classPath = classFile.getPath();
        int idx = classPath.lastIndexOf(RESOURCE_DIR_SEGMENT);
        if (idx < 0) {
            return null;
        }
        return classPath.substring(idx + RESOURCE_DIR_SEGMENT.length());
    }

    @Nullable
    static PhpClass findClassByAbsolutePath(@NotNull Project project, @NotNull String absolutePath) {
        VirtualFile classFile = LocalFileSystem.getInstance().findFileByPath(absolutePath);
        if (classFile == null) {
            return null;
        }
        PsiFile psiFile = PsiManager.getInstance(project).findFile(classFile);
        if (psiFile == null) {
            return null;
        }
        return PsiTreeUtil.findChildOfType(psiFile, PhpClass.class);
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
