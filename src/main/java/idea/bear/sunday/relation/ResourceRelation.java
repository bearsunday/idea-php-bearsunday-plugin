package idea.bear.sunday.relation;

import org.jetbrains.annotations.NotNull;

public record ResourceRelation(
    @NotNull String kind,
    @NotNull String rel,
    @NotNull String sourceUri,
    @NotNull String sourceFqn,
    @NotNull String targetUri,
    @NotNull String targetMethod,
    @NotNull String rawTargetUri,
    @NotNull String sourceFilePath,
    int attributeTextOffset
) {

    public String popupText() {
        return sourceShortName() + "  " + attributeSummary();
    }

    /** Short PHP class name of the referencing resource, e.g. {@code RelationDemo}. */
    public String sourceShortName() {
        int index = sourceFqn.lastIndexOf('\\');
        return index >= 0 ? sourceFqn.substring(index + 1) : sourceFqn;
    }

    /** PHP-attribute-style summary of the relation, e.g. {@code #[Embed(rel="dto", src="app://self/point-dto")]}. */
    public String attributeSummary() {
        String targetArgument = kind.equals("Embed") ? "src" : "href";
        StringBuilder summary = new StringBuilder("#[").append(kind).append('(');
        if (!rel.isBlank()) {
            summary.append("rel=\"").append(rel).append("\", ");
        }
        summary.append(targetArgument).append("=\"").append(rawTargetUri).append("\")]");

        return summary.toString();
    }
}
