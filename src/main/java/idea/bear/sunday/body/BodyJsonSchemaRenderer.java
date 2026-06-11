package idea.bear.sunday.body;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BodyJsonSchemaRenderer {

    private static final String INDENT = "  ";

    private BodyJsonSchemaRenderer() {
    }

    public static String render(BodyType bodyType) {
        return schemaOf(Objects.requireNonNull(bodyType)).render();
    }

    private static JsonValue schemaOf(BodyType bodyType) {
        String namedTypeName = BodyTypes.namedTypeName(bodyType);
        if (namedTypeName != null) {
            return namedSchemaOf(namedTypeName);
        }

        BodyType listElementType = BodyTypes.listElementType(bodyType);
        if (listElementType != null) {
            return object(
                property("type", string("array")),
                property("items", schemaOf(listElementType))
            );
        }

        List<ShapeField> shapeFields = BodyTypes.shapeFields(bodyType);
        if (shapeFields != null) {
            return shapeSchemaOf(shapeFields);
        }

        List<BodyType> unionTypes = BodyTypes.unionTypes(bodyType);
        if (unionTypes != null) {
            return unionSchemaOf(unionTypes);
        }

        return object();
    }

    private static JsonValue namedSchemaOf(String name) {
        return switch (name) {
            case "string" -> typeSchema("string");
            case "int" -> typeSchema("integer");
            case "float" -> typeSchema("number");
            case "bool" -> typeSchema("boolean");
            case "null" -> typeSchema("null");
            case "mixed" -> object();
            default -> object();
        };
    }

    private static JsonValue shapeSchemaOf(List<ShapeField> fields) {
        List<JsonProperty> properties = new ArrayList<>();
        List<JsonValue> required = new ArrayList<>();
        for (ShapeField field : fields) {
            properties.add(property(field.key(), schemaOf(field.type())));
            required.add(string(field.key()));
        }

        return object(
            property("type", string("object")),
            property("properties", new JsonObject(properties)),
            property("required", new JsonArray(required))
        );
    }

    private static JsonValue unionSchemaOf(List<BodyType> types) {
        return object(
            property("anyOf", new JsonArray(types.stream()
                .map(BodyJsonSchemaRenderer::schemaOf)
                .toList()))
        );
    }

    private static JsonValue typeSchema(String type) {
        return object(property("type", string(type)));
    }

    private static JsonObject object(JsonProperty... properties) {
        return new JsonObject(List.of(properties));
    }

    private static JsonProperty property(String name, JsonValue value) {
        return new JsonProperty(name, value);
    }

    private static JsonString string(String value) {
        return new JsonString(value);
    }

    private interface JsonValue {

        void appendTo(StringBuilder builder, int level);

        default String render() {
            StringBuilder builder = new StringBuilder();
            appendTo(builder, 0);

            return builder.toString();
        }

    }

    private record JsonObject(List<JsonProperty> properties) implements JsonValue {

        @Override
        public void appendTo(StringBuilder builder, int level) {
            if (properties.isEmpty()) {
                builder.append("{}");

                return;
            }

            builder.append("{\n");
            for (int i = 0; i < properties.size(); i++) {
                JsonProperty property = properties.get(i);
                indent(builder, level + 1);
                string(property.name()).appendTo(builder, level + 1);
                builder.append(": ");
                property.value().appendTo(builder, level + 1);
                if (i < properties.size() - 1) {
                    builder.append(",");
                }
                builder.append("\n");
            }
            indent(builder, level);
            builder.append("}");
        }

    }

    private record JsonArray(List<JsonValue> values) implements JsonValue {

        @Override
        public void appendTo(StringBuilder builder, int level) {
            if (values.isEmpty()) {
                builder.append("[]");

                return;
            }

            builder.append("[\n");
            for (int i = 0; i < values.size(); i++) {
                indent(builder, level + 1);
                values.get(i).appendTo(builder, level + 1);
                if (i < values.size() - 1) {
                    builder.append(",");
                }
                builder.append("\n");
            }
            indent(builder, level);
            builder.append("]");
        }

    }

    private record JsonString(String value) implements JsonValue {

        @Override
        public void appendTo(StringBuilder builder, int level) {
            builder.append('"');
            for (int i = 0; i < value.length(); i++) {
                appendEscaped(builder, value.charAt(i));
            }
            builder.append('"');
        }

        private static void appendEscaped(StringBuilder builder, char character) {
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append("\\u");
                        String hex = Integer.toHexString(character);
                        builder.append("0".repeat(4 - hex.length())).append(hex);
                        return;
                    }
                    builder.append(character);
                }
            }
        }

    }

    private record JsonProperty(String name, JsonValue value) {
    }

    private static void indent(StringBuilder builder, int level) {
        builder.append(INDENT.repeat(level));
    }

}
