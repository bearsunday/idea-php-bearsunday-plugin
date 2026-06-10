package idea.bear.sunday.body;

import java.util.Locale;
import java.util.Objects;

public record BodyTypeDeclaration(String typeName, BodyType bodyType, String resourceMethodName) {

    public BodyTypeDeclaration(String typeName, BodyType bodyType) {
        this(typeName, bodyType, "");
    }

    public BodyTypeDeclaration {
        Objects.requireNonNull(typeName);
        Objects.requireNonNull(bodyType);
        resourceMethodName = normalizeResourceMethodName(resourceMethodName);
    }

    public boolean isForResourceMethod(String methodName) {
        return resourceMethodName.equals(normalizeResourceMethodName(methodName));
    }

    private static String normalizeResourceMethodName(String methodName) {
        if (methodName == null || methodName.isBlank()) {
            return "";
        }

        return methodName.toLowerCase(Locale.ROOT);
    }

}
