package idea.bear.sunday.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts the top-level {@code properties} keys from a JSON Schema document.
 *
 * <p>BEAR.Resource response schemas are JSON objects of the shape
 * {@code {"type": "object", "properties": {"name": {...}, "age": {...}}}}; the names of the
 * immediate children of {@code properties} are the keys offered for {@code ->body['<caret>']}
 * completion. A self-contained parser is used (rather than the IntelliJ JSON PSI) so the plugin
 * keeps no dependency on the optional JSON support plugin and the extraction can be unit-tested in
 * isolation.
 */
public final class JsonSchemaProperties {

    private JsonSchemaProperties() {
    }

    /**
     * Returns the names of the top-level {@code properties} entries in {@code json}, in document
     * order, or an empty list when the document is not a JSON object or has no {@code properties}
     * object.
     */
    @NotNull
    public static List<String> propertyNames(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonValue value;
        try {
            value = new Parser(json).parse();
        } catch (JsonParseException e) {
            return List.of();
        }
        if (!(value instanceof JsonObject root)) {
            return List.of();
        }
        JsonValue properties = root.get("properties");
        if (!(properties instanceof JsonObject propsObject)) {
            return List.of();
        }

        List<String> names = new ArrayList<>();
        for (String name : propsObject.keys()) {
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    private sealed interface JsonValue {
    }

    private record JsonObject(@NotNull Map<String, JsonValue> members) implements JsonValue {
        JsonObject() {
            this(new LinkedHashMap<>());
        }

        @Nullable
        JsonValue get(@NotNull String key) {
            return members.get(key);
        }

        @NotNull
        List<String> keys() {
            return new ArrayList<>(members.keySet());
        }
    }

    private record JsonArray(@NotNull List<JsonValue> items) implements JsonValue {
    }

    private record JsonPrimitive() implements JsonValue {
    }

    private static final class JsonParseException extends Exception {
    }

    private static final class Parser {
        private final String text;
        private int pos;

        private Parser(@NotNull String text) {
            this.text = text;
        }

        @NotNull
        JsonValue parse() throws JsonParseException {
            skipWhitespace();
            JsonValue value = parseValue();
            skipWhitespace();
            if (pos != text.length()) {
                throw new JsonParseException();
            }
            return value;
        }

        @NotNull
        private JsonValue parseValue() throws JsonParseException {
            skipWhitespace();
            if (pos >= text.length()) {
                throw new JsonParseException();
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> {
                    parseString();
                    yield new JsonPrimitive();
                }
                case 't', 'f', 'n', '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                    parseLiteral();
                    yield new JsonPrimitive();
                }
                default -> throw new JsonParseException();
            };
        }

        @NotNull
        private JsonObject parseObject() throws JsonParseException {
            expect('{');
            JsonObject object = new JsonObject();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return object;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                JsonValue value = parseValue();
                object.members.put(key, value);
                skipWhitespace();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    return object;
                }
                throw new JsonParseException();
            }
        }

        @NotNull
        private JsonArray parseArray() throws JsonParseException {
            expect('[');
            List<JsonValue> items = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return new JsonArray(items);
            }
            while (true) {
                items.add(parseValue());
                skipWhitespace();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == ']') {
                    return new JsonArray(items);
                }
                throw new JsonParseException();
            }
        }

        @NotNull
        private String parseString() throws JsonParseException {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= text.length()) {
                        throw new JsonParseException();
                    }
                    char escaped = text.charAt(pos++);
                    sb.append(switch (escaped) {
                        case '"' -> '"';
                        case '\\' -> '\\';
                        case '/' -> '/';
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case 'u' -> {
                            if (pos + 4 > text.length()) {
                                throw new JsonParseException();
                            }
                            int code = Integer.parseInt(text.substring(pos, pos + 4), 16);
                            pos += 4;
                            yield (char) code;
                        }
                        default -> throw new JsonParseException();
                    });
                } else {
                    sb.append(c);
                }
            }
            throw new JsonParseException();
        }

        private void parseLiteral() throws JsonParseException {
            int start = pos;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) {
                    break;
                }
                pos++;
            }
            if (pos == start) {
                throw new JsonParseException();
            }
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private void expect(char expected) throws JsonParseException {
            if (pos >= text.length() || text.charAt(pos) != expected) {
                throw new JsonParseException();
            }
            pos++;
        }

        private char peek() {
            return pos < text.length() ? text.charAt(pos) : '\0';
        }

        private char next() throws JsonParseException {
            if (pos >= text.length()) {
                throw new JsonParseException();
            }
            return text.charAt(pos++);
        }
    }
}