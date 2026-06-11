package idea.bear.sunday.body;

import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.Method;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public final class BodyTypeName {

    private BodyTypeName() {
    }

    public static String fromClass(@NotNull PhpClass phpClass) {
        return resourceName(phpClass) + "Body";
    }

    public static String legacyGetFromClass(@NotNull PhpClass phpClass) {
        return resourceName(phpClass) + "GetBody";
    }

    public static String fromClassAndMethod(@NotNull PhpClass phpClass, @NotNull Method method) {
        if (isGetMethod(method)) {
            return fromClass(phpClass);
        }

        return resourceName(phpClass) + methodSuffix(method) + "Body";
    }

    private static boolean isGetMethod(@NotNull Method method) {
        String methodName = method.getName();
        return methodName != null && ("onGet".equalsIgnoreCase(methodName) || "get".equalsIgnoreCase(methodName));
    }

    private static String resourceName(@NotNull PhpClass phpClass) {
        String name = phpClass.getName();
        if (name == null || name.isBlank()) {
            return "Resource";
        }

        return name;
    }

    private static String methodSuffix(@NotNull Method method) {
        String methodName = method.getName();
        if (methodName == null || methodName.isBlank()) {
            return "";
        }
        if (methodName.startsWith("on") && methodName.length() > 2) {
            return capitalize(methodName.substring(2));
        }

        return capitalize(methodName);
    }

    private static String capitalize(String value) {
        if (value.isBlank()) {
            return "";
        }

        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

}
