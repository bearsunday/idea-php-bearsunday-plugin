package idea.bear.sunday.alps;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Locates ALPS profiles in the project by walking the virtual file system. Deliberately avoids
 * file-based indexes so that profile facts stay available in dumb mode.
 */
@Service(Service.Level.PROJECT)
public final class AlpsProfileDetector {

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("vendor", "node_modules", "tests", ".git", ".idea", "build");

    private final Project project;

    private volatile long cachedStamp = -1;
    private volatile List<VirtualFile> cachedProfiles = List.of();

    public AlpsProfileDetector(Project project) {
        this.project = project;
    }

    public static AlpsProfileDetector getInstance(Project project) {
        return project.getService(AlpsProfileDetector.class);
    }

    public List<VirtualFile> findProfiles() {
        long stamp = VirtualFileManager.getInstance().getStructureModificationCount();
        if (stamp == cachedStamp) {
            return cachedProfiles;
        }
        List<VirtualFile> profiles = scan();
        cachedProfiles = profiles;
        cachedStamp = stamp;

        return profiles;
    }

    /**
     * Reads the profile through the in-memory document when one exists, so unsaved editor
     * changes are reported. Must be called inside a read action.
     */
    public String contentOf(VirtualFile file) {
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document != null) {
            return document.getText();
        }
        try {
            return VfsUtilCore.loadText(file);
        } catch (IOException exception) {
            throw new AlpsParseException("Cannot read ALPS profile " + file.getPath() + ": " + exception.getMessage());
        }
    }

    public boolean isUnsaved(VirtualFile file) {
        return FileDocumentManager.getInstance().isFileModified(file);
    }

    private List<VirtualFile> scan() {
        VirtualFile root = ProjectUtil.guessProjectDir(project);
        if (root == null) {
            return List.of();
        }
        List<VirtualFile> profiles = new ArrayList<>();
        VfsUtilCore.visitChildrenRecursively(root, new VirtualFileVisitor<Void>() {
            @Override
            public boolean visitFile(@NotNull VirtualFile file) {
                if (file.isDirectory()) {
                    return file.equals(root) || !SKIPPED_DIRECTORIES.contains(file.getName());
                }
                if (isProfileName(file.getName())) {
                    profiles.add(file);
                }

                return true;
            }
        });

        return List.copyOf(profiles);
    }

    private static boolean isProfileName(String name) {
        return name.equals("alps.json")
            || name.equals("alps.xml")
            || name.endsWith(".alps.json")
            || name.endsWith(".alps.xml");
    }
}
