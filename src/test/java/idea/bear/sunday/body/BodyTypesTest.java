package idea.bear.sunday.body;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BodyTypesTest {

    @Test
    void simpleShape() {
        BodyType type = BodyTypes.shape(List.of(
            new ShapeField("id", BodyTypes.INT),
            new ShapeField("name", BodyTypes.STRING)
        ));

        assertEquals("array{id: int, name: string}", type.render());
    }

    @Test
    void nestedShape() {
        BodyType type = BodyTypes.shape(List.of(
            new ShapeField("user", BodyTypes.shape(List.of(
                new ShapeField("id", BodyTypes.INT),
                new ShapeField("name", BodyTypes.STRING)
            )))
        ));

        assertEquals("array{user: array{id: int, name: string}}", type.render());
    }

    @Test
    void listShape() {
        BodyType type = BodyTypes.list(BodyTypes.shape(List.of(
            new ShapeField("id", BodyTypes.INT)
        )));

        assertEquals("list<array{id: int}>", type.render());
    }

    @Test
    void unionDeduplicatesTypes() {
        BodyType type = BodyTypes.union(List.of(BodyTypes.STRING, BodyTypes.INT, BodyTypes.STRING));

        assertEquals("string|int", type.render());
    }

    @Test
    void quotesNonIdentifierKeys() {
        BodyType type = BodyTypes.shape(List.of(
            new ShapeField("created-at", BodyTypes.STRING)
        ));

        assertEquals("array{'created-at': string}", type.render());
    }

}
