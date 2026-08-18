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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The entry points here are a BEAR app's own, copied rather than invented: the ternary that picks a
 * context by SAPI, the plain literal a test server boots with, and the {@code Injector::getInstance}
 * a test reaches in through.
 */
class AppContextListServiceFixtureTest {

    /** The shape a public entry point boots in, with the context chosen by SAPI. */
    private static final String INDEX = """
        <?php

        use MyVendor\\MyProject\\Bootstrap;

        require dirname(__DIR__) . '/vendor/autoload.php';

        exit((new Bootstrap())(PHP_SAPI === 'cli-server' ? 'hal-app' : 'prod-hal-app', $GLOBALS, $_SERVER));
        """;

    private static final String CONSOLE = """
        <?php

        use MyVendor\\MyProject\\Bootstrap;

        exit((new Bootstrap())('cli-hal-api-app', $GLOBALS, $_SERVER));
        """;

    /** How a test reaches into the app, and how Bootstrap itself does -- one states, one does not. */
    private static final String TEST = """
        <?php

        namespace MyVendor\\MyProject;

        class IndexTest
        {
            public function testOnGet(): void
            {
                $injector = Injector::getInstance('app');
            }
        }
        """;

    private static final String BOOTSTRAP = """
        <?php

        namespace MyVendor\\MyProject;

        final class Bootstrap
        {
            public function __invoke(string $context, array $globals, array $server): int
            {
                $app = Injector::getInstance($context);

                return 0;
            }
        }
        """;

    /** A call on something else entirely: it names a string, and the string is no context. */
    private static final String OTHER = """
        <?php

        namespace MyVendor\\MyProject;

        class Other
        {
            public function run(): void
            {
                (new Formatter())('not-a-context', []);
                Registry::getInstance('also-not-a-context');
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
     * The failure this exists for: a context is a string no file declares, so a caller asking about
     * a context has had to guess one from the naming convention.
     */
    @Test
    void namesTheContextsTheAppBootsUnder() {
        addFile("public/index.php", INDEX);
        addFile("bin/app.php", CONSOLE);
        addFile("tests/IndexTest.php", TEST);

        JsonObject envelope = envelope(list());

        assertEquals("ok", envelope.get("status").getAsString(), envelope::toString);
        // Entry points first, in the order the roots are read: public, then bin, then tests.
        assertEquals(
            List.of("hal-app", "prod-hal-app", "cli-hal-api-app", "app"),
            contexts(envelope)
        );
    }

    /**
     * A ternary states two contexts, and its condition states neither: the string it compares
     * PHP_SAPI against is what the entry point tests to choose one, not a context to offer.
     */
    @Test
    void doesNotReadTheConditionOfATernaryAsAContext() {
        addFile("public/index.php", INDEX);

        List<String> contexts = contexts(envelope(list()));

        assertEquals(List.of("hal-app", "prod-hal-app"), contexts);
        assertFalse(contexts.contains("cli-server"), contexts::toString);
    }

    /** Where a context is written is the next thing anyone asks, so the answer carries it. */
    @Test
    void saysWhereEachContextIsWritten() {
        addFile("public/index.php", INDEX);

        JsonObject context = envelope(list()).getAsJsonArray("contexts").get(0).getAsJsonObject();
        JsonObject site = context.getAsJsonArray("writtenIn").get(0).getAsJsonObject();

        assertEquals("hal-app", context.get("context").getAsString());
        assertEquals("public/index.php", site.get("filePath").getAsString());
        assertEquals(7, site.get("line").getAsInt(), site::toString);
    }

    /**
     * Bootstrap names its context with a variable, which states no context at all. Counting it is
     * the difference between "these are the contexts" and "these are the ones I could read".
     */
    @Test
    void countsAContextItCouldNotRead() {
        addFile("src/Bootstrap.php", BOOTSTRAP);

        JsonObject envelope = envelope(list());

        assertTrue(contexts(envelope).isEmpty(), envelope::toString);
        assertEquals(1, envelope.getAsJsonObject("scan").get("argumentsUnreadable").getAsInt(), envelope::toString);
    }

    /** Only the two shapes an app names a context in; a call that is neither names none. */
    @Test
    void readsNoContextFromACallThatNamesNone() {
        addFile("src/Other.php", OTHER);

        JsonObject envelope = envelope(list());

        assertTrue(contexts(envelope).isEmpty(), envelope::toString);
        // Not read is not the same as unreadable: neither call is one this was looking at.
        assertFalse(envelope.getAsJsonObject("scan").has("argumentsUnreadable"), envelope::toString);
    }

    /** vendor is left out: the framework's own fixtures name contexts that are no app's. */
    @Test
    void leavesVendorOutOfTheScan() {
        addFile("vendor/bear/package/tests/Fake/index.php", CONSOLE);

        assertTrue(contexts(envelope(list())).isEmpty(), () -> list());
    }

    /** The panel offers these, so the names alone are answered for it in the same order. */
    @Test
    void answersTheNamesOnTheirOwnForAChooser() {
        addFile("public/index.php", INDEX);
        addFile("tests/IndexTest.php", TEST);

        assertEquals(
            List.of("hal-app", "prod-hal-app", "app"),
            AppContextListService.getInstance(fixture.getProject()).names()
        );
    }

    private String list() {
        return AppContextListService.getInstance(fixture.getProject()).list();
    }

    private static List<String> contexts(JsonObject envelope) {
        List<String> names = new ArrayList<>();
        JsonArray contexts = envelope.getAsJsonArray("contexts");
        for (var element : contexts) {
            names.add(element.getAsJsonObject().get("context").getAsString());
        }

        return names;
    }

    private static JsonObject envelope(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private void addFile(String relativePath, String contents) {
        fixture.addFileToProject(relativePath, contents);
    }
}
