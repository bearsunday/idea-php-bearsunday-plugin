package idea.bear.sunday.body;

import java.util.List;

public record BodyTypeCollection(List<BodyTypeDeclaration> declarations) {

    public BodyTypeCollection {
        declarations = List.copyOf(declarations);
    }

    public List<String> typeNames() {
        return declarations.stream()
            .map(BodyTypeDeclaration::typeName)
            .toList();
    }

}
