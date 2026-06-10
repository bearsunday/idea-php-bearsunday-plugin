package idea.bear.sunday.body;

import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression;
import com.jetbrains.php.lang.psi.elements.ArrayHashElement;
import com.jetbrains.php.lang.psi.elements.PhpExpression;
import com.jetbrains.php.lang.psi.elements.PhpPsiElement;
import com.jetbrains.php.lang.psi.elements.PhpTypedElement;
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression;
import com.jetbrains.php.lang.psi.resolve.types.PhpType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class BodyTypeInferer {

    public BodyType infer(PhpExpression expression) {
        if (expression instanceof ArrayCreationExpression arrayCreationExpression) {
            return inferArray(arrayCreationExpression);
        }
        if (expression instanceof StringLiteralExpression) {
            return BodyTypes.STRING;
        }

        String text = expression.getText().trim().toLowerCase(Locale.ROOT);
        if (text.matches("[+-]?[0-9][0-9_]*")) {
            return BodyTypes.INT;
        }
        if (text.matches("[+-]?([0-9][0-9_]*)?\\.[0-9][0-9_]*")) {
            return BodyTypes.FLOAT;
        }
        if (text.equals("true") || text.equals("false")) {
            return BodyTypes.BOOL;
        }
        if (text.equals("null")) {
            return BodyTypes.NULL;
        }

        return inferTypedElement(expression).orElse(BodyTypes.MIXED);
    }

    private BodyType inferArray(ArrayCreationExpression arrayCreationExpression) {
        List<ArrayElementType> elements = new ArrayList<>();
        int implicitIndex = 0;
        for (ArrayHashElement hashElement : arrayCreationExpression.getHashElements()) {
            PhpPsiElement value = hashElement.getValue();
            if (!(value instanceof PhpExpression valueExpression)) {
                continue;
            }

            String key = keyOf(hashElement.getKey()).orElse(null);
            if (key == null) {
                key = String.valueOf(implicitIndex);
            }
            elements.add(new ArrayElementType(key, infer(valueExpression), isListKey(key, implicitIndex)));
            implicitIndex++;
        }

        if (elements.isEmpty()) {
            return BodyTypes.shape(List.of());
        }

        if (elements.stream().allMatch(ArrayElementType::listKey)) {
            return BodyTypes.list(BodyTypes.union(
                elements.stream().map(ArrayElementType::type).toList()
            ));
        }

        return BodyTypes.shape(elements.stream()
            .map(element -> new ShapeField(element.key(), element.type()))
            .toList());
    }

    private Optional<String> keyOf(PhpPsiElement keyElement) {
        if (keyElement == null) {
            return Optional.empty();
        }
        if (keyElement instanceof StringLiteralExpression stringLiteralExpression) {
            return Optional.of(stringLiteralExpression.getContents());
        }

        String text = keyElement.getText().trim();
        if (text.matches("[+-]?[0-9][0-9_]*")) {
            return Optional.of(text.replace("_", "").replaceFirst("^\\+", ""));
        }

        return Optional.of(text);
    }

    private boolean isListKey(String key, int expectedIndex) {
        return key.equals(String.valueOf(expectedIndex));
    }

    private Optional<BodyType> inferTypedElement(PhpExpression expression) {
        if (!(expression instanceof PhpTypedElement typedElement)) {
            return Optional.empty();
        }

        PhpType type = typedElement.getType();
        if (type.isEmpty()) {
            return Optional.empty();
        }

        List<BodyType> mappedTypes = new ArrayList<>();
        boolean hasMixed = false;
        boolean hasBoolean = false;
        for (String rawType : type.getTypes()) {
            String normalized = rawType.toLowerCase(Locale.ROOT);
            BodyType mappedType = switch (normalized) {
                case "\\string", "string" -> BodyTypes.STRING;
                case "\\int", "int", "\\integer", "integer" -> BodyTypes.INT;
                case "\\float", "float", "\\double", "double" -> BodyTypes.FLOAT;
                case "\\bool", "bool", "\\boolean", "boolean", "\\true", "true", "\\false", "false" -> {
                    hasBoolean = true;
                    yield BodyTypes.BOOL;
                }
                case "\\null", "null" -> BodyTypes.NULL;
                case "\\array", "array" -> BodyTypes.named("array<mixed>");
                case "\\mixed", "mixed" -> {
                    hasMixed = true;
                    yield BodyTypes.MIXED;
                }
                default -> null;
            };
            if (mappedType != null) {
                mappedTypes.add(mappedType);
            }
        }

        if (hasMixed) {
            return Optional.of(BodyTypes.MIXED);
        }
        if (hasBoolean) {
            mappedTypes.removeIf(typePart -> typePart.render().equals("bool"));
            mappedTypes.add(BodyTypes.BOOL);
        }

        if (mappedTypes.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(BodyTypes.union(mappedTypes));
    }

    private record ArrayElementType(String key, BodyType type, boolean listKey) {
    }

}
