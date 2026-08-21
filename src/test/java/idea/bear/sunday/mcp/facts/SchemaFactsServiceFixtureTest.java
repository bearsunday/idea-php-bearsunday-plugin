package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
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

class SchemaFactsServiceFixtureTest {

    private static final String SCHEMA_DEMO = """
        <?php

        namespace MyVendor\\MyProject\\Resource\\App;

        use BEAR\\Resource\\Annotation\\JsonSchema;
        use BEAR\\Resource\\ResourceObject;

        final class SchemaDemo extends ResourceObject
        {
            #[JsonSchema(schema: 'point.json', params: 'point-params.json')]
            public function onGet(int $x = 3, int $y = 4): static
            {
                return $this;
            }
        }
        """;

    private static final String POINT = """
        <?php

        namespace MyVendor\\MyProject\\Resource\\App;

        use BEAR\\Resource\\ResourceObject;

        final class Point extends ResourceObject
        {
            public function onGet(int $x = 0): static
            {
                return $this;
            }
        }
        """;

    private static final String POINT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "x": {"type": "integer"},
            "y": {"type": "integer"}
          },
          "required": ["x", "y"]
        }
        """;

    private static final String POINT_PARAMS = """
        {
          "type": "object",
          "properties": {
            "x": {"type": "integer"}
          }
        }
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
    void resolvesTheResponseSchemaDeclaredByTheAttribute() {
        addPhysicalFile("src/Resource/App/SchemaDemo.php", SCHEMA_DEMO);
        addPhysicalFile("var/json_schema/point.json", POINT_SCHEMA);

        JsonObject envelope = envelope(facts().lookup("app://self/schema-demo", null, null, "response"));
        JsonObject match = envelope.getAsJsonArray("matches").get(0).getAsJsonObject();

        assertEquals("ok", envelope.get("status").getAsString());
        assertEquals(1, envelope.getAsJsonArray("matches").size());
        assertEquals("var/json_schema/point.json", match.get("path").getAsString());
        assertEquals("attribute", match.get("source").getAsString());
        assertEquals("response", match.get("kind").getAsString());
        assertEquals("[\"x\",\"y\"]", match.getAsJsonArray("properties").toString());
        assertEquals("[\"x\",\"y\"]", match.getAsJsonArray("required").toString());
        assertTrue(match.getAsJsonObject("raw").has("properties"));
        assertEquals("file", envelope.getAsJsonObject("provenance").get("source").getAsString());
        assertEquals("var/json_schema/point.json", envelope.getAsJsonObject("provenance").get("path").getAsString());
    }

    /** Several matches are a combined answer: one file's provenance cannot speak for the rest. */
    @Test
    void reportsACombinedProvenanceForSeveralMatches() {
        addPhysicalFile("src/Resource/App/SchemaDemo.php", SCHEMA_DEMO);
        addPhysicalFile("var/json_schema/point.json", POINT_SCHEMA);

        JsonObject envelope = envelope(facts().lookup("app://self/schema-demo", null, "point.json", "response"));

        assertTrue(envelope.getAsJsonArray("matches").size() > 1, envelope::toString);
        assertEquals("derived", envelope.getAsJsonObject("provenance").get("source").getAsString());
    }

    @Test
    void resolvesTheRequestSchemaOfTheGivenMethod() {
        addPhysicalFile("src/Resource/App/SchemaDemo.php", SCHEMA_DEMO);
        addPhysicalFile("var/json_validate/point-params.json", POINT_PARAMS);

        JsonObject match = envelope(facts().lookup("app://self/schema-demo", "get", null, "request"))
            .getAsJsonArray("matches").get(0).getAsJsonObject();

        assertEquals("var/json_validate/point-params.json", match.get("path").getAsString());
        assertEquals("request", match.get("kind").getAsString());
        assertEquals("[\"x\"]", match.getAsJsonArray("properties").toString());
    }

    @Test
    void fallsBackToTheNamingConventionWhenNoAttributeDeclaresASchema() {
        addPhysicalFile("src/Resource/App/Point.php", POINT);
        addPhysicalFile("var/json_schema/point.json", POINT_SCHEMA);

        JsonObject match = envelope(facts().lookup("app://self/point", null, null, null))
            .getAsJsonArray("matches").get(0).getAsJsonObject();

        assertEquals("var/json_schema/point.json", match.get("path").getAsString());
        assertEquals("convention", match.get("source").getAsString());
    }

    @Test
    void looksASchemaUpByFileName() {
        addPhysicalFile("var/json_schema/point.json", POINT_SCHEMA);

        JsonObject match = envelope(facts().lookup(null, null, "point.json", null))
            .getAsJsonArray("matches").get(0).getAsJsonObject();

        assertEquals("var/json_schema/point.json", match.get("path").getAsString());
        assertEquals("file", match.get("source").getAsString());
    }

    @Test
    void resolvesAnAttributeSchemaInASubdirectory() {
        addPhysicalFile("src/Resource/App/AdminDemo.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            use BEAR\\Resource\\Annotation\\JsonSchema;
            use BEAR\\Resource\\ResourceObject;

            final class AdminDemo extends ResourceObject
            {
                #[JsonSchema(schema: 'admin/point.json')]
                public function onGet(): static
                {
                    return $this;
                }
            }
            """);
        addPhysicalFile("var/json_schema/admin/point.json", POINT_SCHEMA);

        JsonObject match = envelope(facts().lookup("app://self/admin-demo", null, null, null))
            .getAsJsonArray("matches").get(0).getAsJsonObject();

        assertEquals("var/json_schema/admin/point.json", match.get("path").getAsString());
        assertEquals("attribute", match.get("source").getAsString());
    }

    /**
     * The boundary is the schema directory the name is resolved under, and it is checked against
     * the file the name reached rather than against the spelling of the name: a path that walks
     * out of the directory answers with nothing even though the file it reached is one the
     * project holds.
     */
    @Test
    void rejectsASchemaFileArgumentThatLeavesTheSchemaDirectories() {
        addPhysicalFile("var/json_schema/point.json", POINT_SCHEMA);
        addPhysicalFile("var/elsewhere/point.json", POINT_SCHEMA);

        JsonObject envelope = envelope(facts().lookup(null, null, "../elsewhere/point.json", null));

        assertEquals(0, envelope.getAsJsonArray("matches").size());
    }

    /** A name that walks out and back in names a file the directory does hold, so it answers. */
    @Test
    void acceptsASchemaFileArgumentThatStaysInsideTheSchemaDirectories() {
        addPhysicalFile("var/json_schema/point.json", POINT_SCHEMA);

        JsonObject envelope = envelope(facts().lookup(null, null, "../json_schema/point.json", null));

        assertEquals(1, envelope.getAsJsonArray("matches").size());
    }

    @Test
    void answersWithAnEmptyListWhenNoSchemaExists() {
        addPhysicalFile("src/Resource/App/Point.php", POINT);

        JsonObject envelope = envelope(facts().lookup("app://self/point", null, null, null));

        assertEquals("ok", envelope.get("status").getAsString());
        assertEquals(0, envelope.getAsJsonArray("matches").size());
    }

    @Test
    void reportsAnUnparsableSchemaWithItsPath() {
        addPhysicalFile("var/json_schema/point.json", "{\"properties\": ");

        JsonArray matches = envelope(facts().lookup(null, null, "point.json", null)).getAsJsonArray("matches");

        assertEquals("var/json_schema/point.json", matches.get(0).getAsJsonObject().get("path").getAsString());
        assertNotNull(matches.get(0).getAsJsonObject().get("error"));
    }

    @Test
    void rejectsAnUnknownKind() {
        assertEquals("not_found", envelope(facts().lookup(null, null, "point.json", "sideways")).get("status").getAsString());
    }

    private static JsonObject envelope(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private SchemaFactsService facts() {
        return SchemaFactsService.getInstance(fixture.getProject());
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
