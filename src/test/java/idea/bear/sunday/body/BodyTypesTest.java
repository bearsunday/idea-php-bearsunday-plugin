package idea.bear.sunday.body;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals("""
            array{
                user: array{
                    id: int,
                    name: string
                }
            }""", BodyTypes.renderFormatted(type));
    }

    @Test
    void listShape() {
        BodyType type = BodyTypes.list(BodyTypes.shape(List.of(
            new ShapeField("id", BodyTypes.INT)
        )));

        assertEquals("list<array{id: int}>", type.render());
        assertEquals("""
            list<array{
                id: int
            }>""", BodyTypes.renderFormatted(type));
    }

    @Test
    void unionDeduplicatesTypes() {
        BodyType type = BodyTypes.union(List.of(BodyTypes.STRING, BodyTypes.INT, BodyTypes.STRING));

        assertEquals("string|int", type.render());
    }

    @Test
    void withShapeFieldAddsAndReplacesShapeField() {
        BodyType type = BodyTypes.shape(List.of(
            new ShapeField("id", BodyTypes.INT)
        ));

        BodyType added = BodyTypes.withShapeField(type, new ShapeField("name", BodyTypes.STRING));
        BodyType replaced = BodyTypes.withShapeField(added, new ShapeField("id", BodyTypes.STRING));

        assertEquals("array{id: int, name: string}", added.render());
        assertEquals("array{id: string, name: string}", replaced.render());
    }

    @Test
    void withShapeFieldAppliesToUnionBranches() {
        BodyType type = BodyTypes.union(List.of(
            BodyTypes.shape(List.of(new ShapeField("id", BodyTypes.INT))),
            BodyTypes.shape(List.of(new ShapeField("status", BodyTypes.STRING)))
        ));

        BodyType updated = BodyTypes.withShapeField(type, new ShapeField("meta", BodyTypes.MIXED));

        assertEquals("array{id: int, meta: mixed}|array{status: string, meta: mixed}", updated.render());
    }

    @Test
    void formatsShapeUnionsAcrossLines() {
        BodyType type = BodyTypes.union(List.of(
            BodyTypes.shape(List.of(new ShapeField("id", BodyTypes.INT))),
            BodyTypes.shape(List.of(new ShapeField("status", BodyTypes.STRING)))
        ));

        assertEquals("array{id: int}|array{status: string}", type.render());
        assertEquals("""
            array{
                id: int
            }|array{
                status: string
            }""", BodyTypes.renderFormatted(type));
    }

    @Test
    void quotesNonIdentifierKeys() {
        BodyType type = BodyTypes.shape(List.of(
            new ShapeField("created-at", BodyTypes.STRING)
        ));

        assertEquals("array{'created-at': string}", type.render());
    }

    @Test
    void fallsBackToGenericArrayForControlCharacterKeys() {
        BodyType type = BodyTypes.shape(List.of(
            new ShapeField("line\nbreak\tkey\0", BodyTypes.STRING)
        ));

        // Psalm cannot faithfully represent a control-character shape key, so the whole shape
        // degrades to a generic array keyed by array-key.
        assertEquals("array<array-key, string>", type.render());
    }

    @Test
    void rendersGenericArray() {
        BodyType type = BodyTypes.map(BodyTypes.union(List.of(BodyTypes.INT, BodyTypes.STRING)));

        assertEquals("array<array-key, int|string>", type.render());
    }

    @Test
    void shapeFieldRequiresKeyAndType() {
        assertThrows(NullPointerException.class, () -> new ShapeField(null, BodyTypes.STRING));
        assertThrows(NullPointerException.class, () -> new ShapeField("id", null));
    }

}
