package idea.bear.sunday.body;

import java.util.List;
import java.util.Optional;

public record BodyTypeCollection(List<BodyTypeDeclaration> declarations) {

    public BodyTypeCollection {
        declarations = List.copyOf(declarations);
    }

    public List<String> typeNames() {
        return declarations.stream()
            .map(BodyTypeDeclaration::typeName)
            .toList();
    }

    public Optional<BodyTypeDeclaration> declarationForResourceMethod(String methodName) {
        return declarations.stream()
            .filter(declaration -> declaration.isForResourceMethod(methodName))
            .findFirst();
    }

}
