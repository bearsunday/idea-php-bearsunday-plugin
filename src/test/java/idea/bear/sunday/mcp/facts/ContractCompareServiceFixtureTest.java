package idea.bear.sunday.mcp.facts;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractCompareServiceFixtureTest {

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

    private static final String POINT_WITH_BODY = """
        <?php

        namespace MyVendor\\MyProject\\Resource\\App;

        use BEAR\\Resource\\ResourceObject;

        final class Point extends ResourceObject
        {
            public function onGet(int $x = 0): static
            {
                $this->body = ['x' => 1, 'w' => 'west'];

                return $this;
            }
        }
        """;

    private static final String POINT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "x": {"type": "integer"},
            "y": {"type": "integer"},
            "z": {"type": "integer"}
          },
          "required": ["x", "y"]
        }
        """;

    private static final String PROFILE = """
        {
          "alps": {
            "descriptor": [
              {
                "id": "Point",
                "descriptor": [
                  {"id": "x", "type": "semantic"},
                  {"id": "y", "type": "semantic"},
                  {"id": "goPoint", "type": "safe", "rt": "#Point"},
                  {"href": "#goNext"}
                ]
              },
              {"id": "goNext", "type": "safe", "rt": "#Point"}
            ]
          }
        }
        """;

    private static final String PROFILE_WITH_DANGLING_HREF = """
        {
          "alps": {
            "descriptor": [
              {
                "id": "Point",
                "descriptor": [
                  {"id": "x", "type": "semantic"},
                  {"href": "#nope"}
                ]
              }
            ]
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
    void reportsTheFieldsOnlyOneSideDeclares() {
        addPhysicalFile("src/Resource/App/Point.php", POINT);
        addPhysicalFile("var/json_schema/point.json", POINT_SCHEMA);
        addPhysicalFile("alps.json", PROFILE);

        JsonObject envelope = envelope(facts().compare("app://self/point", "get"));

        assertEquals("ok", envelope.get("status").getAsString());
        assertEquals("presence-only", envelope.get("kind").getAsString());
        assertEquals("app://self/point", envelope.get("uri").getAsString());
        // A present side says so explicitly, so a caller keying on `available` reads all three
        // sides the same way.
        assertTrue(envelope.getAsJsonObject("schema").get("available").getAsBoolean());
        assertTrue(envelope.getAsJsonObject("alps").get("available").getAsBoolean());
        assertEquals("var/json_schema/point.json", envelope.getAsJsonObject("schema").get("path").getAsString());
        assertEquals("[\"x\",\"y\",\"z\"]", envelope.getAsJsonObject("schema").getAsJsonArray("fields").toString());
        assertEquals("Point", envelope.getAsJsonObject("alps").get("descriptorId").getAsString());
        assertEquals("alps.json", envelope.getAsJsonObject("alps").get("profilePath").getAsString());
        assertEquals("[\"x\",\"y\"]", envelope.getAsJsonObject("alps").getAsJsonArray("fields").toString());
        assertEquals("[\"z\"]", envelope.getAsJsonArray("onlyInSchema").toString());
        assertEquals("[]", envelope.getAsJsonArray("onlyInAlps").toString());
        assertFalse(envelope.has("onlyInBody"));
    }

    @Test
    void reportsTheFieldsOnlyOneOfTheThreeSidesDeclares() {
        addPhysicalFile("src/Resource/App/Point.php", POINT_WITH_BODY);
        addPhysicalFile("var/json_schema/point.json", POINT_SCHEMA);
        addPhysicalFile("alps.json", PROFILE);

        JsonObject envelope = envelope(facts().compare("app://self/point", "get"));

        assertEquals("ok", envelope.get("status").getAsString());
        JsonObject body = envelope.getAsJsonObject("body");
        assertTrue(body.get("available").getAsBoolean());
        assertEquals("[\"x\",\"w\"]", body.getAsJsonArray("fields").toString());
        assertEquals("[\"z\"]", envelope.getAsJsonArray("onlyInSchema").toString());
        assertEquals("[]", envelope.getAsJsonArray("onlyInAlps").toString());
        assertEquals("[\"w\"]", envelope.getAsJsonArray("onlyInBody").toString());
    }

    @Test
    void reportsTheAlpsSideAsUnavailableWithoutAProfile() {
        addPhysicalFile("src/Resource/App/Point.php", POINT);
        addPhysicalFile("var/json_schema/point.json", POINT_SCHEMA);

        JsonObject envelope = envelope(facts().compare("app://self/point", "get"));

        assertEquals("ok", envelope.get("status").getAsString());
        assertFalse(envelope.getAsJsonObject("alps").get("available").getAsBoolean());
        assertNotNull(envelope.getAsJsonObject("schema").get("path"));
        assertFalse(envelope.has("onlyInSchema"));
    }

    @Test
    void reportsBothSidesAsUnavailableForAnUnknownResource() {
        JsonObject envelope = envelope(facts().compare("app://self/point", null));

        assertEquals("ok", envelope.get("status").getAsString());
        assertFalse(envelope.getAsJsonObject("schema").get("available").getAsBoolean());
        assertFalse(envelope.getAsJsonObject("alps").get("available").getAsBoolean());
        assertEquals("get", envelope.get("method").getAsString());
    }

    @Test
    void reportsTheBodySideAsUnavailableWhenTheMethodAssignsNoBody() {
        addPhysicalFile("src/Resource/App/Point.php", POINT);

        JsonObject body = envelope(facts().compare("app://self/point", null)).getAsJsonObject("body");

        assertFalse(body.get("available").getAsBoolean());
        assertTrue(body.get("reason").getAsString().contains("No body declaration for method get"));
    }

    @Test
    void doesNotReportAnUnresolvedReferenceAsAnAlpsField() {
        addPhysicalFile("src/Resource/App/Point.php", POINT);
        addPhysicalFile("alps.json", PROFILE_WITH_DANGLING_HREF);

        JsonObject alps = envelope(facts().compare("app://self/point", "get")).getAsJsonObject("alps");

        assertEquals("[\"x\"]", alps.getAsJsonArray("fields").toString());
    }

    @Test
    void namesTheMethodTheWayTheBodyShapeToolDoes() {
        addPhysicalFile("src/Resource/App/Point.php", POINT_WITH_BODY);

        JsonObject envelope = envelope(facts().compare("app://self/point", "onGet"));

        assertEquals("get", envelope.get("method").getAsString());
        assertTrue(envelope.getAsJsonObject("body").get("available").getAsBoolean());
    }

    private static JsonObject envelope(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private ContractCompareService facts() {
        return ContractCompareService.getInstance(fixture.getProject());
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
