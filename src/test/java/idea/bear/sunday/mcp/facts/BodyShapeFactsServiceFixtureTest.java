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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyShapeFactsServiceFixtureTest {

    private static final String ARTICLE = """
        <?php

        namespace MyVendor\\MyProject\\Resource\\App;

        use BEAR\\Resource\\ResourceObject;

        final class Article extends ResourceObject
        {
            public function onGet(): static
            {
                $this->body = ['id' => 1, 'title' => 'x'];

                return $this;
            }

            public function onPost(bool $draft): static
            {
                if ($draft) {
                    $this->body = ['id' => 1];
                } else {
                    $this->body = ['error' => 'nope'];
                }

                return $this;
            }

            public function onPut(): static
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
    void describesTheShapeOfAnAssignedBody() {
        addPhysicalFile("src/Resource/App/Article.php", ARTICLE);

        JsonObject envelope = envelope(facts().shape("app://self/article", "get"));

        assertEquals("ok", envelope.get("status").getAsString());
        JsonObject bodyShape = envelope.getAsJsonObject("bodyShape");
        assertEquals("app://self/article", bodyShape.get("uri").getAsString());
        assertEquals("get", bodyShape.get("method").getAsString());
        assertEquals("array{id: int, title: string}", bodyShape.get("rendered").getAsString());
        assertEquals("array{id: int, title: string}", bodyShape.get("formatted").getAsString());
        assertEquals(
            "[{\"key\":\"id\",\"type\":\"int\"},{\"key\":\"title\",\"type\":\"string\"}]",
            bodyShape.getAsJsonArray("fields").toString()
        );
        JsonObject provenance = envelope.getAsJsonObject("provenance");
        assertEquals("psi", provenance.get("source").getAsString());
        assertEquals("src/Resource/App/Article.php", provenance.get("path").getAsString());
    }

    /** PHP compares method names case-insensitively, so every spelling of onGet names onGet. */
    @Test
    void readsEverySpellingOfAMethodName() {
        addPhysicalFile("src/Resource/App/Article.php", ARTICLE);

        for (String method : List.of("get", "GET", "onGet", "onget", "ONGET")) {
            JsonObject bodyShape = envelope(facts().shape("app://self/article", method)).getAsJsonObject("bodyShape");

            assertEquals("get", bodyShape.get("method").getAsString(), method);
            assertEquals("array{id: int, title: string}", bodyShape.get("rendered").getAsString(), method);
        }
    }

    @Test
    void reportsABodyBuiltDifferentlyPerPathAsBranches() {
        addPhysicalFile("src/Resource/App/Article.php", ARTICLE);

        JsonObject envelope = envelope(facts().shape("app://self/article", "post"));

        assertEquals("ok", envelope.get("status").getAsString());
        JsonObject bodyShape = envelope.getAsJsonObject("bodyShape");
        assertEquals("array{id: int}|array{error: string}", bodyShape.get("rendered").getAsString());
        assertFalse(bodyShape.has("fields"));

        JsonArray branches = bodyShape.getAsJsonArray("branches");
        assertEquals(2, branches.size());
        assertEquals("array{id: int}", branches.get(0).getAsJsonObject().get("rendered").getAsString());
        assertEquals(
            "[{\"key\":\"id\",\"type\":\"int\"}]",
            branches.get(0).getAsJsonObject().getAsJsonArray("fields").toString()
        );
        assertEquals(
            "[{\"key\":\"error\",\"type\":\"string\"}]",
            branches.get(1).getAsJsonObject().getAsJsonArray("fields").toString()
        );
    }

    @Test
    void answersNotFoundForAnUnknownResource() {
        JsonObject envelope = envelope(facts().shape("app://self/missing", "get"));

        assertEquals("not_found", envelope.get("status").getAsString());
        assertTrue(envelope.get("error").getAsString().contains("Resource class not found"));
    }

    @Test
    void answersNotFoundForAMethodThatAssignsNoBody() {
        addPhysicalFile("src/Resource/App/Article.php", ARTICLE);

        JsonObject envelope = envelope(facts().shape("app://self/article", "put"));

        assertEquals("not_found", envelope.get("status").getAsString());
        assertTrue(envelope.get("error").getAsString().contains("No body declaration for method put"));
    }

    private static JsonObject envelope(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private BodyShapeFactsService facts() {
        return BodyShapeFactsService.getInstance(fixture.getProject());
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
