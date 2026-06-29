package idea.bear.sunday.jsonschema;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiElement;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class JsonSchemaBodyCompletionFixtureTest {

    private CodeInsightTestFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        IdeaTestFixtureFactory factory = IdeaTestFixtureFactory.getFixtureFactory();
        TestFixtureBuilder<IdeaProjectTestFixture> builder =
            factory.createFixtureBuilder(getClass().getSimpleName());
        fixture = factory.createCodeInsightFixture(builder.getFixture(), factory.createTempDirTestFixture());
        fixture.setUp();
    }

    @AfterEach
    void tearDown() throws Exception {
        fixture.tearDown();
    }

    @Test
    void completesBodyKeysFromGetForm() {
        addUserResource("#[JsonSchema('user.json')]");
        List<String> keys = keysAtCaret("""
            <?php
            $resource->get('app://self/user', ['id' => 1])->body['<caret>'];
            """);

        Set<String> names = Set.copyOf(keys);
        assertTrue(names.contains("name"));
        assertTrue(names.contains("age"));
        assertEquals(2, names.size());
    }

    @Test
    void completesBodyKeysFromWithQueryChainForm() {
        addUserResource("#[JsonSchema('user.json')]");
        List<String> keys = keysAtCaret("""
            <?php
            $resource->get->uri('app://self/user')->withQuery(['id' => 1])->body['<caret>'];
            """);

        assertTrue(Set.copyOf(keys).containsAll(Set.of("name", "age")));
    }

    @Test
    void completesBodyKeysFromInvokeForm() {
        addUserResource("#[JsonSchema('user.json')]");
        List<String> keys = keysAtCaret("""
            <?php
            $resource->uri('app://self/user')(['id' => 1])->body['<caret>'];
            """);

        assertTrue(Set.copyOf(keys).containsAll(Set.of("name", "age")));
    }

    @Test
    void completesBodyKeysFromNamedSchemaArgument() {
        addUserResource("#[JsonSchema(schema: 'user.json')]");
        List<String> keys = keysAtCaret("""
            <?php
            $resource->get('app://self/user', ['id' => 1])->body['<caret>'];
            """);

        assertTrue(Set.copyOf(keys).containsAll(Set.of("name", "age")));
    }

    @Test
    void returnsNothingWhenResourceHasNoJsonSchema() {
        addUserResource("");
        List<String> keys = keysAtCaret("""
            <?php
            $resource->get('app://self/user', ['id' => 1])->body['<caret>'];
            """);

        assertTrue(keys.isEmpty());
    }

    @Test
    void returnsNothingWhenUriDoesNotResolve() {
        addUserResource("#[JsonSchema('user.json')]");
        List<String> keys = keysAtCaret("""
            <?php
            $resource->get('app://self/missing', ['id' => 1])->body['<caret>'];
            """);

        assertTrue(keys.isEmpty());
    }

    private void addUserResource(String jsonSchemaAttribute) {
        fixture.addFileToProject("src/Resource/App/User.php", """
            <?php
            namespace MyVendor\\Todo\\Resource\\App;

            class User
            {
                %s
                public function onGet(int $id): array
                {
                    return [];
                }
            }
            """.formatted(jsonSchemaAttribute));

        fixture.addFileToProject("var/json_schema/user.json", """
            {
              "$schema": "http://json-schema.org/draft-04/schema#",
              "type": "object",
              "properties": {
                "name": {"type": "string"},
                "age": {"type": "integer"}
              }
            }
            """);
    }

    private List<String> keysAtCaret(String caller) {
        fixture.configureByText("Caller.php", caller);
        return ApplicationManager.getApplication().runReadAction(
            (Computable<List<String>>) () -> {
                PsiElement element = fixture.getFile().findElementAt(fixture.getCaretOffset());
                assertNotNull(element);
                return JsonSchemaBodyCompletionProvider.bodyKeys(element, null);
            }
        );
    }
}