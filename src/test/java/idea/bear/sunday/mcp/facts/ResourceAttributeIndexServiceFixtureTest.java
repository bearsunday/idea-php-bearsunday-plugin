package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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

class ResourceAttributeIndexServiceFixtureTest {

    private static final String USER = """
        <?php

        namespace MyVendor\\MyProject\\Resource\\App;

        use BEAR\\RepositoryModule\\Annotation\\Cacheable;
        use BEAR\\Resource\\Annotation\\JsonSchema;
        use BEAR\\Resource\\ResourceObject;
        use MyVendor\\MyProject\\Annotation\\Audited;

        #[Cacheable]
        final class User extends ResourceObject
        {
            #[Audited]
            #[JsonSchema(schema: 'user.json')]
            public function onGet(int $id): static
            {
                return $this;
            }

            public function onPost(string $name): static
            {
                return $this;
            }
        }
        """;

    /** A resource that carries no attribute at all: counted as scanned, reported in no entry. */
    private static final String PROFILE = """
        <?php

        namespace MyVendor\\MyProject\\Resource\\App;

        use BEAR\\Resource\\ResourceObject;

        final class Profile extends ResourceObject
        {
            public function onGet(): static
            {
                return $this;
            }
        }
        """;

    /** Writes the same {@code #[Audited]} text as {@link #USER}, aliased to a different class. */
    private static final String REPORT = """
        <?php

        namespace MyVendor\\MyProject\\Resource\\App;

        use BEAR\\Resource\\ResourceObject;
        use MyVendor\\MyProject\\Other\\Audited;

        final class Report extends ResourceObject
        {
            #[Audited]
            public function onGet(): static
            {
                return $this;
            }
        }
        """;

    private static final String ABSTRACT_ITEM = """
        <?php

        namespace MyVendor\\MyProject\\Resource\\App;

        use BEAR\\Resource\\ResourceObject;

        abstract class AbstractItem extends ResourceObject
        {
            public function onGet(int $id): static
            {
                return $this;
            }
        }
        """;

    /** Declares no method of its own: every on* method comes from {@link #ABSTRACT_ITEM}. */
    private static final String ITEM = """
        <?php

        namespace MyVendor\\MyProject\\Resource\\App;

        use BEAR\\RepositoryModule\\Annotation\\Cacheable;

        #[Cacheable]
        final class Item extends AbstractItem
        {
        }
        """;

    /** Declares a trait before the resource class, so the first class-like node is not the resource. */
    private static final String ORDER = """
        <?php

        namespace MyVendor\\MyProject\\Resource\\App;

        use BEAR\\Resource\\ResourceObject;
        use MyVendor\\MyProject\\Annotation\\Audited;

        trait OrderAssertions
        {
        }

        final class Order extends ResourceObject
        {
            #[Audited]
            public function onGet(): static
            {
                return $this;
            }
        }
        """;

    private static final String AOP_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use MyVendor\\MyProject\\Annotation\\Audited;
        use MyVendor\\MyProject\\Interceptor\\AuditInterceptor;
        use Ray\\Di\\AbstractModule;

        final class AopModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bindInterceptor(
                    $this->matcher->any(),
                    $this->matcher->annotatedWith(Audited::class),
                    [AuditInterceptor::class],
                );
            }
        }
        """;

    private CodeInsightTestFixture fixture;

    /**
     * The light fixture recipe {@code BasePlatformTestCase} uses. {@link LightTempDirTestFixtureImpl}
     * writes into the light module's own source root, so the added files are both where
     * {@code ProjectUtil.guessProjectDir} points and inside the project index the interceptor
     * bindings are read from. The temp-dir fixture the sibling fact tests use satisfies only the
     * first of those.
     */
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

    @Test
    void listsTheAttributesOfAClassAndOfItsMethods() {
        addFile("src/Resource/App/User.php", USER);

        JsonObject envelope = envelope(index(null, null, null));
        JsonObject classEntry = entry(envelope, "app://self/user", "class", null);
        JsonObject methodEntry = entry(envelope, "app://self/user", "method", "onGet");

        assertEquals("ok", envelope.get("status").getAsString());
        assertEquals("src/Resource/App/User.php", classEntry.get("filePath").getAsString());
        assertEquals("\\MyVendor\\MyProject\\Resource\\App\\User", classEntry.get("classFqn").getAsString());
        assertEquals(
            "\\BEAR\\RepositoryModule\\Annotation\\Cacheable",
            attribute(classEntry, "Cacheable").get("fqn").getAsString()
        );
        assertEquals(2, methodEntry.getAsJsonArray("attributes").size());
        assertEquals(
            "\\MyVendor\\MyProject\\Annotation\\Audited",
            attribute(methodEntry, "Audited").get("fqn").getAsString()
        );
        // onPost carries no attribute, so it is not an entry at all.
        assertEquals(2, envelope.getAsJsonArray("entries").size(), envelope::toString);
    }

    @Test
    void reportsTheArgumentTextOfAnAttribute() {
        addFile("src/Resource/App/User.php", USER);

        JsonObject methodEntry = entry(envelope(index(null, null, null)), "app://self/user", "method", "onGet");

        assertEquals("schema: 'user.json'", attribute(methodEntry, "JsonSchema").get("argsText").getAsString());
        assertFalse(attribute(methodEntry, "Audited").has("argsText"));
    }

    @Test
    void countsAClassThatCarriesNoAttribute() {
        addFile("src/Resource/App/User.php", USER);
        addFile("src/Resource/App/Profile.php", PROFILE);

        JsonObject envelope = envelope(index(null, null, null));
        JsonObject scan = envelope.getAsJsonObject("scan");

        assertEquals("src/Resource", scan.get("resourceRoot").getAsString());
        assertEquals(2, scan.get("files").getAsInt());
        assertEquals(2, scan.get("classes").getAsInt());
        assertTrue(uris(envelope).contains("app://self/user"));
        assertFalse(uris(envelope).contains("app://self/profile"));
    }

    @Test
    void filtersByShortName() {
        addFile("src/Resource/App/User.php", USER);

        JsonArray entries = envelope(index("Audited", null, null)).getAsJsonArray("entries");
        JsonObject entry = entries.get(0).getAsJsonObject();

        assertEquals(1, entries.size());
        assertEquals("onGet", entry.get("method").getAsString());
        assertEquals(1, entry.getAsJsonArray("attributes").size());
        assertEquals("Audited", entry.getAsJsonArray("attributes").get(0).getAsJsonObject().get("name").getAsString());
    }

    /**
     * The point of the tool: {@code #[Audited]} is written the same way in both files but aliased
     * to a different class in each, so a class-name filter keeps one and a text search would keep
     * both.
     */
    @Test
    void filtersByTheResolvedClassRatherThanByTheTextWritten() {
        addFile("src/Resource/App/User.php", USER);
        addFile("src/Resource/App/Report.php", REPORT);

        JsonObject byShortName = envelope(index("Audited", null, null));
        JsonObject byClassName = envelope(index("\\MyVendor\\MyProject\\Annotation\\Audited", null, null));

        assertEquals(List.of("app://self/report", "app://self/user"), uris(byShortName).stream().sorted().toList());
        assertEquals(List.of("app://self/user"), uris(byClassName));
        assertEquals(
            "\\MyVendor\\MyProject\\Other\\Audited",
            attribute(entry(byShortName, "app://self/report", "method", "onGet"), "Audited").get("fqn").getAsString()
        );
    }

    @Test
    void filtersByMethodAndThenLeavesTheClassAttributesOut() {
        addFile("src/Resource/App/User.php", USER);

        JsonArray entries = envelope(index(null, "get", null)).getAsJsonArray("entries");

        assertEquals(1, entries.size());
        assertEquals("method", entries.get(0).getAsJsonObject().get("target").getAsString());
        assertEquals("onGet", entries.get(0).getAsJsonObject().get("method").getAsString());
    }

    /** PHP compares method names case-insensitively, so every spelling of onGet names onGet. */
    @Test
    void acceptsEverySpellingOfAMethodName() {
        addFile("src/Resource/App/User.php", USER);

        for (String method : List.of("get", "GET", "onGet", "onget", "ONGET")) {
            JsonArray entries = envelope(index(null, method, null)).getAsJsonArray("entries");

            assertEquals(1, entries.size(), method);
            assertEquals("onGet", entries.get(0).getAsJsonObject().get("method").getAsString(), method);
        }
    }

    /** A resource may declare no method of its own; its class attributes still apply to it. */
    @Test
    void reportsTheClassAttributesOfAResourceWhoseMethodsAreAllInherited() {
        addFile("src/Resource/App/AbstractItem.php", ABSTRACT_ITEM);
        addFile("src/Resource/App/Item.php", ITEM);

        JsonObject envelope = envelope(index("Cacheable", null, null));
        JsonObject entry = entry(envelope, "app://self/item", "class", null);

        assertEquals(1, envelope.getAsJsonArray("entries").size(), envelope::toString);
        assertEquals("src/Resource/App/Item.php", entry.get("filePath").getAsString());
    }

    /** The first class-like node in a file is not always the resource class. */
    @Test
    void looksPastATraitDeclaredBeforeTheResourceClass() {
        addFile("src/Resource/App/Order.php", ORDER);

        JsonObject envelope = envelope(index("Audited", null, null));

        assertEquals(
            "\\MyVendor\\MyProject\\Resource\\App\\Order",
            entry(envelope, "app://self/order", "method", "onGet").get("classFqn").getAsString()
        );
    }

    @Test
    void reportsTheInterceptorsBoundToAnAttribute() {
        addFile("src/Resource/App/User.php", USER);
        addFile("src/Module/AopModule.php", AOP_MODULE);

        JsonObject envelope = envelope(index("Audited", null, null));
        JsonObject audited = attribute(entry(envelope, "app://self/user", "method", "onGet"), "Audited");

        assertFalse(envelope.has("interceptorsUnavailable"), envelope::toString);
        assertEquals(
            List.of("\\MyVendor\\MyProject\\Interceptor\\AuditInterceptor"),
            strings(audited.getAsJsonArray("interceptors")),
            envelope::toString
        );
    }

    @Test
    void reportsNoInterceptorForAnUnboundAttribute() {
        addFile("src/Resource/App/User.php", USER);
        addFile("src/Module/AopModule.php", AOP_MODULE);

        JsonObject envelope = envelope(index("JsonSchema", null, null));
        JsonObject schema = attribute(entry(envelope, "app://self/user", "method", "onGet"), "JsonSchema");

        assertTrue(schema.getAsJsonArray("interceptors").isEmpty(), envelope::toString);
    }

    @Test
    void readsTheResourceRootItIsGiven() {
        addFile("src/Resource/App/User.php", USER);
        addFile("src/Resource/Page/Index.php", PROFILE
            .replace("Resource\\\\App", "Resource\\\\Page")
            .replace("Profile", "Index"));

        JsonObject envelope = envelope(index(null, null, "src/Resource/Page"));

        assertEquals("src/Resource/Page", envelope.getAsJsonObject("scan").get("resourceRoot").getAsString());
        assertEquals(1, envelope.getAsJsonObject("scan").get("files").getAsInt());
    }

    @Test
    void refusesAResourceRootThatLeavesTheProject() {
        JsonObject envelope = envelope(index(null, null, "../etc"));

        assertEquals("not_found", envelope.get("status").getAsString());
        assertTrue(envelope.get("error").getAsString().contains("../etc"), envelope::toString);
    }

    /** "." reads as the project itself, which would parse every PHP file in it, vendor included. */
    @Test
    void refusesAResourceRootThatIsTheProjectItself() {
        JsonObject envelope = envelope(index(null, null, "."));

        assertEquals("not_found", envelope.get("status").getAsString());
    }

    @Test
    void reportsNotFoundForAResourceRootThatDoesNotExist() {
        JsonObject envelope = envelope(index(null, null, "src/Nowhere"));

        assertEquals("not_found", envelope.get("status").getAsString());
        assertTrue(envelope.get("error").getAsString().contains("src/Nowhere"), envelope::toString);
    }

    private String index(String attribute, String method, String resourceRoot) {
        return ResourceAttributeIndexService.getInstance(fixture.getProject())
            .index(attribute, method, resourceRoot);
    }

    private static List<String> uris(JsonObject envelope) {
        List<String> uris = new ArrayList<>();
        for (JsonElement element : envelope.getAsJsonArray("entries")) {
            String uri = element.getAsJsonObject().get("uri").getAsString();
            if (!uris.contains(uri)) {
                uris.add(uri);
            }
        }

        return uris;
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }

        return values;
    }

    private static JsonObject entry(JsonObject envelope, String uri, String target, String method) {
        for (JsonElement element : envelope.getAsJsonArray("entries")) {
            JsonObject entry = element.getAsJsonObject();
            boolean sameMethod = method == null
                ? !entry.has("method")
                : entry.has("method") && method.equals(entry.get("method").getAsString());
            if (uri.equals(entry.get("uri").getAsString())
                && target.equals(entry.get("target").getAsString())
                && sameMethod) {
                return entry;
            }
        }
        throw new AssertionError("entry not found: " + uri + " " + target + " " + method + " in " + envelope);
    }

    private static JsonObject attribute(JsonObject entry, String name) {
        for (JsonElement element : entry.getAsJsonArray("attributes")) {
            if (name.equals(element.getAsJsonObject().get("name").getAsString())) {
                return element.getAsJsonObject();
            }
        }
        throw new AssertionError("attribute not found: " + name + " in " + entry);
    }

    private static JsonObject envelope(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private void addFile(String relativePath, String contents) {
        fixture.addFileToProject(relativePath, contents);
    }
}
