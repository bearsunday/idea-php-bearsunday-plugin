package idea.bear.sunday.util;

import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public final class VirtualFiles {

    private VirtualFiles() {
    }

    /**
     * Whether a file lies under a directory. Callers with a narrower boundary than the project
     * -- the configured schema directories, say -- ask here too, so containment means the same
     * thing everywhere. Both sides are compared as the paths they finally name, so a symbolic
     * link is judged by where it points, and a project that itself lives behind one
     * (/tmp -> /private/tmp on macOS) is not mistaken for somewhere else.
     */
    public static boolean isInside(@NotNull VirtualFile directory, @NotNull VirtualFile file) {
        return VfsUtilCore.isAncestor(canonical(directory), canonical(file), false);
    }

    private static VirtualFile canonical(VirtualFile file) {
        VirtualFile canonical = file.getCanonicalFile();

        return canonical == null ? file : canonical;
    }
}
