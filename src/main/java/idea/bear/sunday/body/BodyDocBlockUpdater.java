package idea.bear.sunday.body;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

public final class BodyDocBlockUpdater {

    private BodyDocBlockUpdater() {
    }

    public static String create(BodyTypeCollection bodyTypes) {
        return "/**\n"
            + String.join("\n", generatedLines(bodyTypes)) + "\n"
            + " */";
    }

    public static String update(String existingDocBlock, BodyTypeCollection bodyTypes, String... legacyTypeNames) {
        if (existingDocBlock == null || existingDocBlock.isBlank()) {
            return create(bodyTypes);
        }

        List<String> generatedTypeNames = new ArrayList<>(bodyTypes.typeNames());
        generatedTypeNames.addAll(Arrays.asList(legacyTypeNames));
        String[] lines = existingDocBlock.split("\\R", -1);
        for (String line : lines) {
            generatedTypeNames.addAll(bodyPropertyTypeNames(line));
        }
        List<String> keptLines = new ArrayList<>();
        boolean skippingGeneratedPsalmType = false;
        for (String line : lines) {
            if (skippingGeneratedPsalmType) {
                if (isBodyPropertyLine(line)) {
                    skippingGeneratedPsalmType = false;
                    continue;
                }
                if (isPhpDocTagLine(line) || isClosingLine(line)) {
                    skippingGeneratedPsalmType = false;
                } else {
                    continue;
                }
            }

            if (isGeneratedPsalmTypeLine(line, generatedTypeNames) || isBodyPropertyLine(line)) {
                skippingGeneratedPsalmType = isGeneratedPsalmTypeLine(line, generatedTypeNames);
                continue;
            }
            keptLines.add(line);
        }

        int closingIndex = findClosingLineIndex(keptLines);
        if (closingIndex < 0) {
            return create(bodyTypes);
        }

        List<String> generatedLines = new ArrayList<>(generatedLines(bodyTypes));
        if (needsBlankLineBeforeGeneratedTags(keptLines, closingIndex)) {
            generatedLines.add(0, " *");
        }
        keptLines.addAll(closingIndex, generatedLines);

        return String.join("\n", keptLines);
    }

    private static List<String> generatedLines(BodyTypeCollection bodyTypes) {
        List<String> lines = new ArrayList<>();
        for (BodyTypeDeclaration declaration : bodyTypes.declarations()) {
            lines.addAll(psalmTypeLines(declaration.typeName(), declaration.bodyType()));
        }
        lines.add(propertyLine(bodyTypes.typeNames()));

        return lines;
    }

    private static List<String> psalmTypeLines(String typeName, BodyType bodyType) {
        String[] renderedLines = BodyTypes.renderFormatted(bodyType).split("\n", -1);
        List<String> lines = new ArrayList<>();
        lines.add(" * @psalm-type " + typeName + " = " + renderedLines[0]);
        for (int i = 1; i < renderedLines.length; i++) {
            lines.add(" * " + renderedLines[i]);
        }

        return lines;
    }

    private static String propertyLine(List<String> typeNames) {
        return " * @property " + String.join("|", typeNames) + "|null $body";
    }

    private static boolean isGeneratedPsalmTypeLine(String line, Collection<String> typeNames) {
        for (String typeName : typeNames) {
            if (line.matches("\\s*\\*\\s*@psalm-type\\s+" + Pattern.quote(typeName) + "\\b.*")) {
                return true;
            }
        }

        return false;
    }

    private static boolean isBodyPropertyLine(String line) {
        return line.matches("\\s*\\*\\s*@property\\s+.+\\s+\\$body\\b.*");
    }

    private static List<String> bodyPropertyTypeNames(String line) {
        if (!isBodyPropertyLine(line)) {
            return List.of();
        }

        String typeExpression = line.replaceFirst("^\\s*\\*\\s*@property\\s+", "");
        int bodyIndex = typeExpression.indexOf("$body");
        if (bodyIndex < 0) {
            return List.of();
        }

        List<String> typeNames = new ArrayList<>();
        for (String typeName : typeExpression.substring(0, bodyIndex).trim().split("\\|")) {
            String candidate = typeName.trim();
            if (!"null".equals(candidate) && candidate.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                typeNames.add(candidate);
            }
        }

        return typeNames;
    }

    private static boolean isPhpDocTagLine(String line) {
        return line.matches("\\s*\\*\\s*@\\S+.*");
    }

    private static boolean needsBlankLineBeforeGeneratedTags(List<String> lines, int insertionIndex) {
        int previousContentIndex = insertionIndex - 1;
        while (previousContentIndex >= 0 && lines.get(previousContentIndex).isBlank()) {
            previousContentIndex--;
        }
        if (previousContentIndex < 0) {
            return false;
        }

        String previousLine = lines.get(previousContentIndex);
        if (isOpeningLine(previousLine) || isBlankPhpDocLine(previousLine) || isPhpDocTagLine(previousLine)) {
            return false;
        }

        return hasSummaryContent(lines, insertionIndex);
    }

    private static boolean hasSummaryContent(List<String> lines, int insertionIndex) {
        for (int i = 0; i < insertionIndex; i++) {
            String line = lines.get(i);
            if (isOpeningLine(line) || isBlankPhpDocLine(line) || isPhpDocTagLine(line)) {
                continue;
            }

            return true;
        }

        return false;
    }

    private static boolean isOpeningLine(String line) {
        return line.trim().equals("/**");
    }

    private static boolean isBlankPhpDocLine(String line) {
        return line.matches("\\s*\\*\\s*");
    }

    private static boolean isClosingLine(String line) {
        return line.trim().equals("*/");
    }

    private static int findClosingLineIndex(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (isClosingLine(lines.get(i))) {
                return i;
            }
        }

        return -1;
    }

}
