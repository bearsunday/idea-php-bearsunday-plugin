package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkSuggestServiceFixtureTest {

    private static final String PROFILE = """
        {
          "alps": {
            "descriptor": [
              {"id": "Point", "descriptor": [{"id": "x", "type": "semantic"}]}
            ]
          }
        }
        """;

    private static final String LINKED_PROFILE = """
        {
          "alps": {
            "descriptor": [
              {
                "id": "Point",
                "link": [{"rel": "describedby", "href": "var/json_schema/point.json"}],
                "descriptor": [{"id": "x", "type": "semantic"}]
              }
            ]
          }
        }
        """;

    private static final String POINT_SCHEMA = """
        {"type": "object", "properties": {"x": {"type": "integer"}}}
        """;

    private static final String OPENAPI = """
        {"openapi": "3.0.0", "paths": {"/point": {"get": {"operationId": "getPoint"}}}}
        """;

    private CodeInsightTestFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        IdeaTestFixtureFactory factory = IdeaTestFixtureFactory.getFixtureFactory();
        TestFixtureBuilder<IdeaProjectTestFixture> builder = factory.createFixtureBuilder(getClass().getSimpleName());
        fixture = factory.createCodeInsightFixture(builder.getFixture(), factory.createTempDirTestFixture());
        fixture.setUp();
    }

    @AfterEach
    void tearDown() throws Exception {
        fixture.tearDown();
    }

    @Test
    void suggestsTheSchemaOfADescriptorThatIsNotLinked() {
        addPhysicalFile("alps.json", PROFILE);
        addPhysicalFile("var/json_schema/point.json", POINT_SCHEMA);

        JsonObject envelope = envelope(facts().suggest("Point", null));
        JsonObject suggestion = suggestion(envelope.getAsJsonArray("suggestions"), "describedby");

        assertEquals("ok", envelope.get("status").getAsString());
        assertEquals("inference", envelope.get("kind").getAsString());
        assertEquals("var/json_schema/point.json", suggestion.get("href").getAsString());
        assertEquals("high", suggestion.get("confidence").getAsString());
        assertTrue(suggestion.get("exists").getAsBoolean());
        assertTrue(suggestion.get("reason").getAsString().contains("Point"));
    }

    @Test
    void doesNotSuggestALinkTheProfileAlreadyDeclares() {
        addPhysicalFile("alps.json", LINKED_PROFILE);
        addPhysicalFile("var/json_schema/point.json", POINT_SCHEMA);

        JsonArray suggestions = envelope(facts().suggest("Point", null)).getAsJsonArray("suggestions");

        assertEquals(0, suggestions.size());
    }

    @Test
    void suggestsTheOpenApiOperationOfADescriptor() {
        addPhysicalFile("alps.json", PROFILE);
        addPhysicalFile("docs/openapi.json", OPENAPI);

        JsonArray suggestions = envelope(facts().suggest(null, "app://self/point")).getAsJsonArray("suggestions");
        JsonObject suggestion = suggestion(suggestions, "related");

        assertEquals(1, suggestions.size());
        assertEquals("docs/openapi.json#/paths/~1point", suggestion.get("href").getAsString());
        assertEquals("medium", suggestion.get("confidence").getAsString());
    }

    @Test
    void suggestsNothingWhenNeitherSchemaNorOperationExists() {
        addPhysicalFile("alps.json", PROFILE);

        JsonObject envelope = envelope(facts().suggest(null, null));

        assertEquals("ok", envelope.get("status").getAsString());
        assertEquals(0, envelope.getAsJsonArray("suggestions").size());
    }

    @Test
    void reportsNotFoundForAnUnknownDescriptor() {
        addPhysicalFile("alps.json", PROFILE);

        assertEquals("not_found", envelope(facts().suggest("Missing", null)).get("status").getAsString());
    }

    @Test
    void reportsNotFoundWhenTheProjectHasNoProfile() {
        assertEquals("not_found", envelope(facts().suggest("Point", null)).get("status").getAsString());
    }

    /**
     * An unreadable URI used to become "no filter", so every descriptor in the profile was
     * suggested under status=ok -- a confident answer to a typo.
     */
    @Test
    void reportsNotFoundForAnUnsupportedResourceUri() {
        addPhysicalFile("alps.json", PROFILE);

        JsonObject envelope = envelope(facts().suggest(null, "http://example.com/point"));

        assertEquals("not_found", envelope.get("status").getAsString(), envelope::toString);
    }

    private static JsonObject suggestion(JsonArray suggestions, String rel) {
        for (JsonElement element : suggestions) {
            if (rel.equals(element.getAsJsonObject().get("rel").getAsString())) {
                return element.getAsJsonObject();
            }
        }
        throw new AssertionError("suggestion not found: " + rel);
    }

    private static JsonObject envelope(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private LinkSuggestService facts() {
        return LinkSuggestService.getInstance(fixture.getProject());
    }

    private void addPhysicalFile(String relativePath, String contents) {
        try {
            String basePath = fixture.getProject().getBasePath();
            assertNotNull(basePath);
            Path path = Path.of(basePath, relativePath);
            Files.createDirectories(path.getParent());
            Files.writeString(path, contents, StandardCharsets.UTF_8);
            assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
