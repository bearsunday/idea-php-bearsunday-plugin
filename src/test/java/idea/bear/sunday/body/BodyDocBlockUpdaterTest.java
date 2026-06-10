package idea.bear.sunday.body;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BodyDocBlockUpdaterTest {

    @Test
    void createsDocBlock() {
        BodyType bodyType = BodyTypes.shape(List.of(new ShapeField("id", BodyTypes.INT)));

        assertEquals("""
            /**
             * @psalm-type IndexGetBody = array{id: int}
             * @property IndexGetBody|null $body
             */""", BodyDocBlockUpdater.create(collection("IndexGetBody", bodyType)));
    }

    @Test
    void createsDocBlockWithMethodSpecificBodyTypes() {
        BodyType getBodyType = BodyTypes.shape(List.of(new ShapeField("id", BodyTypes.INT)));
        BodyType postBodyType = BodyTypes.shape(List.of(new ShapeField("status", BodyTypes.STRING)));

        assertEquals("""
            /**
             * @psalm-type ArticleGetBody = array{id: int}
             * @psalm-type ArticlePostBody = array{status: string}
             * @property ArticleGetBody|ArticlePostBody|null $body
             */""", BodyDocBlockUpdater.create(new BodyTypeCollection(List.of(
            new BodyTypeDeclaration("ArticleGetBody", getBodyType),
            new BodyTypeDeclaration("ArticlePostBody", postBodyType)
        ))));
    }

    @Test
    void appendsGeneratedLinesToExistingDocBlock() {
        BodyType bodyType = BodyTypes.shape(List.of(new ShapeField("name", BodyTypes.STRING)));

        assertEquals("""
            /**
             * Existing summary.
             *
             * @psalm-type IndexGetBody = array{name: string}
             * @property IndexGetBody|null $body
             */""", BodyDocBlockUpdater.update("""
            /**
             * Existing summary.
             */""", collection("IndexGetBody", bodyType)));
    }

    @Test
    void replacesExistingGeneratedLinesOnly() {
        BodyType bodyType = BodyTypes.shape(List.of(new ShapeField("id", BodyTypes.INT)));

        assertEquals("""
            /**
             * Existing summary.
             *
             * @psalm-type IndexGetBody = array{id: int}
             * @property IndexGetBody|null $body
             */""", BodyDocBlockUpdater.update("""
            /**
             * Existing summary.
             * @psalm-type IndexGetBody = array{old: string}
             * @property array<string, mixed>|null $body
             */""", collection("IndexGetBody", bodyType)));
    }

    @Test
    void replacesLegacyClassBodyTypeName() {
        BodyType bodyType = BodyTypes.shape(List.of(new ShapeField("id", BodyTypes.INT)));

        assertEquals("""
            /**
             * Existing summary.
             *
             * @psalm-type IndexGetBody = array{id: int}
             * @property IndexGetBody|null $body
             */""", BodyDocBlockUpdater.update("""
            /**
             * Existing summary.
             * @psalm-type IndexBody = array{
             *     old: string
             * }
             * @property IndexBody|null $body
             */""", collection("IndexGetBody", bodyType), "IndexBody"));
    }

    @Test
    void removesMethodBodyTypeNamesFromPreviousBodyProperty() {
        BodyType bodyType = BodyTypes.shape(List.of(new ShapeField("id", BodyTypes.INT)));

        assertEquals("""
            /**
             * Existing summary.
             *
             * @psalm-type IndexGetBody = array{id: int}
             * @property IndexGetBody|null $body
             */""", BodyDocBlockUpdater.update("""
            /**
             * Existing summary.
             * @psalm-type IndexGetBody = array{old: string}
             * @psalm-type IndexPutBody = array{
             *     removed: bool
             * }
             * @property IndexGetBody|IndexPutBody|null $body
             */""", collection("IndexGetBody", bodyType)));
    }

    @Test
    void createsFormattedDocBlockForNestedUnionShape() {
        BodyType bodyType = BodyTypes.union(List.of(
            BodyTypes.shape(List.of(
                new ShapeField("id", BodyTypes.INT),
                new ShapeField("posts", BodyTypes.list(BodyTypes.shape(List.of(
                    new ShapeField("id", BodyTypes.INT),
                    new ShapeField("title", BodyTypes.STRING)
                ))))
            )),
            BodyTypes.shape(List.of(
                new ShapeField("status", BodyTypes.STRING),
                new ShapeField("id", BodyTypes.INT)
            ))
        ));

        assertEquals("""
            /**
             * @psalm-type IndexGetBody = array{
             *     id: int,
             *     posts: list<array{
             *         id: int,
             *         title: string
             *     }>
             * }|array{
             *     status: string,
             *     id: int
             * }
             * @property IndexGetBody|null $body
             */""", BodyDocBlockUpdater.create(collection("IndexGetBody", bodyType)));
    }

    @Test
    void replacesExistingFormattedDocBlock() {
        BodyType bodyType = BodyTypes.shape(List.of(new ShapeField("name", BodyTypes.STRING)));

        assertEquals("""
            /**
             * Existing summary.
             *
             * @psalm-type IndexGetBody = array{name: string}
             * @property IndexGetBody|null $body
             */""", BodyDocBlockUpdater.update("""
            /**
             * Existing summary.
             * @psalm-type IndexGetBody = array{
             *     id: int
             * }
             * @property IndexGetBody|null $body
             */""", collection("IndexGetBody", bodyType)));
    }

    @Test
    void doesNotDuplicateBlankLineBeforeGeneratedTags() {
        BodyType bodyType = BodyTypes.shape(List.of(new ShapeField("name", BodyTypes.STRING)));

        assertEquals("""
            /**
             * Existing summary.
             *
             * @psalm-type IndexGetBody = array{name: string}
             * @property IndexGetBody|null $body
             */""", BodyDocBlockUpdater.update("""
            /**
             * Existing summary.
             *
             */""", collection("IndexGetBody", bodyType)));
    }

    private static BodyTypeCollection collection(String typeName, BodyType bodyType) {
        return new BodyTypeCollection(List.of(new BodyTypeDeclaration(typeName, bodyType)));
    }

}
