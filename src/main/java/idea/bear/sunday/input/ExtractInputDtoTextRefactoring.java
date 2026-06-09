package idea.bear.sunday.input;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ExtractInputDtoTextRefactoring {
    private static final Pattern NAMESPACE = Pattern.compile("(?m)^\\s*namespace\\s+([^;]+);");
    private static final Pattern USE = Pattern.compile("(?m)^use\\s+([^;]+);");
    private static final Pattern METHOD = Pattern.compile("(?s)(/\\*\\*.*?\\*/\\s*)?(public\\s+function\\s+(on[A-Z][A-Za-z0-9_]*)\\s*\\((.*?)\\)\\s*(:\\s*[^\\{]+)?\\{)");
    private static final Pattern PARAM_VAR = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern PARAM_DOC = Pattern.compile("^\\s*\\*\\s*@param\\s+([^\\s]+)\\s+\\$([A-Za-z_][A-Za-z0-9_]*)(?:\\s+(.*?))?\\s*$");

    public @Nullable MethodInfo findMethodAtOffset(@NotNull String text, int offset) {
        Matcher matcher = METHOD.matcher(text);
        while (matcher.find()) {
            int bodyStart = matcher.end() - 1;
            int bodyEnd = findMatchingBrace(text, bodyStart);
            if (bodyEnd < 0) {
                continue;
            }
            if (offset >= matcher.start() && offset <= bodyEnd + 1) {
                return new MethodInfo(
                    matcher.start(), bodyEnd + 1, matcher.start(4), matcher.end(4), bodyStart, bodyEnd,
                    matcher.group(1) == null ? "" : matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4), matcher.group(5) == null ? "" : matcher.group(5)
                );
            }
        }
        return null;
    }

    public @NotNull List<ParamInfo> parseParams(@NotNull String paramsText) {
        List<ParamInfo> params = new ArrayList<>();
        for (String raw : splitTopLevel(paramsText)) {
            String text = raw.trim();
            if (text.isEmpty()) {
                continue;
            }
            Matcher var = PARAM_VAR.matcher(text);
            if (!var.find()) {
                continue;
            }
            String name = var.group(1);
            String before = text.substring(0, var.start()).trim();
            String after = text.substring(var.end()).trim();
            String defaultValue = null;
            int eq = topLevelEquals(after);
            if (eq >= 0) {
                defaultValue = after.substring(eq + 1).trim();
            }
            String noAttrs = before.replaceAll("#\\[[^]]*]", "").trim();
            String[] tokens = noAttrs.split("\\s+");
            String type = tokens.length == 0 || tokens[tokens.length - 1].isBlank() ? "mixed" : tokens[tokens.length - 1];
            boolean supported = !Pattern.compile("&\\s*\\$").matcher(text).find()
                && !text.contains("...")
                && !text.contains("#[Input]")
                && !text.contains("#[InputFile]");
            params.add(new ParamInfo(name, type, defaultValue, text, supported));
        }
        return params;
    }

    public @NotNull RefactoringResult refactorResource(
        @NotNull String text,
        int offset,
        @NotNull Set<String> selectedNames,
        @NotNull String dtoClass,
        @NotNull String dtoVar,
        @NotNull String dtoFqn
    ) {
        MethodInfo method = findMethodAtOffset(text, offset);
        if (method == null) {
            throw new IllegalArgumentException("No BEAR.Resource onXxx method found at caret");
        }
        List<ParamInfo> params = parseParams(method.paramsText());
        List<ParamInfo> selected = params.stream().filter(p -> selectedNames.contains(p.name())).toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("No supported parameters selected");
        }
        String body = text.substring(method.bodyStart() + 1, method.bodyEnd());
        for (ParamInfo param : selected) {
            if (!param.supported()) {
                throw new IllegalArgumentException("Unsupported parameter: $" + param.name());
            }
            if (isVariableWritten(body, param.name())) {
                throw new IllegalArgumentException("Cannot extract parameter that is modified in method body: $" + param.name());
            }
        }

        Map<String, ParamDoc> docs = extractParamDocs(method.docComment());
        String newDoc = removeSelectedParamDocs(method.docComment(), selectedNames);
        String newParams = buildNewParams(params, selectedNames, dtoClass, dtoVar);
        String signature = newDoc + method.signaturePrefix().replace(method.paramsText(), newParams);

        for (ParamInfo param : selected) {
            body = replaceVariableReferences(body, param.name(), "$" + dtoVar + "->" + param.name());
        }
        String transformedMethod = signature + body + "}";
        String newText = text.substring(0, method.start()) + transformedMethod + text.substring(method.end());
        newText = addUse(newText, "Ray\\InputQuery\\Attribute\\Input");
        newText = addUse(newText, dtoFqn.startsWith("\\") ? dtoFqn.substring(1) : dtoFqn);

        String dtoNamespace = namespaceOf(dtoFqn);
        String dtoText = buildDto(dtoNamespace, dtoClass, selected, docs);
        return new RefactoringResult(newText, dtoText, selected);
    }

    public @NotNull String buildDto(@NotNull String namespace, @NotNull String className, @NotNull List<ParamInfo> params, @NotNull Map<String, ParamDoc> docs) {
        StringBuilder doc = new StringBuilder("    /**\n");
        for (ParamInfo param : params) {
            ParamDoc paramDoc = docs.get(param.name());
            String type = paramDoc == null || paramDoc.type().isBlank() ? param.type() : paramDoc.type();
            String desc = paramDoc == null || paramDoc.description().isBlank() ? "" : " " + paramDoc.description();
            doc.append("     * @param ").append(type).append(" $").append(param.name()).append(desc).append("\n");
        }
        doc.append("     */\n");

        StringBuilder ctorParams = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            ParamInfo param = params.get(i);
            ctorParams.append("        #[Input] public readonly ").append(param.type()).append(" $").append(param.name());
            if (param.defaultValue() != null) {
                ctorParams.append(" = ").append(param.defaultValue());
            }
            if (i < params.size() - 1) {
                ctorParams.append(",");
            }
            ctorParams.append("\n");
        }

        return "<?php\n\ndeclare(strict_types=1);\n\n"
            + "namespace " + namespace + ";\n\n"
            + "use Ray\\InputQuery\\Attribute\\Input;\n\n"
            + "final class " + className + "\n"
            + "{\n"
            + doc
            + "    public function __construct(\n"
            + ctorParams
            + "    ) {}\n"
            + "}\n";
    }

    public @NotNull String defaultInputNamespace(@NotNull String resourceText) {
        Matcher matcher = NAMESPACE.matcher(resourceText);
        if (!matcher.find()) {
            return "Input";
        }
        String ns = matcher.group(1).trim();
        int resource = ns.indexOf("\\Resource\\");
        if (resource >= 0) {
            return ns.substring(0, resource) + "\\Input";
        }
        return ns + "\\Input";
    }

    private static int findMatchingBrace(String text, int open) {
        int depth = 0;
        LexState state = LexState.CODE;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            switch (state) {
                case CODE -> {
                    if (c == '\'' ) {
                        state = LexState.SINGLE_QUOTE;
                    } else if (c == '"') {
                        state = LexState.DOUBLE_QUOTE;
                    } else if (c == '/' && next == '/') {
                        state = LexState.LINE_COMMENT;
                        i++;
                    } else if (c == '#') {
                        state = LexState.LINE_COMMENT;
                    } else if (c == '/' && next == '*') {
                        state = LexState.BLOCK_COMMENT;
                        i++;
                    } else if (c == '{') {
                        depth++;
                    } else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            return i;
                        }
                    }
                }
                case SINGLE_QUOTE -> {
                    if (c == '\\') {
                        i++;
                    } else if (c == '\'') {
                        state = LexState.CODE;
                    }
                }
                case DOUBLE_QUOTE -> {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        state = LexState.CODE;
                    }
                }
                case LINE_COMMENT -> {
                    if (c == '\n' || c == '\r') {
                        state = LexState.CODE;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && next == '/') {
                        state = LexState.CODE;
                        i++;
                    }
                }
            }
        }
        return -1;
    }

    private static boolean isVariableWritten(String body, String name) {
        String variable = "\\$" + Pattern.quote(name);
        return containsCodePattern(body, Pattern.compile(variable + "\\s*(?:\\+=|-=|\\*=|/=|%=|\\.=|\\?\\?=|&=|\\|=|\\^=|<<=|>>=|\\+\\+|--|=(?!=|>))"))
            || containsCodePattern(body, Pattern.compile("(?:\\+\\+|--)\\s*" + variable + "\\b"))
            || containsCodePattern(body, Pattern.compile("\\bas\\s+&?\\s*" + variable + "\\b"));
    }

    private static boolean containsCodePattern(String text, Pattern pattern) {
        String codeOnly = maskNonCode(text);
        return pattern.matcher(codeOnly).find();
    }

    private static String replaceVariableReferences(String text, String name, String replacement) {
        StringBuilder out = new StringBuilder(text.length());
        LexState state = LexState.CODE;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            if (state == LexState.CODE && c == '$' && startsIdentifier(text, i + 1, name)) {
                out.append(replacement);
                i += name.length();
                continue;
            }

            out.append(c);
            switch (state) {
                case CODE -> {
                    if (c == '\'' ) {
                        state = LexState.SINGLE_QUOTE;
                    } else if (c == '"') {
                        state = LexState.DOUBLE_QUOTE;
                    } else if (c == '/' && next == '/') {
                        state = LexState.LINE_COMMENT;
                        out.append(next);
                        i++;
                    } else if (c == '#') {
                        state = LexState.LINE_COMMENT;
                    } else if (c == '/' && next == '*') {
                        state = LexState.BLOCK_COMMENT;
                        out.append(next);
                        i++;
                    }
                }
                case SINGLE_QUOTE -> {
                    if (c == '\\') {
                        if (i + 1 < text.length()) {
                            out.append(text.charAt(++i));
                        }
                    } else if (c == '\'') {
                        state = LexState.CODE;
                    }
                }
                case DOUBLE_QUOTE -> {
                    if (c == '\\') {
                        if (i + 1 < text.length()) {
                            out.append(text.charAt(++i));
                        }
                    } else if (c == '"') {
                        state = LexState.CODE;
                    }
                }
                case LINE_COMMENT -> {
                    if (c == '\n' || c == '\r') {
                        state = LexState.CODE;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && next == '/') {
                        out.append(next);
                        i++;
                        state = LexState.CODE;
                    }
                }
            }
        }
        return out.toString();
    }

    private static boolean startsIdentifier(String text, int offset, String name) {
        if (!text.startsWith(name, offset)) {
            return false;
        }
        int end = offset + name.length();
        return end >= text.length() || !isIdentifierPart(text.charAt(end));
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String maskNonCode(String text) {
        StringBuilder out = new StringBuilder(text.length());
        LexState state = LexState.CODE;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            boolean code = state == LexState.CODE;
            out.append(code ? c : (c == '\n' || c == '\r' ? c : ' '));
            switch (state) {
                case CODE -> {
                    if (c == '\'' ) state = LexState.SINGLE_QUOTE;
                    else if (c == '"') state = LexState.DOUBLE_QUOTE;
                    else if (c == '/' && next == '/') { state = LexState.LINE_COMMENT; out.append(' '); i++; }
                    else if (c == '#') state = LexState.LINE_COMMENT;
                    else if (c == '/' && next == '*') { state = LexState.BLOCK_COMMENT; out.append(' '); i++; }
                }
                case SINGLE_QUOTE -> {
                    if (c == '\\') i++;
                    else if (c == '\'') state = LexState.CODE;
                }
                case DOUBLE_QUOTE -> {
                    if (c == '\\') i++;
                    else if (c == '"') state = LexState.CODE;
                }
                case LINE_COMMENT -> { if (c == '\n' || c == '\r') state = LexState.CODE; }
                case BLOCK_COMMENT -> { if (c == '*' && next == '/') { state = LexState.CODE; out.append(' '); i++; } }
            }
        }
        return out.toString();
    }

    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[') depth++;
            if (c == ')' || c == ']') depth--;
            if (c == ',' && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static int topLevelEquals(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[') depth++;
            if (c == ')' || c == ']') depth--;
            if (c == '=' && depth == 0) return i;
        }
        return -1;
    }

    private static Map<String, ParamDoc> extractParamDocs(String docComment) {
        Map<String, ParamDoc> docs = new LinkedHashMap<>();
        if (docComment.isBlank()) {
            return docs;
        }
        for (String line : docComment.split("\\R")) {
            Matcher matcher = PARAM_DOC.matcher(line);
            if (matcher.matches()) {
                docs.put(matcher.group(2), new ParamDoc(matcher.group(1), matcher.group(3) == null ? "" : matcher.group(3)));
            }
        }
        return docs;
    }

    private static String removeSelectedParamDocs(String docComment, Set<String> selectedNames) {
        if (docComment.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String line : docComment.split("\\R", -1)) {
            Matcher matcher = PARAM_DOC.matcher(line);
            if (matcher.matches() && selectedNames.contains(matcher.group(2))) {
                continue;
            }
            out.append(line).append("\n");
        }
        String newDoc = out.toString();
        return hasMeaningfulDocLine(newDoc) ? newDoc : "";
    }

    private static boolean hasMeaningfulDocLine(String docComment) {
        for (String line : docComment.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.equals("/**") || trimmed.equals("*/")) {
                continue;
            }
            if (trimmed.startsWith("*")) {
                trimmed = trimmed.substring(1).trim();
            }
            if (!trimmed.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String buildNewParams(List<ParamInfo> params, Set<String> selectedNames, String dtoClass, String dtoVar) {
        List<String> result = new ArrayList<>();
        boolean inserted = false;
        for (ParamInfo param : params) {
            if (selectedNames.contains(param.name())) {
                if (!inserted) {
                    result.add("#[Input] " + dtoClass + " $" + dtoVar);
                    inserted = true;
                }
                continue;
            }
            result.add(param.originalText());
        }
        return String.join(", ", result);
    }

    private static String addUse(String text, String fqn) {
        String use = "use " + fqn + ";";
        if (text.contains(use)) {
            return text;
        }
        Matcher lastUse = USE.matcher(text);
        int insert = -1;
        while (lastUse.find()) {
            insert = lastUse.end();
        }
        if (insert >= 0) {
            return text.substring(0, insert) + "\n" + use + text.substring(insert);
        }
        Matcher ns = NAMESPACE.matcher(text);
        if (ns.find()) {
            insert = ns.end();
            return text.substring(0, insert) + "\n\n" + use + text.substring(insert);
        }
        return "<?php\n\n" + use + "\n" + text.replaceFirst("^<\\?php\\s*", "");
    }

    private static String namespaceOf(String fqn) {
        String normalized = fqn.startsWith("\\") ? fqn.substring(1) : fqn;
        int pos = normalized.lastIndexOf('\\');
        return pos < 0 ? "" : normalized.substring(0, pos);
    }

    private enum LexState { CODE, SINGLE_QUOTE, DOUBLE_QUOTE, LINE_COMMENT, BLOCK_COMMENT }

    public record MethodInfo(int start, int end, int paramsStart, int paramsEnd, int bodyStart, int bodyEnd, String docComment, String signaturePrefix, String methodName, String paramsText, String returnType) {}
    public record ParamInfo(String name, String type, @Nullable String defaultValue, String originalText, boolean supported) {}
    public record ParamDoc(String type, String description) {}
    public record RefactoringResult(String resourceText, String dtoText, List<ParamInfo> selectedParams) {}

    public @NotNull Set<String> selectedNamesFromText(@NotNull String selectedText, @NotNull List<ParamInfo> params) {
        Set<String> names = new LinkedHashSet<>();
        for (ParamInfo param : params) {
            if (selectedText.contains("$" + param.name())) {
                names.add(param.name());
            }
        }
        return names;
    }
}
