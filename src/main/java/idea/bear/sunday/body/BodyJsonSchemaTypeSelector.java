package idea.bear.sunday.body;

final class BodyJsonSchemaTypeSelector {

    private BodyJsonSchemaTypeSelector() {
    }

    static BodyType select(BodyTypeCollection collection) {
        return collection.declarationForResourceMethod("get")
            .map(BodyTypeDeclaration::bodyType)
            .orElseGet(() -> BodyTypes.union(collection.declarations().stream()
                .map(BodyTypeDeclaration::bodyType)
                .toList()));
    }

}
