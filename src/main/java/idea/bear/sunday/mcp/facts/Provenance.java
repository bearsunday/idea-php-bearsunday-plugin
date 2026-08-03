package idea.bear.sunday.mcp.facts;

import org.jetbrains.annotations.Nullable;

/**
 * Where a fact came from. {@code fresh} is {@code unsaved} when the answer reflects in-editor
 * changes that are not on disk yet, otherwise {@code saved}.
 */
public record Provenance(String source, String path, @Nullable Integer offset, String fresh) {

    public static final String SOURCE_FILE = "file";
    public static final String SOURCE_PSI = "psi";
    public static final String SOURCE_DERIVED = "derived";
    public static final String FRESH_SAVED = "saved";
    public static final String FRESH_UNSAVED = "unsaved";

    public static Provenance ofFile(String path, boolean unsaved) {
        return new Provenance(SOURCE_FILE, path, null, unsaved ? FRESH_UNSAVED : FRESH_SAVED);
    }

    public static Provenance ofPsi(String path, boolean unsaved) {
        return new Provenance(SOURCE_PSI, path, null, unsaved ? FRESH_UNSAVED : FRESH_SAVED);
    }

    /** An answer combined from several sources; {@code path} names what it is about, not a file. */
    public static Provenance derived(String path) {
        return derived(path, false);
    }

    /**
     * Same, for an answer whose sources can be read from the editor: {@code unsaved} is true when
     * any contributing source has in-editor changes that are not on disk yet.
     */
    public static Provenance derived(String path, boolean unsaved) {
        return new Provenance(SOURCE_DERIVED, path, null, unsaved ? FRESH_UNSAVED : FRESH_SAVED);
    }
}
