package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import com.intellij.testFramework.fixtures.impl.LightTempDirTestFixtureImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the {@code implementations} half of the transition lookup, which needs the
 * incoming-relation index and therefore the light fixture recipe (see
 * ResourceAttributeIndexServiceFixtureTest); the sibling ALPS test's temp-dir fixture keeps
 * physical files out of the project index.
 */
class AlpsTransitionImplementationsFixtureTest {

    private static final String PROFILE = """
        {
          "alps": {
            "descriptor": [
              {
                "id": "BlogPosting",
                "descriptor": [
                  {"id": "goEntry", "type": "safe", "rt": "#Entry", "rel": "item"}
                ]
              },
              {"id": "Entry"}
            ]
          }
        }
        """;

    private static final String BLOG_POSTING = """
        <?php

        namespace MyVendor\\MyProject\\Resource\\App;

        use BEAR\\Resource\\Annotation\\Link;
        use BEAR\\Resource\\ResourceObject;

        final class BlogPosting extends ResourceObject
        {
            #[Link(rel: 'item', href: 'app://self/entry')]
            public function onGet(): static
            {
                return $this;
            }
        }
        """;

    private static final String ENTRY = """
        <?php

        namespace MyVendor\\MyProject\\Resource\\App;

        use BEAR\\Resource\\ResourceObject;

        final class Entry extends ResourceObject
        {
            public function onGet(): static
            {
                return $this;
            }
        }
        """;

    private CodeInsightTestFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        IdeaTestFixtureFactory factory = IdeaTestFixtureFactory.getFixtureFactory();
        TestFixtureBuilder<IdeaProjectTestFixture> builder = factory.createLightFixtureBuilder(
            LightProjectDescriptor.EMPTY_PROJECT_DESCRIPTOR,
            getClass().getSimpleName()
        );
        fixture = factory.createCodeInsightFixture(builder.getFixture(), new LightTempDirTestFixtureImpl(true));
        fixture.setUp();
    }

    @AfterEach
    void tearDown() throws Exception {
        fixture.tearDown();
    }

    /**
     * A multi-word source id: its kebab spelling is "blog-posting" while the relation index
     * writes "blogposting", and the implementations must survive that difference in spelling.
     */
    @Test
    void reportsTheImplementationsOfATransitionUnderAMultiWordSource() {
        fixture.addFileToProject("alps.json", PROFILE);
        fixture.addFileToProject("src/Resource/App/BlogPosting.php", BLOG_POSTING);
        fixture.addFileToProject("src/Resource/App/Entry.php", ENTRY);

        JsonObject envelope = JsonParser.parseString(
            AlpsFactsService.getInstance(fixture.getProject()).transitionLookup(null, null, "Entry", null)
        ).getAsJsonObject();
        JsonArray transitions = envelope.getAsJsonArray("transitions");

        assertEquals(1, transitions.size(), envelope::toString);
        JsonObject transition = transitions.get(0).getAsJsonObject();
        assertTrue(transition.has("implementations"), transition::toString);
        JsonObject implementation = transition.getAsJsonArray("implementations").get(0).getAsJsonObject();
        assertTrue(
            implementation.get("sourceFqn").getAsString().endsWith("BlogPosting"),
            implementation::toString
        );
        assertEquals("item", implementation.get("rel").getAsString());
    }
}
