package idea.bear.sunday.alps;

import org.jetbrains.annotations.Nullable;

/**
 * An ALPS {@code link} resolved against the file system and the owning profile.
 * {@code external} marks {@code http(s)} targets, which are never resolved or fetched.
 */
public record ResolvedLink(
    @Nullable String rel,
    @Nullable String href,
    @Nullable String resolvedPath,
    boolean exists,
    boolean external
) {
}
