package idea.bear.sunday.body;

import java.util.Objects;

public record BodyTypeDeclaration(String typeName, BodyType bodyType) {

    public BodyTypeDeclaration {
        Objects.requireNonNull(typeName);
        Objects.requireNonNull(bodyType);
    }

}
