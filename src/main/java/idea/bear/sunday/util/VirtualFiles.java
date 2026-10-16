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
     * (/tmp -> /private/tmp on macOS) is not mistaken for somewhere else. A link whose target
     * the VFS does not hold cannot be placed, and is not inside.
     */
    public static boolean isInside(@NotNull VirtualFile directory, @NotNull VirtualFile file) {
        VirtualFile canonicalDirectory = directory.getCanonicalFile();
        VirtualFile canonicalFile = file.getCanonicalFile();
        if (canonicalDirectory == null || canonicalFile == null) {
            return false;
        }

        return VfsUtilCore.isAncestor(canonicalDirectory, canonicalFile, false);
    }
}
