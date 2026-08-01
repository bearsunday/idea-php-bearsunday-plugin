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

class ResourceFactsServiceFixtureTest {

    private static final String USER = """
        <?php

        namespace MyVendor\\MyProject\\Resource\\App;

        use BEAR\\Resource\\Annotation\\JsonSchema;
        use BEAR\\Resource\\ResourceObject;

        final class User extends ResourceObject
        {
            #[JsonSchema(schema: 'user.json')]
            public function onGet(int $id, string $name = 'anonymous'): static
            {
                return $this;
            }

            private function helper(): void
            {
            }
        }
        """;

    private static final String DASHBOARD = """
        <?php

        namespace MyVendor\\MyProject\\Resource\\App;

        use BEAR\\Resource\\Annotation\\Embed;
        use BEAR\\Resource\\Annotation\\Link;
        use BEAR\\Resource\\ResourceObject;

        final class Dashboard extends ResourceObject
        {
            #[Embed(src: 'app://self/user{?id}', rel: 'user')]
            #[Link(rel: 'profile', href: 'app://self/profile{?id}')]
            public function onGet(int $id): static
            {
                return $this;
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
    void describesResourceMethodsAndParameters() {
        addPhysicalFile("src/Resource/App/User.php", USER);

        JsonObject envelope = envelope(facts().describe("app://self/user"));
        JsonObject resource = envelope.getAsJsonObject("resource");
        JsonObject method = resource.getAsJsonArray("methods").get(0).getAsJsonObject();
        JsonArray params = method.getAsJsonArray("params");

        assertEquals("ok", envelope.get("status").getAsString());
        assertEquals("\\MyVendor\\MyProject\\Resource\\App\\User", resource.get("classFqn").getAsString());
        assertEquals("src/Resource/App/User.php", resource.get("filePath").getAsString());
        assertEquals("app://self/user", resource.get("uri").getAsString());
        assertEquals(1, resource.getAsJsonArray("methods").size());
        assertEquals("onGet", method.get("name").getAsString());
        assertEquals(2, params.size());
        assertEquals("id", params.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("int", params.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("name", params.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("psi", envelope.getAsJsonObject("provenance").get("source").getAsString());
    }

    @Test
    void reportsTheJsonSchemaAttributeOfAMethod() {
        addPhysicalFile("src/Resource/App/User.php", USER);

        JsonObject method = envelope(facts().describe("app://self/user"))
            .getAsJsonObject("resource").getAsJsonArray("methods").get(0).getAsJsonObject();
        JsonObject attribute = method.getAsJsonArray("attributes").get(0).getAsJsonObject();

        assertEquals(1, method.getAsJsonArray("attributes").size());
        assertTrue(attribute.get("text").getAsString().contains("user.json"));
    }

    @Test
    void listsOutgoingLinkAndEmbedRelations() {
        addPhysicalFile("src/Resource/App/Dashboard.php", DASHBOARD);

        JsonObject resource = envelope(facts().describe("app://self/dashboard")).getAsJsonObject("resource");
        JsonArray relations = resource.getAsJsonArray("relationsOut");
        JsonObject embed = relation(relations, "user");
        JsonObject link = relation(relations, "profile");

        assertEquals(2, relations.size());
        assertEquals("Embed", embed.get("kind").getAsString());
        assertEquals("app://self/user", embed.get("targetUri").getAsString());
        assertEquals("onGet", embed.get("targetMethod").getAsString());
        assertEquals("Link", link.get("kind").getAsString());
        assertEquals("app://self/profile", link.get("targetUri").getAsString());
        assertNotNull(resource.getAsJsonArray("relationsIn"));
    }

    @Test
    void acceptsAPathUriWithoutScheme() {
        addPhysicalFile("src/Resource/App/User.php", USER);

        JsonObject envelope = envelope(facts().describe("/user"));

        assertEquals("ok", envelope.get("status").getAsString());
        assertEquals("app://self/user", envelope.getAsJsonObject("resource").get("uri").getAsString());
    }

    @Test
    void reportsNotFoundForAnUnknownResource() {
        JsonObject envelope = envelope(facts().describe("app://self/missing"));

        assertEquals("not_found", envelope.get("status").getAsString());
        assertNotNull(envelope.get("error"));
    }

    private static JsonObject relation(JsonArray relations, String rel) {
        for (JsonElement element : relations) {
            if (rel.equals(element.getAsJsonObject().get("rel").getAsString())) {
                return element.getAsJsonObject();
            }
        }
        throw new AssertionError("relation not found: " + rel);
    }

    private static JsonObject envelope(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private ResourceFactsService facts() {
        return ResourceFactsService.getInstance(fixture.getProject());
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
