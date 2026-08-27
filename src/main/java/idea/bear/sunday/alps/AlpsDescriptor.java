package idea.bear.sunday.alps;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A normalized ALPS descriptor. Definitions carry an {@code id}; references carry only an
 * {@code href}. {@code textOffset} is a best-effort offset of the definition in the source file
 * (-1 when unknown) kept for future goto/inspection reuse.
 */
public record AlpsDescriptor(
    @Nullable String id,
    @Nullable String type,
    @Nullable String rt,
    @Nullable String href,
    @Nullable String rel,
    @Nullable String doc,
    @Nullable String def,
    @Nullable String tag,
    @Nullable String title,
    List<AlpsLink> links,
    List<AlpsDescriptor> children,
    int textOffset
) {

    public boolean isReference() {
        return id == null && href != null;
    }

    /** Transition types as defined by ALPS: safe, unsafe, idempotent. */
    public boolean isTransition() {
        return "safe".equals(type) || "unsafe".equals(type) || "idempotent".equals(type);
    }
}
