package idea.bear.sunday.alps;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
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
import java.util.Locale;
import java.util.Set;

/**
 * Locates ALPS profiles in the project by walking the virtual file system. Deliberately avoids
 * file-based indexes so that profile facts stay available in dumb mode.
 */
@Service(Service.Level.PROJECT)
public final class AlpsProfileDetector {

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("vendor", "node_modules", "tests", ".git", ".idea", "build");

    private final Project project;

    // One volatile field holding both halves: written separately, a scan interleaved with a VFS
    // change could publish a stale list stamped with the current modification count, and the
    // stale answer would then live until the next structure change.
    private volatile Cache cache = new Cache(-1, List.of());

    public AlpsProfileDetector(Project project) {
        this.project = project;
    }

    public static AlpsProfileDetector getInstance(Project project) {
        return project.getService(AlpsProfileDetector.class);
    }

    public List<VirtualFile> findProfiles() {
        long stamp = VirtualFileManager.getInstance().getStructureModificationCount();
        Cache cached = cache;
        if (stamp == cached.stamp()) {
            return cached.profiles();
        }
        List<VirtualFile> profiles = scan();
        cache = new Cache(stamp, profiles);

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
            throw new AlpsUnreadableException("Cannot read ALPS profile " + file.getPath() + ": " + exception.getMessage());
        }
    }

    public boolean isUnsaved(VirtualFile file) {
        return FileDocumentManager.getInstance().isFileModified(file);
    }

    /** Parses a profile in the format its file name implies. Must be called inside a read action. */
    public AlpsProfile parse(VirtualFile file) {
        String text = contentOf(file);

        return file.getName().toLowerCase(Locale.ROOT).endsWith(".xml")
            ? AlpsNormalizer.fromXml(text, file.getPath())
            : AlpsNormalizer.fromJson(text, file.getPath());
    }

    private List<VirtualFile> scan() {
        VirtualFile root = ProjectUtil.guessProjectDir(project);
        if (root == null) {
            return List.of();
        }
        List<VirtualFile> profiles = new ArrayList<>();
        // NO_FOLLOW_SYMLINKS: a link pointing at an ancestor makes the walk recurse forever, and
        // one pointing outside the project would answer with a profile the project does not hold.
        VfsUtilCore.visitChildrenRecursively(root, new VirtualFileVisitor<Void>(VirtualFileVisitor.NO_FOLLOW_SYMLINKS) {
            @Override
            public boolean visitFile(@NotNull VirtualFile file) {
                // The walk covers the whole project; without this it cannot be called off.
                ProgressManager.checkCanceled();
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

    /** The profile list and the modification count it was scanned under, published together. */
    private record Cache(long stamp, List<VirtualFile> profiles) {
    }
}
