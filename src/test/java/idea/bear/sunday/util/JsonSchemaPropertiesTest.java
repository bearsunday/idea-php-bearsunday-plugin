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