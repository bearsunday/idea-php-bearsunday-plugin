package idea.bear.sunday.body;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class BodyTypes {

    private static final int MAX_INLINE_LENGTH = 100;
    private static final String INDENT = "    ";

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

    public static String renderFormatted(BodyType type) {
        return renderFormatted(type, 0, false);
    }

    private static String renderFormatted(BodyType type, int level, boolean forceMultiline) {
        if (type instanceof FormattableBodyType formattableType) {
            return formattableType.renderFormatted(level, forceMultiline);
        }

        return type.render();
    }

    private static String indent(int level) {
        return INDENT.repeat(Math.max(0, level));
    }

    private static boolean isMultiline(String text) {
        return text.contains("\n");
    }

    private static boolean containsShape(BodyType type) {
        if (type instanceof ShapeType) {
            return true;
        }
        if (type instanceof ListType listType) {
            return containsShape(listType.elementType());
        }
        if (type instanceof UnionType unionType) {
            return unionType.types().stream().anyMatch(BodyTypes::containsShape);
        }

        return false;
    }

    private interface FormattableBodyType extends BodyType {

        String renderFormatted(int level, boolean forceMultiline);

    }

    private record NamedType(String name) implements FormattableBodyType {

        private NamedType {
            Objects.requireNonNull(name);
        }

        @Override
        public String render() {
            return name;
        }

        @Override
        public String renderFormatted(int level, boolean forceMultiline) {
            return render();
        }

    }

    private record ListType(BodyType elementType) implements FormattableBodyType {

        private ListType {
            Objects.requireNonNull(elementType);
        }

        @Override
        public String render() {
            return "list<" + elementType.render() + ">";
        }

        @Override
        public String renderFormatted(int level, boolean forceMultiline) {
            String element = BodyTypes.renderFormatted(
                elementType,
                level,
                forceMultiline || containsShape(elementType) || render().length() > MAX_INLINE_LENGTH
            );
            if (!isMultiline(element)) {
                return "list<" + element + ">";
            }

            String[] lines = element.split("\n", -1);
            StringBuilder formatted = new StringBuilder("list<").append(lines[0]);
            for (int i = 1; i < lines.length - 1; i++) {
                formatted.append("\n").append(lines[i]);
            }
            formatted.append("\n").append(lines[lines.length - 1]).append(">");

            return formatted.toString();
        }

    }

    private record ShapeType(List<ShapeField> fields) implements FormattableBodyType {

        @Override
        public String render() {
            if (fields.isEmpty()) {
                return "array{}";
            }

            return fields.stream()
                .map(field -> renderKey(field.key()) + ": " + field.type().render())
                .collect(Collectors.joining(", ", "array{", "}"));
        }

        @Override
        public String renderFormatted(int level, boolean forceMultiline) {
            String compact = render();
            if (fields.isEmpty() || (!forceMultiline && !shouldRenderMultiline(compact))) {
                return compact;
            }

            StringBuilder formatted = new StringBuilder("array{");
            for (int i = 0; i < fields.size(); i++) {
                ShapeField field = fields.get(i);
                String fieldType = BodyTypes.renderFormatted(field.type(), level + 1, containsShape(field.type()));
                String[] lines = fieldType.split("\n", -1);
                String comma = i == fields.size() - 1 ? "" : ",";

                formatted.append("\n")
                    .append(indent(level + 1))
                    .append(renderKey(field.key()))
                    .append(": ")
                    .append(lines[0]);

                for (int line = 1; line < lines.length; line++) {
                    formatted.append("\n").append(lines[line]);
                }
                formatted.append(comma);
            }

            return formatted.append("\n").append(indent(level)).append("}").toString();
        }

        private boolean shouldRenderMultiline(String compact) {
            return compact.length() > MAX_INLINE_LENGTH || fields.stream()
                .map(ShapeField::type)
                .anyMatch(type -> type instanceof UnionType || containsShape(type));
        }

        private static String renderKey(String key) {
            if (key.matches("[A-Za-z_][A-Za-z0-9_]*") || key.matches("0|[1-9][0-9]*")) {
                return key;
            }

            return "'" + key.replace("\\", "\\\\").replace("'", "\\'") + "'";
        }

    }

    private record UnionType(List<BodyType> types) implements FormattableBodyType {

        @Override
        public String render() {
            return types.stream()
                .map(BodyType::render)
                .collect(Collectors.joining("|"));
        }

        @Override
        public String renderFormatted(int level, boolean forceMultiline) {
            String compact = render();
            if (!forceMultiline && compact.length() <= MAX_INLINE_LENGTH && types.stream().noneMatch(BodyTypes::containsShape)) {
                return compact;
            }

            StringBuilder formatted = new StringBuilder();
            for (int i = 0; i < types.size(); i++) {
                String branch = BodyTypes.renderFormatted(types.get(i), level, containsShape(types.get(i)));
                if (i == 0) {
                    formatted.append(branch);
                    continue;
                }

                appendUnionBranch(formatted, branch);
            }

            return formatted.toString();
        }

        private static void appendUnionBranch(StringBuilder formatted, String branch) {
            String[] lines = branch.split("\n", -1);
            formatted.append("|").append(lines[0]);
            for (int i = 1; i < lines.length; i++) {
                formatted.append("\n").append(lines[i]);
            }
        }

    }

}
