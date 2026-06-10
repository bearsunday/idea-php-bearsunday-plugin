package idea.bear.sunday.body;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class BodyTypes {

    public static final BodyType MIXED = named("mixed");
    public static final BodyType STRING = named("string");
    public static final BodyType INT = named("int");
    public static final BodyType FLOAT = named("float");
    public static final BodyType BOOL = named("bool");
    public static final BodyType NULL = named("null");

    private BodyTypes() {
    }

    public static BodyType named(String name) {
        return new NamedType(name);
    }

    public static BodyType list(BodyType elementType) {
        return new ListType(elementType);
    }

    public static BodyType shape(List<ShapeField> fields) {
        return new ShapeType(List.copyOf(fields));
    }

    public static BodyType union(List<BodyType> types) {
        List<BodyType> flattened = new ArrayList<>();
        for (BodyType type : types) {
            if (type instanceof UnionType unionType) {
                flattened.addAll(unionType.types());
                continue;
            }
            flattened.add(type);
        }

        Map<String, BodyType> unique = new LinkedHashMap<>();
        for (BodyType type : flattened) {
            unique.put(type.render(), type);
        }

        if (unique.isEmpty()) {
            return MIXED;
        }
        if (unique.size() == 1) {
            return unique.values().iterator().next();
        }

        return new UnionType(List.copyOf(unique.values()));
    }

    private record NamedType(String name) implements BodyType {

        private NamedType {
            Objects.requireNonNull(name);
        }

        @Override
        public String render() {
            return name;
        }

    }

    private record ListType(BodyType elementType) implements BodyType {

        private ListType {
            Objects.requireNonNull(elementType);
        }

        @Override
        public String render() {
            return "list<" + elementType.render() + ">";
        }

    }

    private record ShapeType(List<ShapeField> fields) implements BodyType {

        @Override
        public String render() {
            if (fields.isEmpty()) {
                return "array{}";
            }

            return fields.stream()
                .map(field -> renderKey(field.key()) + ": " + field.type().render())
                .collect(Collectors.joining(", ", "array{", "}"));
        }

        private static String renderKey(String key) {
            if (key.matches("[A-Za-z_][A-Za-z0-9_]*") || key.matches("0|[1-9][0-9]*")) {
                return key;
            }

            return "'" + key.replace("\\", "\\\\").replace("'", "\\'") + "'";
        }

    }

    private record UnionType(List<BodyType> types) implements BodyType {

        @Override
        public String render() {
            return types.stream()
                .map(BodyType::render)
                .collect(Collectors.joining("|"));
        }

    }

}
