package idea.bear.sunday.body;

import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.Method;

import java.util.Locale;

public final class BodyTypeName {

    private BodyTypeName() {
    }

    public static String fromClass(PhpClass phpClass) {
        return resourceName(phpClass) + "Body";
    }

    public static String fromClassAndMethod(PhpClass phpClass, Method method) {
        if (isGetMethod(method)) {
            return fromClass(phpClass);
        }

        return resourceName(phpClass) + methodSuffix(method) + "Body";
    }

    private static boolean isGetMethod(Method method) {
        String methodName = method.getName();
        return methodName != null && ("onGet".equalsIgnoreCase(methodName) || "get".equalsIgnoreCase(methodName));
    }

    private static String resourceName(PhpClass phpClass) {
        String name = phpClass.getName();
        if (name == null || name.isBlank()) {
            return "Resource";
        }

        return name;
    }

    private static String methodSuffix(Method method) {
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
