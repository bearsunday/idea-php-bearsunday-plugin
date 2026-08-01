package idea.bear.sunday.alps;

import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Resolves ALPS {@code link} hrefs: in-profile fragments, sibling profile files, and external URLs.
 */
public final class AlpsLinkResolver {

    private AlpsLinkResolver() {
    }

    public static ResolvedLink resolve(AlpsLink link, VirtualFile profileFile, AlpsProfile profile) {
        String href = link.href();
        if (href == null || href.isBlank()) {
            return new ResolvedLink(link.rel(), href, null, false, false);
        }
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return new ResolvedLink(link.rel(), href, null, false, true);
        }
        if (href.startsWith("#")) {
            String id = href.substring(1);
            boolean exists = findById(profile.descriptors(), id) != null;

            return new ResolvedLink(link.rel(), href, profile.sourcePath() + "#" + id, exists, false);
        }

        // A relative href may carry a fragment (Foo.json#bar); only the file part is resolvable.
        int fragment = href.indexOf('#');
        String filePart = fragment < 0 ? href : href.substring(0, fragment);
        VirtualFile parent = profileFile.getParent();
        VirtualFile target = parent == null || filePart.isEmpty() ? null : parent.findFileByRelativePath(filePart);

        return new ResolvedLink(link.rel(), href, target == null ? null : target.getPath(), target != null, false);
    }

    @Nullable
    public static AlpsDescriptor findById(List<AlpsDescriptor> descriptors, String id) {
        for (AlpsDescriptor descriptor : descriptors) {
            if (id.equals(descriptor.id())) {
                return descriptor;
            }
            AlpsDescriptor nested = findById(descriptor.children(), id);
            if (nested != null) {
                return nested;
            }
        }

        return null;
    }
}
