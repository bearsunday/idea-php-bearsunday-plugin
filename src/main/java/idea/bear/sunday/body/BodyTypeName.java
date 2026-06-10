package idea.bear.sunday.body;

import com.jetbrains.php.lang.psi.elements.PhpClass;

public final class BodyTypeName {

    private BodyTypeName() {
    }

    public static String fromClass(PhpClass phpClass) {
        String name = phpClass.getName();
        if (name == null || name.isBlank()) {
            return "ResourceBody";
        }

        return name + "Body";
    }

}
