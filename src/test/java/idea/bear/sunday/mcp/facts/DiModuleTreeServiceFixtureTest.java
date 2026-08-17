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
 * The tree this walks is the one {@code BEAR\Package\Module} builds; the fixtures are written the
 * way {@code bear/package} and a generated app write theirs.
 *
 * <p>Module classes are resolved through the project index, so the light fixture recipe is the one
 * the attribute index test uses (see {@code ResourceAttributeIndexServiceFixtureTest}).
 */
class DiModuleTreeServiceFixtureTest {

    /** The app's own module, and the one it installs; the chain gives the walk something to nest. */
    private static final String APP_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        class AppModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->install(new AuraSqlModule());
            }
        }
        """;

    private static final String AURA_SQL_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        class AuraSqlModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->install(new DbModule());
            }
        }
        """;

    private static final String DB_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        class DbModule extends AbstractModule
        {
            protected function configure(): void
            {
            }
        }
        """;

    /** What the app does not declare, BEAR.Package does. */
    private static final String FRAMEWORK_PROD_MODULE = """
        <?php

        namespace BEAR\\Package\\Context;

        use Ray\\Di\\AbstractModule;

        class ProdModule extends AbstractModule
        {
            protected function configure(): void
            {
            }
        }
        """;

    /** override() puts its argument's bindings on top, so the edge is not the same as install(). */
    private static final String OVERRIDE_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        class OverrideModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->override(new DbModule());
            }
        }
        """;

    /**
     * A module declaring an {@code install()} of its own: those calls are its own method rather
     * than Ray.Di's, while its {@code override()} calls are still Ray.Di's.
     */
    private static final String LEGACY_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        class LegacyModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->install(new AuraSqlModule());
                $this->override(new DbModule());
            }

            public function install(AbstractModule $module): void
            {
            }
        }
        """;

    /** An install whose argument the source does not state. */
    private static final String DYNAMIC_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        class DynamicModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->installChosen($this->chooseModule());
            }

            private function installChosen(AbstractModule $module): void
            {
                $this->install($module);
            }
        }
        """;

    private static final String CYCLE_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        class CycleModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->install(new LoopModule());
            }
        }
        """;

    private static final String LOOP_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        class LoopModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->install(new CycleModule());
            }
        }
        """;

    /** The base a context module leaves its wiring to: the installs are written here, not there. */
    private static final String ABSTRACT_PROD_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        abstract class AbstractProdModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->install(new DbModule());
            }
        }
        """;

    /** A context module whose whole body is its base class. */
    private static final String PROD_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        class ProdModule extends AbstractProdModule
        {
        }
        """;

    /** The other half of the pattern: configure() chains to the base and adds to it. */
    private static final String STAGING_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        class StagingModule extends AbstractProdModule
        {
            protected function configure(): void
            {
                parent::configure();
                $this->install(new AuraSqlModule());
            }
        }
        """;

    /** An install whose text carries the characters Mermaid reads as syntax. */
    private static final String QUOTED_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        class QuotedModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->install($this->modules["prod #1"]);
            }
        }
        """;

    /** A context module whose base module is nowhere in the project. */
    private static final String ORPHAN_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Acme\\Base\\AbstractBaseModule;

        class OrphanModule extends AbstractBaseModule
        {
        }
        """;

    /** An anonymous module carries its own $this, so the installs inside it are not the outer's. */
    private static final String ANONYMOUS_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        class AnonymousModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->install(new class extends AbstractModule {
                    protected function configure(): void
                    {
                        $this->install(new DbModule());
                    }
                });
            }
        }
        """;

    /** A segment that installs the very module the loader ends by overriding everything with. */
    private static final String META_INSTALLING_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use BEAR\\Package\\Module\\AppMetaModule;
        use Ray\\Di\\AbstractModule;

        class MetaModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->install(new AppMetaModule());
            }
        }
        """;

    /** The module BEAR\\Package\\Module overrides every context with, once bear/package is installed. */
    private static final String APP_META_MODULE = """
        <?php

        namespace BEAR\\Package\\Module;

        use Ray\\Di\\AbstractModule;

        class AppMetaModule extends AbstractModule
        {
            protected function configure(): void
            {
            }
        }
        """;

    /** What {@code BEAR\Package\Module} starts the chain from, written as Ray.Di writes it. */
    private static final String ASSISTED_MODULE = """
        <?php

        namespace Ray\\Di;

        final class AssistedModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->install(new AssistedInjectModule());
            }
        }
        """;

    private static final String ASSISTED_INJECT_MODULE = """
        <?php

        namespace Ray\\Di;

        final class AssistedInjectModule extends AbstractModule
        {
            protected function configure(): void
            {
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

    @Test
    void resolvesASegmentToTheAppsOwnModule() {
        addApp();

        JsonObject envelope = envelope(read("app"));
        JsonObject segment = segment(envelope, 0);

        assertEquals("ok", envelope.get("status").getAsString(), envelope::toString);
        assertEquals("\\MyVendor\\MyProject", envelope.get("appNamespace").getAsString());
        assertEquals("\\MyVendor\\MyProject\\Module\\AppModule", segment.get("moduleClass").getAsString());
        assertEquals("app", segment.get("origin").getAsString());
        assertEquals("src/Module/AppModule.php", segment.get("filePath").getAsString());
    }

    /** A segment the app does not declare falls through to BEAR.Package's own context module. */
    @Test
    void fallsBackToTheFrameworkModuleForASegmentTheAppDoesNotDeclare() {
        addApp();
        addFile("vendor/bear/package/src/Context/ProdModule.php", FRAMEWORK_PROD_MODULE);

        JsonObject segment = segment(envelope(read("prod")), 0);

        assertEquals("\\BEAR\\Package\\Context\\ProdModule", segment.get("moduleClass").getAsString());
        assertEquals("framework", segment.get("origin").getAsString());
    }

    /**
     * The loader wraps the segments right to left and Ray.Di keeps the receiving container's
     * bindings, so the leftmost segment is the one that wins a conflict.
     */
    @Test
    void numbersTheLeftmostSegmentAsTheOneThatWins() {
        addApp();
        addFile("vendor/bear/package/src/Context/ProdModule.php", FRAMEWORK_PROD_MODULE);

        JsonObject envelope = envelope(read("prod-app"));

        assertEquals("prod", segment(envelope, 0).get("segment").getAsString());
        assertEquals(1, segment(envelope, 0).get("priority").getAsInt());
        assertEquals("app", segment(envelope, 1).get("segment").getAsString());
        assertEquals(2, segment(envelope, 1).get("priority").getAsInt());
    }

    /** A segment no class answers to is an answer, and it says which two names were looked for. */
    @Test
    void namesBothCandidatesForASegmentNothingDeclares() {
        addApp();

        JsonObject envelope = envelope(read("nowhere"));
        JsonObject unresolved = envelope.getAsJsonArray("unresolvedSegments").get(0).getAsJsonObject();

        assertEquals("ok", envelope.get("status").getAsString(), envelope::toString);
        assertEquals("nowhere", unresolved.get("segment").getAsString());
        assertEquals(
            List.of("\\MyVendor\\MyProject\\Module\\NowhereModule", "\\BEAR\\Package\\Context\\NowhereModule"),
            strings(unresolved.getAsJsonArray("candidates"))
        );
    }

    @Test
    void walksTheModulesAModuleInstalls() {
        addApp();

        JsonObject auraSql = install(segment(envelope(read("app")), 0), 0);
        JsonObject db = install(auraSql, 0);

        assertEquals("install", auraSql.get("kind").getAsString());
        assertEquals("\\MyVendor\\MyProject\\Module\\AuraSqlModule", auraSql.get("moduleClass").getAsString());
        assertEquals("\\MyVendor\\MyProject\\Module\\DbModule", db.get("moduleClass").getAsString());
    }

    /**
     * An edge names two files: the module is declared in one and installed from another. Pairing
     * the declared class's path with the caller's line would point at a line of a file the install
     * is not written in.
     */
    @Test
    void separatesWhereAModuleIsDeclaredFromWhereItIsInstalled() {
        addApp();

        JsonObject auraSql = install(segment(envelope(read("app")), 0), 0);
        JsonObject installedAt = auraSql.getAsJsonObject("installedAt");

        assertEquals("src/Module/AuraSqlModule.php", auraSql.get("filePath").getAsString());
        assertEquals("src/Module/AppModule.php", installedAt.get("filePath").getAsString());
        assertEquals(11, installedAt.get("line").getAsInt(), installedAt::toString);
    }

    /** The loader's own explode() keeps a trailing empty segment, and no app boots on one. */
    @Test
    void keepsATrailingEmptySegmentTheContextStates() {
        addApp();

        JsonObject envelope = envelope(read("app-"));
        JsonObject unresolved = envelope.getAsJsonArray("unresolvedSegments").get(0).getAsJsonObject();

        assertEquals(2, envelope.getAsJsonObject("scan").get("segments").getAsInt());
        assertEquals("", unresolved.get("segment").getAsString());
        assertEquals(2, unresolved.get("priority").getAsInt());
    }

    @Test
    void tellsAnOverrideEdgeFromAnInstallOne() {
        addApp();
        addFile("src/Module/OverrideModule.php", OVERRIDE_MODULE);

        JsonObject edge = install(segment(envelope(read("override")), 0), 0);

        assertEquals("override", edge.get("kind").getAsString());
        assertEquals("\\MyVendor\\MyProject\\Module\\DbModule", edge.get("moduleClass").getAsString());
    }

    /**
     * A module declaring its own {@code install()} says nothing about its {@code override()} calls,
     * which are still Ray.Di's -- reading it as if it did would report the module as installing
     * less than its source states.
     */
    @Test
    void marksACallToAnInstallTheModuleDeclaresItselfWithoutDroppingIt() {
        addApp();
        addFile("src/Module/LegacyModule.php", LEGACY_MODULE);

        JsonObject own = install(segment(envelope(read("legacy")), 0), 0);
        JsonObject rayDi = install(segment(envelope(read("legacy")), 0), 1);

        assertEquals("install", own.get("kind").getAsString());
        assertTrue(own.get("ownMethod").getAsBoolean(), own::toString);
        assertEquals("\\MyVendor\\MyProject\\Module\\AuraSqlModule", own.get("moduleClass").getAsString());
        // Declaring install() says nothing about override(), which is still Ray.Di's.
        assertEquals("override", rayDi.get("kind").getAsString());
        assertFalse(rayDi.has("ownMethod"), rayDi::toString);
    }

    /** Dropping an install this cannot read would say "this module installs nothing else". */
    @Test
    void reportsAnInstallWhoseArgumentItCannotRead() {
        addApp();
        addFile("src/Module/DynamicModule.php", DYNAMIC_MODULE);

        JsonObject edge = install(segment(envelope(read("dynamic")), 0), 0);

        assertEquals("install", edge.get("kind").getAsString());
        assertTrue(edge.get("moduleUnreadable").getAsBoolean(), edge::toString);
        assertEquals("$this->install($module)", edge.get("text").getAsString());
        assertFalse(edge.has("moduleClass"), edge::toString);
    }

    @Test
    void expandsAModuleOnceWhenTheGraphLoopsBackToIt() {
        addApp();
        addFile("src/Module/CycleModule.php", CYCLE_MODULE);
        addFile("src/Module/LoopModule.php", LOOP_MODULE);

        JsonObject loop = install(segment(envelope(read("cycle")), 0), 0);
        JsonObject back = install(loop, 0);

        assertEquals("\\MyVendor\\MyProject\\Module\\CycleModule", back.get("moduleClass").getAsString());
        assertTrue(back.get("visited").getAsBoolean(), back::toString);
        assertFalse(back.has("installs"), back::toString);
    }

    /**
     * The loader's own last step outranks every segment, so it is part of the tree -- and reported
     * as unresolved rather than omitted when bear/package is not installed.
     */
    @Test
    void reportsTheFrameworksFinalOverride() {
        addApp();
        addFile("vendor/bear/package/src/Module/AppMetaModule.php", APP_META_MODULE);

        JsonObject override = envelope(read("app")).getAsJsonObject("frameworkOverride");

        assertEquals("\\BEAR\\Package\\Module\\AppMetaModule", override.get("moduleClass").getAsString());
        assertEquals("override", override.get("kind").getAsString());
        assertEquals(0, override.get("priority").getAsInt());
        assertFalse(override.has("classUnresolved"), override::toString);
    }

    /**
     * A module whose body is empty still installs whatever its base module installs, and reading
     * only its own body would report the context as installing nothing at all.
     */
    @Test
    void readsTheInstallsAModuleInheritsFromItsBaseModule() {
        addApp();
        addFile("src/Module/AbstractProdModule.php", ABSTRACT_PROD_MODULE);
        addFile("src/Module/ProdModule.php", PROD_MODULE);

        JsonObject edge = install(segment(envelope(read("prod")), 0), 0);

        assertEquals("\\MyVendor\\MyProject\\Module\\DbModule", edge.get("moduleClass").getAsString());
        assertEquals("\\MyVendor\\MyProject\\Module\\AbstractProdModule", edge.get("inheritedFrom").getAsString());
        // The install is written in the base module's file, which is where installedAt must point.
        assertEquals(
            "src/Module/AbstractProdModule.php",
            edge.getAsJsonObject("installedAt").get("filePath").getAsString()
        );
    }

    /** A configure() that chains to its base runs both sets, and both are the answer. */
    @Test
    void keepsAModulesOwnInstallsApartFromTheOnesItInherits() {
        addApp();
        addFile("src/Module/AbstractProdModule.php", ABSTRACT_PROD_MODULE);
        addFile("src/Module/StagingModule.php", STAGING_MODULE);

        JsonArray edges = segment(envelope(read("staging")), 0).getAsJsonArray("installs");

        assertEquals(2, edges.size(), edges::toString);
        JsonObject own = edges.get(0).getAsJsonObject();
        JsonObject inherited = edges.get(1).getAsJsonObject();
        assertEquals("\\MyVendor\\MyProject\\Module\\AuraSqlModule", own.get("moduleClass").getAsString());
        assertFalse(own.has("inheritedFrom"), own::toString);
        assertEquals("\\MyVendor\\MyProject\\Module\\DbModule", inherited.get("moduleClass").getAsString());
        assertEquals("\\MyVendor\\MyProject\\Module\\AbstractProdModule", inherited.get("inheritedFrom").getAsString());
    }

    /**
     * A base module the index cannot resolve leaves the module's whole wiring unread. Saying so is
     * what keeps the empty node from reading as "this module installs nothing".
     */
    @Test
    void saysSoWhenItCannotResolveAModulesBaseClass() {
        addApp();
        addFile("src/Module/OrphanModule.php", ORPHAN_MODULE);

        JsonObject segment = segment(envelope(read("orphan")), 0);

        assertEquals("\\Acme\\Base\\AbstractBaseModule", segment.get("baseClassUnresolved").getAsString());
        assertFalse(segment.has("installs"), segment::toString);
    }

    /**
     * Every module's chain ends at Ray.Di's own AbstractModule, whose body installs nothing. A
     * project whose vendor directory is not indexed cannot resolve it -- and marking that would
     * put "wiring unread" on every module in the project while nothing is unread.
     */
    @Test
    void doesNotClaimAnUnresolvedBaseWhenTheChainEndsAtRayDisOwnModule() {
        addApp();
        addFile("src/Module/AbstractProdModule.php", ABSTRACT_PROD_MODULE);
        addFile("src/Module/ProdModule.php", PROD_MODULE);

        JsonObject segment = segment(envelope(read("prod")), 0);

        assertFalse(segment.has("baseClassUnresolved"), segment::toString);
        assertEquals(1, segment.getAsJsonArray("installs").size(), segment::toString);
    }

    /**
     * The installs of an anonymous module belong to it, not to the class it is written in --
     * reading them here would report the inner module's edges as the outer module's own.
     */
    @Test
    void doesNotClaimTheInstallsOfAnAnonymousModuleAsTheEnclosingModulesOwn() {
        addApp();
        addFile("src/Module/AnonymousModule.php", ANONYMOUS_MODULE);

        JsonArray edges = segment(envelope(read("anonymous")), 0).getAsJsonArray("installs");

        assertEquals(1, edges.size(), edges::toString);
        // The anonymous class is the module installed, and it is one this cannot name.
        assertTrue(edges.get(0).getAsJsonObject().get("moduleUnreadable").getAsBoolean(), edges::toString);
    }

    /**
     * The loader's final override outranks every segment, so it is the node that gets expanded:
     * reported as a bare "visited" leaf it would say the strongest module in the tree installs
     * nothing.
     */
    @Test
    void expandsTheFrameworksOverrideRatherThanTheSegmentThatAlsoInstallsIt() {
        addApp();
        addFile("vendor/bear/package/src/Module/AppMetaModule.php", APP_META_MODULE);
        addFile("src/Module/MetaModule.php", META_INSTALLING_MODULE);

        JsonObject envelope = envelope(read("meta"));
        JsonObject override = envelope.getAsJsonObject("frameworkOverride");
        JsonObject viaSegment = install(segment(envelope, 0), 0);

        assertFalse(override.has("visited"), override::toString);
        assertEquals("\\BEAR\\Package\\Module\\AppMetaModule", viaSegment.get("moduleClass").getAsString());
        assertTrue(viaSegment.get("visited").getAsBoolean(), viaSegment::toString);
    }

    /**
     * The loader builds its chain from {@code new AssistedModule()} outwards, so it is in every
     * tree and every segment wraps it -- the weakest node, and one no segment names.
     */
    @Test
    void reportsTheLoadersInnermostAssistedModule() {
        addApp();
        addFile("vendor/ray/di/src/di/AssistedModule.php", ASSISTED_MODULE);
        addFile("vendor/ray/di/src/di/AssistedInjectModule.php", ASSISTED_INJECT_MODULE);

        JsonObject assisted = envelope(read("prod-app")).getAsJsonObject("assistedModule");

        assertEquals("\\Ray\\Di\\AssistedModule", assisted.get("moduleClass").getAsString());
        // One past the last segment's: every segment wraps it, so every segment beats it.
        assertEquals(3, assisted.get("priority").getAsInt(), assisted::toString);
        assertFalse(assisted.has("classUnresolved"), assisted::toString);
        assertEquals(
            "\\Ray\\Di\\AssistedInjectModule",
            install(assisted, 0).get("moduleClass").getAsString()
        );
    }

    @Test
    void saysSoWhenTheLoadersAssistedModuleIsNotInstalled() {
        addApp();

        JsonObject assisted = envelope(read("app")).getAsJsonObject("assistedModule");

        assertEquals("\\Ray\\Di\\AssistedModule", assisted.get("moduleClass").getAsString());
        assertTrue(assisted.get("classUnresolved").getAsBoolean(), assisted::toString);
    }

    @Test
    void saysSoWhenTheFrameworksFinalOverrideIsNotInstalled() {
        addApp();

        JsonObject override = envelope(read("app")).getAsJsonObject("frameworkOverride");

        assertEquals("\\BEAR\\Package\\Module\\AppMetaModule", override.get("moduleClass").getAsString());
        assertTrue(override.get("classUnresolved").getAsBoolean(), override::toString);
    }

    /**
     * Without AppModule.php the app-side candidate cannot even be named, so a segment answered as
     * "framework" may still be shadowed by an app module this could not look for.
     */
    @Test
    void saysSoWhenItCannotNameTheAppsNamespace() {
        addFile("vendor/bear/package/src/Context/ProdModule.php", FRAMEWORK_PROD_MODULE);

        JsonObject envelope = envelope(read("prod"));

        assertTrue(envelope.get("appNamespaceUnknown").getAsBoolean(), envelope::toString);
        assertFalse(envelope.has("appNamespace"), envelope::toString);
        assertEquals("framework", segment(envelope, 0).get("origin").getAsString());
    }

    @Test
    void countsWhatItWalked() {
        addApp();
        addFile("vendor/bear/package/src/Context/ProdModule.php", FRAMEWORK_PROD_MODULE);

        JsonObject scan = envelope(read("prod-app-nowhere")).getAsJsonObject("scan");

        assertEquals("prod-app-nowhere", scan.get("context").getAsString());
        assertEquals(3, scan.get("segments").getAsInt());
        // ProdModule, AppModule and the two AppModule reaches; AppMetaModule is not installed here.
        assertEquals(4, scan.get("modules").getAsInt());
        assertFalse(scan.has("modulesSkipped"), scan::toString);
    }

    @Test
    void asksForAContext() {
        addApp();

        JsonObject envelope = envelope(read("  "));

        assertEquals("not_found", envelope.get("status").getAsString());
        assertTrue(envelope.get("error").getAsString().contains("prod-api-app"), envelope::toString);
    }

    /**
     * The picture is a rendering of the answer: the same modules, the same edges, and the same
     * marks. A diagram that quietly tidied one away would be believed more readily than the JSON.
     */
    @Test
    void drawsTheTreeItAnswersWith() {
        addApp();
        addFile("src/Module/OverrideModule.php", OVERRIDE_MODULE);

        String mermaid = diagram("override-app");

        assertTrue(mermaid.startsWith("flowchart LR\n"), mermaid);
        assertTrue(mermaid.contains("[\"AppModule<br/>app \u00b7 priority 2 \u00b7 app\"]"), mermaid);
        // install is a plain arrow, override a thick one, because override decides which wins.
        assertTrue(mermaid.contains(" -->|install| "), mermaid);
        assertTrue(mermaid.contains(" ==>|override| "), mermaid);
        // The loader's own final override is in the picture, and it is priority 0.
        assertTrue(mermaid.contains("framework override \u00b7 priority 0"), mermaid);
        // So is the module it starts from, at the weak end of the same chain.
        assertTrue(mermaid.contains("assisted injection \u00b7 priority 3"), mermaid);
    }

    /** An install the walk could not read is drawn as one, not left out of the picture. */
    @Test
    void drawsTheMarksTheAnswerCarries() {
        addApp();
        addFile("src/Module/DynamicModule.php", DYNAMIC_MODULE);

        String mermaid = diagram("dynamic-nowhere");

        assertTrue(mermaid.contains("module not named<br/>$this->install($module)"), mermaid);
        assertTrue(mermaid.contains("nowhere \u00b7 segment unresolved"), mermaid);
    }

    /**
     * Mermaid ends a label on a quote and starts an entity on a hash, and an install this could
     * not read is drawn with its source text -- which is where both turn up.
     */
    @Test
    void escapesTheSourceTextItDrawsIntoALabel() {
        addApp();
        addFile("src/Module/QuotedModule.php", QUOTED_MODULE);

        String mermaid = diagram("quoted");
        String label = mermaid.lines().filter(line -> line.contains("module not named")).findFirst().orElseThrow();

        assertTrue(label.contains("#quot;prod #35;1#quot;"), label);
        // Exactly the two quotes that open and close the label itself, and no bare hash.
        assertEquals(2, label.chars().filter(c -> c == '"').count(), label);
        assertFalse(label.contains("#1"), label);
    }

    private void addApp() {
        addFile("src/Module/AppModule.php", APP_MODULE);
        addFile("src/Module/AuraSqlModule.php", AURA_SQL_MODULE);
        addFile("src/Module/DbModule.php", DB_MODULE);
    }

    private String read(String context) {
        return DiModuleTreeService.getInstance(fixture.getProject()).read(context, false);
    }

    private String diagram(String context) {
        return envelope(DiModuleTreeService.getInstance(fixture.getProject()).read(context, true))
            .get("diagram").getAsString();
    }

    private static JsonObject segment(JsonObject envelope, int position) {
        return at(envelope.getAsJsonArray("segments"), position, envelope);
    }

    private static JsonObject install(JsonObject module, int position) {
        return at(module.getAsJsonArray("installs"), position, module);
    }

    private static JsonObject at(JsonArray array, int position, JsonObject context) {
        if (array == null || position >= array.size()) {
            throw new AssertionError("nothing at " + position + " in " + context);
        }

        return array.get(position).getAsJsonObject();
    }

    private static List<String> strings(JsonArray array) {
        List<String> strings = new ArrayList<>();
        array.forEach(element -> strings.add(element.getAsString()));

        return strings;
    }

    private static JsonObject envelope(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private void addFile(String relativePath, String contents) {
        fixture.addFileToProject(relativePath, contents);
    }
}
