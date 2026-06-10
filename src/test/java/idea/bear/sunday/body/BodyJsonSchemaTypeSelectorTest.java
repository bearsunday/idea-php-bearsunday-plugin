package idea.bear.sunday.body;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BodyJsonSchemaTypeSelectorTest {

    @Test
    void prefersGetBodyForMethodlessSchemaFile() {
        BodyTypeCollection collection = new BodyTypeCollection(List.of(
            new BodyTypeDeclaration("ArticlePostBody", BodyTypes.shape(List.of(new ShapeField("status", BodyTypes.STRING)))),
            new BodyTypeDeclaration("ArticleGetBody", BodyTypes.shape(List.of(new ShapeField("id", BodyTypes.INT))))
        ));

        BodyType selected = BodyJsonSchemaTypeSelector.select(collection);

        assertEquals("array{id: int}", selected.render());
    }

    @Test
    void fallsBackToUnionWhenGetBodyIsMissing() {
        BodyTypeCollection collection = new BodyTypeCollection(List.of(
            new BodyTypeDeclaration("ArticlePostBody", BodyTypes.shape(List.of(new ShapeField("status", BodyTypes.STRING)))),
            new BodyTypeDeclaration("ArticlePutBody", BodyTypes.shape(List.of(new ShapeField("id", BodyTypes.INT))))
        ));

        BodyType selected = BodyJsonSchemaTypeSelector.select(collection);

        assertEquals("array{status: string}|array{id: int}", selected.render());
    }

}
