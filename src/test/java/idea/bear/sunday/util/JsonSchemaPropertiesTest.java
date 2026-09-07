package idea.bear.sunday.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonSchemaPropertiesTest {

    @Test
    void extractsPropertyNamesInOrder() {
        List<String> names = JsonSchemaProperties.propertyNames("""
            {
              "$schema": "http://json-schema.org/draft-04/schema#",
              "type": "object",
              "properties": {
                "name": {"type": "string"},
                "age": {"type": "integer"}
              }
            }
            """);

        assertEquals(List.of("name", "age"), names);
    }

    @Test
    void handlesNestedObjectsInProperties() {
        List<String> names = JsonSchemaProperties.propertyNames("""
            {
              "type": "object",
              "properties": {
                "name": {"type": "string"},
                "address": {
                  "type": "object",
                  "properties": {"street": {"type": "string"}}
                }
              }
            }
            """);

        assertEquals(List.of("name", "address"), names);
    }

    @Test
    void returnsEmptyWhenNoProperties() {
        assertTrue(JsonSchemaProperties.propertyNames("""
            {"type": "object"}
            """).isEmpty());
    }

    /**
     * The shape the body type generator writes for a resource method that assigns
     * {@code $this->body} more than once. Read as a top-level {@code properties} lookup it names
     * nothing, which is issue #54.
     */
    @Test
    void unionsTheBranchesOfAnyOf() {
        List<String> names = JsonSchemaProperties.propertyNames("""
            {
              "anyOf": [
                {"type": "object", "properties": {"id": {"type": "integer"}, "title": {"type": "string"}}},
                {"type": "object", "properties": {"status": {"type": "string"}, "id": {"type": "integer"}}}
              ]
            }
            """);

        assertEquals(List.of("id", "title", "status"), names);
    }

    @Test
    void unionsTheBranchesOfOneOfAndAllOf() {
        assertEquals(
            List.of("a", "b"),
            JsonSchemaProperties.propertyNames("""
                {"oneOf": [{"properties": {"a": {}}}, {"properties": {"b": {}}}]}
                """)
        );
        assertEquals(
            List.of("c", "d"),
            JsonSchemaProperties.propertyNames("""
                {"allOf": [{"properties": {"c": {}}}, {"properties": {"d": {}}}]}
                """)
        );
    }

    @Test
    void unionsBranchesNestedInBranches() {
        assertEquals(
            List.of("outer", "inner"),
            JsonSchemaProperties.propertyNames("""
                {
                  "anyOf": [
                    {"properties": {"outer": {}}},
                    {"anyOf": [{"properties": {"inner": {}}}]}
                  ]
                }
                """)
        );
    }

    @Test
    void keepsTopLevelPropertiesAlongsideBranches() {
        assertEquals(
            List.of("own", "branch"),
            JsonSchemaProperties.propertyNames("""
                {"properties": {"own": {}}, "anyOf": [{"properties": {"branch": {}}}]}
                """)
        );
    }

    /**
     * {@code ""} is a property name JSON allows and a completion popup cannot offer. The fact
     * tools keep it; see {@code SchemaFactsService.propertyNames}.
     */
    @Test
    void leavesOutABlankKeyABranchDeclares() {
        assertEquals(
            List.of("kept"),
            JsonSchemaProperties.propertyNames("""
                {"anyOf": [{"properties": {"": {}, " ": {}, "kept": {}}}]}
                """)
        );
    }

    @Test
    void returnsEmptyWhenPropertiesIsNotObject() {
        assertTrue(JsonSchemaProperties.propertyNames("""
            {"properties": ["name", "age"]}
            """).isEmpty());
    }

    @Test
    void returnsEmptyForInvalidJson() {
        assertTrue(JsonSchemaProperties.propertyNames("{not json").isEmpty());
    }

    @Test
    void returnsEmptyForNullOrBlank() {
        assertTrue(JsonSchemaProperties.propertyNames(null).isEmpty());
        assertTrue(JsonSchemaProperties.propertyNames("").isEmpty());
    }

    @Test
    void handlesEscapedPropertyNames() {
        List<String> names = JsonSchemaProperties.propertyNames("""
            {"properties": {"a\\"b": {"type": "string"}, "c": {"type": "integer"}}}
            """);

        assertEquals(List.of("a\"b", "c"), names);
    }

    @Test
    void handlesUnicodeEscapes() {
        List<String> names = JsonSchemaProperties.propertyNames("""
            {"properties": {"caf\\u00e9": {"type": "string"}}}
            """);

        assertEquals(List.of("café"), names);
    }
}