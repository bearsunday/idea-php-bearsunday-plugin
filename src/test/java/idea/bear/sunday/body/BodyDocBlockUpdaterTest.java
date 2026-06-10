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
             * @psalm-type IndexBody = array{id: int}
             * @property IndexBody|null $body
             */""", BodyDocBlockUpdater.create("IndexBody", bodyType));
    }

    @Test
    void appendsGeneratedLinesToExistingDocBlock() {
        BodyType bodyType = BodyTypes.shape(List.of(new ShapeField("name", BodyTypes.STRING)));

        assertEquals("""
            /**
             * Existing summary.
             * @psalm-type IndexBody = array{name: string}
             * @property IndexBody|null $body
             */""", BodyDocBlockUpdater.update("""
            /**
             * Existing summary.
             */""", "IndexBody", bodyType));
    }

    @Test
    void replacesExistingGeneratedLinesOnly() {
        BodyType bodyType = BodyTypes.shape(List.of(new ShapeField("id", BodyTypes.INT)));

        assertEquals("""
            /**
             * Existing summary.
             * @psalm-type IndexBody = array{id: int}
             * @property IndexBody|null $body
             */""", BodyDocBlockUpdater.update("""
            /**
             * Existing summary.
             * @psalm-type IndexBody = array{old: string}
             * @property array<string, mixed>|null $body
             */""", "IndexBody", bodyType));
    }

}
