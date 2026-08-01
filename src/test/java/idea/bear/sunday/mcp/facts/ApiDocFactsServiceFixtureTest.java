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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiDocFactsServiceFixtureTest {

    private static final String APIDOC = """
        <?xml version="1.0" encoding="UTF-8"?>
        <apidoc>
            <appName>MyVendor\\MyProject</appName>
            <scheme>app</scheme>
            <docDir>docs</docDir>
            <format>html,openapi</format>
        </apidoc>
        """;

    private static final String OPENAPI = """
        {
          "openapi": "3.0.0",
          "paths": {
            "/point": {
              "get": {"operationId": "getPoint", "summary": "A point"},
              "post": {"operationId": "postPoint"}
            }
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
    void findsAnOperationByPathAndMethod() {
        addPhysicalFile("apidoc.xml", APIDOC);
        addPhysicalFile("docs/openapi.json", OPENAPI);

        JsonObject envelope = envelope(facts().operationLookup("/point", "get", null));
        JsonObject operation = envelope.getAsJsonArray("operations").get(0).getAsJsonObject();

        assertEquals("ok", envelope.get("status").getAsString());
        assertEquals(1, envelope.getAsJsonArray("operations").size());
        assertEquals("/point", operation.get("path").getAsString());
        assertEquals("get", operation.get("method").getAsString());
        assertEquals("#/paths/~1point/get", operation.get("jsonPointer").getAsString());
        assertEquals("A point", operation.getAsJsonObject("operation").get("summary").getAsString());
        assertEquals("docs/openapi.json", envelope.getAsJsonObject("provenance").get("path").getAsString());
    }

    @Test
    void findsAnOperationByOperationId() {
        addPhysicalFile("apidoc.xml", APIDOC);
        addPhysicalFile("docs/openapi.json", OPENAPI);

        JsonArray operations = envelope(facts().operationLookup(null, null, "postPoint")).getAsJsonArray("operations");

        assertEquals(1, operations.size());
        assertEquals("post", operations.get(0).getAsJsonObject().get("method").getAsString());
    }

    @Test
    void listsEveryOperationWithoutItsBodyWhenNoFilterIsGiven() {
        addPhysicalFile("apidoc.xml", APIDOC);
        addPhysicalFile("docs/openapi.json", OPENAPI);

        JsonArray operations = envelope(facts().operationLookup(null, null, null)).getAsJsonArray("operations");

        assertEquals(2, operations.size());
        assertFalse(operations.get(0).getAsJsonObject().has("operation"));
        assertTrue(operations.get(0).getAsJsonObject().has("jsonPointer"));
    }

    @Test
    void readsTheConventionalLocationWithoutApiDocXml() {
        addPhysicalFile("docs/openapi.json", OPENAPI);

        assertEquals("ok", envelope(facts().operationLookup("/point", "get", null)).get("status").getAsString());
    }

    @Test
    void reportsEngineUnavailableWhenTheDocumentWasNeverGenerated() {
        JsonObject envelope = envelope(facts().operationLookup("/point", "get", null));

        assertEquals("engine_unavailable", envelope.get("status").getAsString());
        assertTrue(envelope.get("error").getAsString().contains("docs/openapi.json"));
    }

    @Test
    void returnsNoOperationForAnUnknownPath() {
        addPhysicalFile("docs/openapi.json", OPENAPI);

        JsonObject envelope = envelope(facts().operationLookup("/missing", null, null));

        assertEquals("ok", envelope.get("status").getAsString());
        assertEquals(0, envelope.getAsJsonArray("operations").size());
    }

    private static JsonObject envelope(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private ApiDocFactsService facts() {
        return ApiDocFactsService.getInstance(fixture.getProject());
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
