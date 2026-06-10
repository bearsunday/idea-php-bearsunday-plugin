package idea.bear.sunday.body;

final class BodyJsonSchemaTypeSelector {

    private BodyJsonSchemaTypeSelector() {
    }

    static BodyType select(BodyTypeCollection collection) {
        return collection.declarations().stream()
            .filter(declaration -> declaration.typeName().endsWith("GetBody"))
            .findFirst()
            .map(BodyTypeDeclaration::bodyType)
            .orElseGet(() -> BodyTypes.union(collection.declarations().stream()
                .map(BodyTypeDeclaration::bodyType)
                .toList()));
    }

}
