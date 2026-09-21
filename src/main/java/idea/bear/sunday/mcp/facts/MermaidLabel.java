package idea.bear.sunday.mcp.facts;

/**
 * Escapes text put inside a Mermaid label.
 *
 * <p>The diagrams quote their labels, so a quote ends one early and a pipe ends an edge label the
 * same way. Mermaid spells those as entities, and because an entity itself begins with {@code #},
 * that character is replaced first or the replacements would escape each other. Angle brackets go
 * too: the renderer runs the label through DOMPurify, which drops anything tag-shaped without
 * saying so, and an unreadable install this puts in a label can carry them.
 *
 * <p>A newline becomes a space; a label is one line, and a raw newline ends the statement.
 */
final class MermaidLabel {

    private MermaidLabel() {
    }

    static String escape(String text) {
        return text.replace("#", "#35;")
            .replace("\"", "#quot;")
            .replace("|", "#124;")
            .replace("<", "#60;")
            .replace(">", "#62;")
            .replace("\n", " ");
    }
}
