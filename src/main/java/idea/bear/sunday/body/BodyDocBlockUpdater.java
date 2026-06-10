package idea.bear.sunday.body;

import java.util.ArrayList;
import java.util.List;

public final class BodyDocBlockUpdater {

    private BodyDocBlockUpdater() {
    }

    public static String create(String typeName, BodyType bodyType) {
        return "/**\n"
            + psalmTypeLine(typeName, bodyType) + "\n"
            + propertyLine(typeName) + "\n"
            + " */";
    }

    public static String update(String existingDocBlock, String typeName, BodyType bodyType) {
        if (existingDocBlock == null || existingDocBlock.isBlank()) {
            return create(typeName, bodyType);
        }

        String[] lines = existingDocBlock.split("\\R", -1);
        List<String> keptLines = new ArrayList<>();
        for (String line : lines) {
            if (isGeneratedPsalmTypeLine(line, typeName) || isBodyPropertyLine(line)) {
                continue;
            }
            keptLines.add(line);
        }

        int closingIndex = findClosingLineIndex(keptLines);
        if (closingIndex < 0) {
            return create(typeName, bodyType);
        }

        keptLines.add(closingIndex, propertyLine(typeName));
        keptLines.add(closingIndex, psalmTypeLine(typeName, bodyType));

        return String.join("\n", keptLines);
    }

    private static String psalmTypeLine(String typeName, BodyType bodyType) {
        return " * @psalm-type " + typeName + " = " + bodyType.render();
    }

    private static String propertyLine(String typeName) {
        return " * @property " + typeName + "|null $body";
    }

    private static boolean isGeneratedPsalmTypeLine(String line, String typeName) {
        return line.matches("\\s*\\*\\s*@psalm-type\\s+" + typeName + "\\b.*");
    }

    private static boolean isBodyPropertyLine(String line) {
        return line.matches("\\s*\\*\\s*@property\\s+.+\\s+\\$body\\b.*");
    }

    private static int findClosingLineIndex(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).trim().equals("*/")) {
                return i;
            }
        }

        return -1;
    }

}
