package idea.bear.sunday.mcp.facts;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * File access shared by the fact services: project-relative paths and reads that see unsaved
 * editor changes.
 */
final class FactsFiles {

    private static final String PHP_EXTENSION = "php";

    private FactsFiles() {
    }

    /**
     * A scan root as a project-relative path, or {@code null} when it may not be read. An answer
     * built by walking the file tree must stay inside the project, so a root that escapes it, or
     * that walks back up to it, is refused rather than read: {@code "."} resolves to the project
     * itself and would parse every PHP file in it, vendor directories included.
     */
    @Nullable
    static String normalizeRoot(@Nullable String root, String defaultRoot) {
        if (root == null || root.isBlank()) {
            return defaultRoot;
        }
        String trimmed = root.trim().replace('\\', '/');
        if (trimmed.startsWith("/")) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        for (String segment : trimmed.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (".".equals(segment) || "..".equals(segment)) {
                return null;
            }
            segments.add(segment);
        }

        return segments.isEmpty() ? null : String.join("/", segments);
    }

    /**
     * The PHP files under a directory, walked rather than looked up so a building index cannot
     * empty the answer. Symbolic links are not followed: a link out of the project would put
     * another project's classes in this project's answer.
     */
    static List<VirtualFile> phpFilesUnder(VirtualFile rootDir) {
        List<VirtualFile> files = new ArrayList<>();
        VfsUtilCore.visitChildrenRecursively(rootDir, new VirtualFileVisitor<Void>(VirtualFileVisitor.NO_FOLLOW_SYMLINKS) {
            @Override
            public boolean visitFile(@NotNull VirtualFile file) {
                ProgressManager.checkCanceled();
                if (!file.isDirectory() && PHP_EXTENSION.equalsIgnoreCase(file.getExtension())) {
                    files.add(file);
                }

                return true;
            }
        });
        files.sort(Comparator.comparing(VirtualFile::getPath));

        return files;
    }

    @Nullable
    static VirtualFile baseDir(Project project) {
        VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
        if (baseDir != null) {
            return baseDir;
        }

        String basePath = project.getBasePath();

        return basePath == null ? null : LocalFileSystem.getInstance().findFileByNioFile(Path.of(basePath));
    }

    /**
     * Resolves a project-relative path, answering {@code null} for anything that lands outside
     * the project. The paths reaching here come from tool arguments and from project settings
     * alike, and the answers carry file contents, so containment is decided once here rather
     * than by each caller's own reading of the path: a ".." segment, an absolute path and a
     * symbolic link pointing out of the tree all end up outside, and only the resolved file
     * says so.
     */
    @Nullable
    static VirtualFile find(Project project, String relativePath) {
        VirtualFile baseDir = baseDir(project);
        if (baseDir == null) {
            return null;
        }
        VirtualFile file = baseDir.findFileByRelativePath(relativePath);

        return file != null && isInside(baseDir, file) ? file : null;
    }

    /**
     * Whether a file lies under a directory. Callers with a narrower boundary than the project
     * -- the configured schema directories, say -- ask here too, so containment means the same
     * thing everywhere. Both sides are compared as the paths they finally name, so a symbolic
     * link is judged by where it points, and a project that itself lives behind one
     * (/tmp -> /private/tmp on macOS) is not mistaken for somewhere else.
     */
    static boolean isInside(VirtualFile directory, VirtualFile file) {
        return VfsUtilCore.isAncestor(canonical(directory), canonical(file), false);
    }

    private static VirtualFile canonical(VirtualFile file) {
        VirtualFile canonical = file.getCanonicalFile();

        return canonical == null ? file : canonical;
    }

    /** Project-relative path of a file, falling back to the absolute path when it lies outside. */
    static String relativePath(Project project, VirtualFile file) {
        VirtualFile baseDir = baseDir(project);
        String relative = baseDir == null ? null : VfsUtil.getRelativePath(file, baseDir, '/');

        return relative == null ? file.getPath() : relative;
    }

    static boolean isUnsaved(VirtualFile file) {
        return FileDocumentManager.getInstance().isFileModified(file);
    }

    /**
     * Reads a file through its in-memory document when one exists, so unsaved editor changes are
     * reported. Must be called inside a read action.
     */
    @Nullable
    static String contentOf(VirtualFile file) {
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document != null) {
            return document.getText();
        }
        try {
            return VfsUtilCore.loadText(file);
        } catch (IOException | IllegalArgumentException exception) {
            // IllegalArgumentException: binary file without a decompiler; treat as unreadable.
            return null;
        }
    }
}
