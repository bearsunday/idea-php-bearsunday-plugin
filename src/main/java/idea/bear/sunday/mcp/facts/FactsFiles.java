package idea.bear.sunday.mcp.facts;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

/**
 * File access shared by the fact services: project-relative paths and reads that see unsaved
 * editor changes.
 */
final class FactsFiles {

    private FactsFiles() {
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

    @Nullable
    static VirtualFile find(Project project, String relativePath) {
        VirtualFile baseDir = baseDir(project);

        return baseDir == null ? null : baseDir.findFileByRelativePath(relativePath);
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
