package idea.bear.sunday.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts the field names a JSON Schema document describes.
 *
 * <p>BEAR.Resource response schemas are JSON objects of the shape
 * {@code {"type": "object", "properties": {"name": {...}, "age": {...}}}}; the names of the
 * immediate children of {@code properties} are the keys offered for {@code ->body['<caret>']}
 * completion. A self-contained parser is used (rather than the IntelliJ JSON PSI) so the plugin
 * keeps no dependency on the optional JSON support plugin and the extraction can be unit-tested in
 * isolation.
 *
 * <p>A schema may describe alternatives instead of one object, and the generated schema of a
 * resource method that assigns {@code $this->body} more than once does exactly that: the body
 * type generator renders a union as {@code anyOf}, which carries no {@code properties} of its own.
 * The names of such a document are the union of its branches' names, in first-seen order -- the
 * same rule {@code BodyShapeFactsService.fieldNames} applies to a union body, so the two sides of
 * a schema-to-body comparison count fields the same way. {@code $ref} is not followed; the
 * generator does not emit it.
 *
 * <p>A blank key is left out, and only here: JSON allows {@code ""} as a property name, but a
 * completion popup cannot offer it -- the item would insert nothing. The fact tools keep it,
 * because a field a schema states is one they must report.
 */
public final class JsonSchemaProperties {

    /** The keywords whose array members are themselves schemas describing the same value. */
    private static final List<String> BRANCH_KEYWORDS = List.of("anyOf", "oneOf", "allOf");

    private JsonSchemaProperties() {
    }

    /**
     * Returns the field names {@code json} describes, in first-seen order, or an empty list when
     * the document is not a JSON object or describes no fields.
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

        Set<String> names = new LinkedHashSet<>();
        collect(value, names);

        return List.copyOf(names);
    }

    private static void collect(JsonValue value, Set<String> names) {
        if (!(value instanceof JsonObject object)) {
            return;
        }
        if (object.get("properties") instanceof JsonObject properties) {
            for (String name : properties.keys()) {
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
        }
        for (String keyword : BRANCH_KEYWORDS) {
            if (object.get(keyword) instanceof JsonArray branches) {
                for (JsonValue branch : branches.items()) {
                    collect(branch, names);
                }
            }
        }
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