package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.testFramework.DumbModeTestUtils;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the {@code implementations} half of the transition lookup, which needs the
 * incoming-relation index and therefore the light fixture recipe (see
 * ResourceAttributeIndexServiceFixtureTest); the sibling ALPS test's temp-dir fixture keeps
 * physical files out of the project index.
 *
 * <p>The profile is shaped the way real ones are: the transition is defined at the top level with
 * no {@code rel} attribute, and the state that offers it names it by reference.
 */
class AlpsTransitionImplementationsFixtureTest {

    private static final String PROFILE = """
        {
          "alps": {
            "descriptor": [
              {"id": "Entry"},
              {
                "id": "BlogPosting",
                "descriptor": [
                  {"href": "#goEntry"}
                ]
              },
              {"id": "goEntry", "type": "safe", "rt": "#Entry"}
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
            #[Link(rel: 'goEntry', href: 'app://self/entry')]
            public function onGet(): static
            {
                return $this;
            }
        }
        """;

    /** The same transition reached with every near-miss spelling of its id, and no exact one. */
    private static final String NEAR_MISS = """
        <?php

        namespace MyVendor\\MyProject\\Resource\\App;

        use BEAR\\Resource\\Annotation\\Link;
        use BEAR\\Resource\\ResourceObject;

        final class NearMiss extends ResourceObject
        {
            #[Link(rel: 'go-entry', href: 'app://self/entry')]
            #[Link(rel: 'GoEntry', href: 'app://self/entry')]
            #[Link(rel: 'entry', href: 'app://self/entry')]
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

    private void addProfileAndResources() {
        fixture.addFileToProject("alps.json", PROFILE);
        fixture.addFileToProject("src/Resource/App/BlogPosting.php", BLOG_POSTING);
        fixture.addFileToProject("src/Resource/App/Entry.php", ENTRY);
    }

    private JsonObject lookup(String from, String rel, String rt) {
        return JsonParser.parseString(
            AlpsFactsService.getInstance(fixture.getProject()).transitionLookup(from, rel, rt, null)
        ).getAsJsonObject();
    }

    /**
     * The state names the transition by {@code {"href": "#goEntry"}}, which is the form real
     * profiles use, so the containing state is what {@code from} has to filter on. The source id
     * is multi-word as well: its kebab spelling is "blog-posting" while the relation index writes
     * "blogposting", and the match must survive that difference.
     */
    @Test
    void findsATransitionAStateReachesByHrefAndTheRelationThatImplementsIt() {
        addProfileAndResources();

        JsonObject envelope = lookup("BlogPosting", null, null);
        JsonArray transitions = envelope.getAsJsonArray("transitions");

        assertEquals(1, transitions.size(), envelope::toString);
        JsonObject transition = transitions.get(0).getAsJsonObject();
        assertEquals("goEntry", transition.get("id").getAsString());
        assertEquals("BlogPosting", transition.get("from").getAsString());
        assertEquals("href", transition.get("via").getAsString());
        assertTrue(transition.has("implementations"), transition::toString);
        JsonObject implementation = transition.getAsJsonArray("implementations").get(0).getAsJsonObject();
        assertTrue(implementation.get("sourceFqn").getAsString().endsWith("BlogPosting"), implementation::toString);
        assertEquals("goEntry", implementation.get("rel").getAsString());
    }

    /** A transition a state reaches by href is reported under that state, not twice. */
    @Test
    void listsATransitionOnceWhenAStateNamesItByReference() {
        addProfileAndResources();

        JsonObject envelope = lookup(null, null, null);
        JsonArray transitions = envelope.getAsJsonArray("transitions");

        assertEquals(1, transitions.size(), envelope::toString);
        assertEquals("BlogPosting", transitions.get(0).getAsJsonObject().get("from").getAsString());
    }

    /**
     * A transition id is an opaque identifier: only a rel that spells it exactly implements it.
     * Kebab, case and the {@code go} prefix are habits of the convention, not part of the id, and
     * folding any of them in would attribute one resource's relation to another's transition.
     */
    @Test
    void doesNotMatchARelThatOnlyResemblesTheTransitionId() {
        fixture.addFileToProject("alps.json", PROFILE);
        fixture.addFileToProject("src/Resource/App/NearMiss.php", NEAR_MISS);
        fixture.addFileToProject("src/Resource/App/Entry.php", ENTRY);

        JsonObject transition = lookup("BlogPosting", null, null)
            .getAsJsonArray("transitions").get(0).getAsJsonObject();

        assertFalse(transition.has("implementations"), transition::toString);
    }

    /**
     * The profile is a file, the relations are an index: while the index is building, the
     * transition still answers for what the profile says and drops only the implementations.
     */
    @Test
    void answersFromTheProfileWhileTheRelationIndexIsStillBuilding() {
        addProfileAndResources();

        DumbModeTestUtils.runInDumbModeSynchronously(fixture.getProject(), () -> {
            JsonObject envelope = lookup("BlogPosting", null, null);
            JsonArray transitions = envelope.getAsJsonArray("transitions");

            assertEquals(1, transitions.size(), envelope::toString);
            JsonObject transition = transitions.get(0).getAsJsonObject();
            assertEquals("goEntry", transition.get("id").getAsString());
            assertFalse(transition.has("implementations"), transition::toString);
            assertEquals("index_not_ready", transition.get("implementationsUnavailable").getAsString());
        });
    }
}
