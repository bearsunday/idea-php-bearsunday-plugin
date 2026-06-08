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
        String relText = rel.isBlank() ? "" : " rel=\"" + rel + "\"";
        String targetArgument = kind.equals("Embed") ? "src" : "href";

        return kind + relText
            + " from " + sourceFileName()
            + "  " + targetArgument + "=\"" + rawTargetUri + "\"";
    }

    private String sourceFileName() {
        int index = sourceFilePath.lastIndexOf('/');
        return index >= 0 ? sourceFilePath.substring(index + 1) : sourceFilePath;
    }
}
