package idea.bear.sunday.body;

import java.util.Objects;

public record ShapeField(String key, BodyType type) {

    public ShapeField {
        Objects.requireNonNull(key);
        Objects.requireNonNull(type);
    }

}
