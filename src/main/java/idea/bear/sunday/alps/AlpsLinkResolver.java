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
        // Every scheme is external, not only http(s): a urn: or mailto: href names nothing on
        // disk, and a protocol-relative href (//host/x) names a host, not a sibling file.
        if (hasScheme(href) || href.startsWith("//")) {
            return new ResolvedLink(link.rel(), href, null, false, true);
        }
        if (href.startsWith("#")) {
            String id = href.substring(1);
            boolean exists = findById(profile.descriptors(), id) != null;

            return new ResolvedLink(link.rel(), href, profile.sourcePath() + "#" + id, exists, false);
        }

        // A relative href may carry a fragment (Foo.json#bar); only the file part is resolvable.
        // ".." is never followed: existence outside the project is not this tool's to answer.
        // Neither is a root-absolute href (/schema/x.json): it is relative to a site root only
        // the deployment knows, not to the profile's own directory.
        int fragment = href.indexOf('#');
        String filePart = fragment < 0 ? href : href.substring(0, fragment);
        VirtualFile parent = profileFile.getParent();
        VirtualFile target = parent == null || filePart.isEmpty() || filePart.startsWith("/") || filePart.contains("..")
            ? null
            : parent.findFileByRelativePath(filePart);

        return new ResolvedLink(link.rel(), href, target == null ? null : target.getPath(), target != null, false);
    }

    /** Whether the href opens with an RFC 3986 scheme; a relative reference cannot, by grammar. */
    private static boolean hasScheme(String href) {
        int colon = href.indexOf(':');
        if (colon <= 0 || !isAsciiLetter(href.charAt(0))) {
            return false;
        }
        for (int i = 1; i < colon; i++) {
            char character = href.charAt(i);
            if (!isAsciiLetter(character) && !Character.isDigit(character)
                && character != '+' && character != '-' && character != '.') {
                return false;
            }
        }

        return true;
    }

    private static boolean isAsciiLetter(char character) {
        return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z');
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
