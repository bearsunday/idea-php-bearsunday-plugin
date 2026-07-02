package idea.bear.sunday.body;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BodyJsonSchemaRendererTest {

    @Test
    void rendersScalarTypes() {
        assertEquals("""
            {
              "type": "string"
            }""", BodyJsonSchemaRenderer.render(BodyTypes.STRING));
        assertEquals("""
            {
              "type": "integer"
            }""", BodyJsonSchemaRenderer.render(BodyTypes.INT));
        assertEquals("""
            {
              "type": "number"
            }""", BodyJsonSchemaRenderer.render(BodyTypes.FLOAT));
        assertEquals("""
            {
              "type": "boolean"
            }""", BodyJsonSchemaRenderer.render(BodyTypes.BOOL));
        assertEquals("""
            {
              "type": "null"
            }""", BodyJsonSchemaRenderer.render(BodyTypes.NULL));
        assertEquals("{}", BodyJsonSchemaRenderer.render(BodyTypes.MIXED));
    }

    @Test
    void rendersListTypeAsArray() {
        BodyType type = BodyTypes.list(BodyTypes.INT);

        assertEquals("""
            {
              "type": "array",
              "items": {
                "type": "integer"
              }
            }""", BodyJsonSchemaRenderer.render(type));
    }

    @Test
    void rendersShapeTypeAsObject() {
        BodyType type = BodyTypes.shape(List.of(
            new ShapeField("id", BodyTypes.INT),
            new ShapeField("created-at", BodyTypes.STRING),
            new ShapeField("0", BodyTypes.BOOL)
        ));

        assertEquals("""
            {
              "type": "object",
              "properties": {
                "id": {
                  "type": "integer"
                },
                "created-at": {
                  "type": "string"
                },
                "0": {
                  "type": "boolean"
                }
              },
              "required": [
                "id",
                "created-at",
                "0"
              ]
            }""", BodyJsonSchemaRenderer.render(type));
    }

    @Test
    void rendersUnionTypeAsAnyOf() {
        BodyType type = BodyTypes.union(List.of(BodyTypes.STRING, BodyTypes.NULL));

        assertEquals("""
            {
              "anyOf": [
                {
                  "type": "string"
                },
                {
                  "type": "null"
                }
              ]
            }""", BodyJsonSchemaRenderer.render(type));
    }

    @Test
    void rendersNestedShapeListAndUnion() {
        BodyType type = BodyTypes.shape(List.of(
            new ShapeField("items", BodyTypes.list(BodyTypes.shape(List.of(
                new ShapeField("name", BodyTypes.STRING),
                new ShapeField("value", BodyTypes.union(List.of(BodyTypes.INT, BodyTypes.FLOAT)))
            ))))
        ));

        assertEquals("""
            {
              "type": "object",
              "properties": {
                "items": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "name": {
                        "type": "string"
                      },
                      "value": {
                        "anyOf": [
                          {
                            "type": "integer"
                          },
                          {
                            "type": "number"
                          }
                        ]
                      }
                    },
                    "required": [
                      "name",
                      "value"
                    ]
                  }
                }
              },
              "required": [
                "items"
              ]
            }""", BodyJsonSchemaRenderer.render(type));
    }

}
