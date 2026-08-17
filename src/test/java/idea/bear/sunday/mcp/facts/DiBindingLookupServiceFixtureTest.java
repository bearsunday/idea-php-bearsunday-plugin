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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The forms in these fixtures are the ones {@code bear/*} and {@code ray/*} actually write; they
 * were read out of {@code demo-app/vendor} rather than invented.
 */
class DiBindingLookupServiceFixtureTest {

    /** The bindings this tool can name an implementation for. The last one is the failure it exists for. */
    private static final String APP_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use BEAR\\Resource\\RenderInterface;
        use BEAR\\Resource\\Renderer\\HalRenderer;
        use BEAR\\Resource\\Renderer\\OptionsRenderer;
        use MyVendor\\MyProject\\Annotation\\Category;
        use MyVendor\\MyProject\\CategorySurrogateKey;
        use MyVendor\\MyProject\\SurrogateKeyInterface;
        use Ray\\Di\\AbstractModule;
        use Ray\\Di\\Scope;

        final class AppModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bind(RenderInterface::class)->to(HalRenderer::class)->in(Scope::SINGLETON);
                $this->bind(RenderInterface::class)->annotatedWith('options')->to(OptionsRenderer::class);
                $this->bind(SurrogateKeyInterface::class)->annotatedWith(Category::class)->to(CategorySurrogateKey::class);
            }
        }
        """;

    /** Every binding form whose implementation only a running container knows. */
    private static final String CACHE_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use MyVendor\\MyProject\\Annotation\\AppName;
        use MyVendor\\MyProject\\Annotation\\Local;
        use MyVendor\\MyProject\\ApcuAdapter;
        use MyVendor\\MyProject\\CacheItemPoolInterface;
        use MyVendor\\MyProject\\CacheNamespace;
        use MyVendor\\MyProject\\Memcached;
        use MyVendor\\MyProject\\MemcachedProvider;
        use MyVendor\\MyProject\\OptionsMethods;
        use Ray\\Di\\AbstractModule;

        final class CacheModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bind(Memcached::class)->toProvider(MemcachedProvider::class);
                $this->bind(CacheItemPoolInterface::class)
                    ->annotatedWith(Local::class)
                    ->toConstructor(ApcuAdapter::class, ['namespace' => CacheNamespace::class]);
                $this->bind(OptionsMethods::class);
                $this->bind()->annotatedWith(AppName::class)->toInstance($this->appName);
            }
        }
        """;

    /** A chain over several lines with a docblock inside it, and a qualifier held in a property. */
    private static final String REPLICATION_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use MyVendor\\MyProject\\ExtendedPdoInterface;
        use MyVendor\\MyProject\\ReplicationDbProvider;
        use Ray\\Di\\AbstractModule;

        final class ReplicationModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bind(ExtendedPdoInterface::class)
                    /** @phpstan-ignore argument.type */
                    ->annotatedWith($this->qualifer)
                    ->toProvider(ReplicationDbProvider::class, $this->qualifer);
            }
        }
        """;

    /** rename() moves a binding to another qualifier; this version reports it instead of applying it. */
    private static final String HALO_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use BEAR\\Resource\\RenderInterface;
        use Ray\\Di\\AbstractModule;

        final class HaloModule extends AbstractModule
        {
            protected function configure(): void
            {
                $module->rename(RenderInterface::class, 'original');
            }
        }
        """;

    /** Writes the same {@code Category::class} text as {@link #APP_MODULE}, aliased elsewhere. */
    private static final String OTHER_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use MyVendor\\MyProject\\Other\\Category;
        use MyVendor\\MyProject\\OtherSurrogateKey;
        use MyVendor\\MyProject\\SurrogateKeyInterface;
        use Ray\\Di\\AbstractModule;

        final class OtherModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bind(SurrogateKeyInterface::class)->annotatedWith(Category::class)->to(OtherSurrogateKey::class);
            }
        }
        """;

    /**
     * Four calls named bind that are not Ray.Di bindings: too many arguments, another receiver, a
     * class that extends nothing, and a class that declares the bind() being called.
     */
    private static final String EVENT_LISTENER = """
        <?php

        namespace MyVendor\\MyProject;

        final class EventListener
        {
            public function register(Dispatcher $dispatcher): void
            {
                $this->bind('event', $handler);
                $dispatcher->bind(Handler::class);
                $this->bind($handler);
            }
        }

        final class SocketBinder extends Socket
        {
            public function listen(): void
            {
                $this->bind($address);
            }

            public function bind(string $address): void
            {
            }
        }
        """;

    /** Qualifiers whose value the source does not state, and one it does state through an escape. */
    private static final String NAMED_PDO_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        final class NamedPdoModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bind()->annotatedWith("{$this->qualifer}_dsn")->toInstance($this->dsn);
                $this->bind()->annotatedWith("pdo\\tuser")->toInstance($this->user);
                $this->bind()->annotatedWith('pdo\\tpass')->toInstance($this->pass);
            }
        }
        """;

    /** A constant that is not ::class: its value is not in the source, so it names no class. */
    private static final String REGISTRY_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use MyVendor\\MyProject\\Registry;
        use MyVendor\\MyProject\\StoreInterface;
        use Ray\\Di\\AbstractModule;

        final class RegistryModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bind(StoreInterface::class)->to(Registry::DEFAULT_STORE);
                $this->bind(Registry::STORE_KEY)->to(Registry::class);
            }
        }
        """;

    /** Ray.Di's own spellings that are not class constants: bind('') and a string interface name. */
    private static final String STRING_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use MyVendor\\MyProject\\Annotation\\AppName;
        use Ray\\Di\\AbstractModule;

        final class StringModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bind('')->annotatedWith(AppName::class)->toInstance('demo');
                $this->bind('MyVendor\\\\MyProject\\\\ClockInterface')->to(SystemClock::class);
                $this->bind(TimerInterface::class)->to('MyVendor\\\\MyProject\\\\Module\\\\SystemTimer');
            }
        }
        """;

    /** A module that inherits configure() from a base module, and renames onto the same interface. */
    private static final String PROD_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use BEAR\\Resource\\RenderInterface;
        use Ray\\Di\\AbstractModule;

        abstract class BaseContextModule extends AbstractModule
        {
            protected function configure(): void
            {
            }
        }

        final class ProdModule extends BaseContextModule
        {
            public function __construct(AbstractModule $module)
            {
                $module->rename(RenderInterface::class, 'original', '', '');

                parent::__construct($module);
            }
        }
        """;

    /** A chain continued through a variable: the tail is in another statement, out of reach. */
    private static final String SPLIT_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use MyVendor\\MyProject\\ClockInterface;
        use MyVendor\\MyProject\\SystemClock;
        use Ray\\Di\\AbstractModule;

        final class SplitModule extends AbstractModule
        {
            protected function configure(): void
            {
                $bind = $this->bind(ClockInterface::class);
                $bind->annotatedWith('utc')->to(SystemClock::class);
            }
        }
        """;

    /** A trait extends nothing, yet the bind it hosts runs in the module that uses it. */
    private static final String TRAIT_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use MyVendor\\MyProject\\ClockInterface;
        use MyVendor\\MyProject\\SystemClock;
        use Ray\\Di\\AbstractModule;

        trait ClockBindingTrait
        {
            protected function bindClock(): void
            {
                $this->bind(ClockInterface::class)->to(SystemClock::class);
            }
        }

        final class TraitModule extends AbstractModule
        {
            use ClockBindingTrait;

            protected function configure(): void
            {
                $this->bindClock();
            }
        }
        """;

    /** A bind handed back from a helper: the chain is finished by the caller, out of reach. */
    private static final String RETURN_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use MyVendor\\MyProject\\ClockInterface;
        use MyVendor\\MyProject\\SystemClock;
        use Ray\\Di\\AbstractModule;
        use Ray\\Di\\Bind;

        final class ReturnModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->clock()->to(SystemClock::class);
            }

            private function clock(): Bind
            {
                return $this->bind(ClockInterface::class);
            }
        }
        """;

    /**
     * Two renames of something that is not a binding: one whose receiver is not a module, and one
     * called on {@code $this} by a class that declares no {@code configure()}, so it is no module
     * either. Both extend something, which is all the bind guard asks of a class.
     */
    private static final String MOVE_FILE = """
        <?php

        namespace MyVendor\\MyProject;

        final class MoveFile extends AbstractHandler
        {
            public function move(string $from, string $to): void
            {
                $this->filesystem->rename($from, $to);
            }
        }

        final class MoveTable extends AbstractTable
        {
            public function move(string $from, string $to): void
            {
                $this->rename($from, $to);
            }
        }
        """;

    /** A four-argument rename: the binding lands on an interface argument 0 never names. */
    private static final String MOVE_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use MyVendor\\MyProject\\SourceInterface;
        use MyVendor\\MyProject\\TargetInterface;
        use Ray\\Di\\AbstractModule;

        final class MoveModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->rename(SourceInterface::class, 'new', '', TargetInterface::class);
            }
        }
        """;

    /** A rename whose arguments are not literals: the construct most worth reporting. */
    private static final String DYNAMIC_RENAME_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        final class DynamicRenameModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->rename($this->interface, $this->newName);
            }
        }
        """;

    /** The app module a context reaches, and the one it installs. */
    private static final String CONTEXT_APP_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use MyVendor\\MyProject\\ClockInterface;
        use MyVendor\\MyProject\\SystemClock;
        use Ray\\Di\\AbstractModule;

        class AppModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->install(new MailModule());
                $this->bind(ClockInterface::class)->to(SystemClock::class);
            }
        }
        """;

    private static final String CONTEXT_MAIL_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use MyVendor\\MyProject\\MailerInterface;
        use MyVendor\\MyProject\\SmtpMailer;
        use Ray\\Di\\AbstractModule;

        class MailModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bind(MailerInterface::class)->to(SmtpMailer::class);
            }
        }
        """;

    /** A module under src that no context installs: the one a context-scoped answer leaves out. */
    private static final String CONTEXT_STRAY_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use MyVendor\\MyProject\\MailerInterface;
        use MyVendor\\MyProject\\NullMailer;
        use Ray\\Di\\AbstractModule;

        class StrayModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bind(MailerInterface::class)->to(NullMailer::class);
            }
        }
        """;

    /** A module that states its bindings in a base module and nothing in its own body. */
    private static final String CONTEXT_ABSTRACT_PROD_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use MyVendor\\MyProject\\ClockInterface;
        use MyVendor\\MyProject\\UtcClock;
        use Ray\\Di\\AbstractModule;

        abstract class AbstractProdModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bind(ClockInterface::class)->to(UtcClock::class);
            }
        }
        """;

    private static final String CONTEXT_PROD_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        final class ProdModule extends AbstractProdModule
        {
        }
        """;

    /** The module the loader overrides everything with, which no context segment names. */
    private static final String CONTEXT_APP_META_MODULE = """
        <?php

        namespace BEAR\\Package\\Module;

        use BEAR\\AppMeta\\AbstractAppMeta;
        use MyVendor\\MyProject\\ProjectMeta;
        use Ray\\Di\\AbstractModule;

        class AppMetaModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bind(AbstractAppMeta::class)->to(ProjectMeta::class);
            }
        }
        """;

    private CodeInsightTestFixture fixture;

    /** The light fixture recipe the attribute index test uses; see its setUp for why. */
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
    void findsTheImplementationBoundToAnInterface() {
        addFile("src/Module/AppModule.php", APP_MODULE);

        JsonObject envelope = envelope(lookup("RenderInterface", null, null));
        JsonArray bindings = envelope.getAsJsonArray("bindings");
        JsonObject unnamed = bindings.get(0).getAsJsonObject();

        assertEquals("ok", envelope.get("status").getAsString());
        assertEquals(2, bindings.size(), envelope::toString);
        assertEquals("\\BEAR\\Resource\\RenderInterface", unnamed.get("interface").getAsString());
        assertEquals("to", unnamed.get("boundBy").getAsString());
        assertEquals("static", unnamed.get("resolution").getAsString());
        assertEquals("\\BEAR\\Resource\\Renderer\\HalRenderer", unnamed.get("implementation").getAsString());
        assertEquals("Scope::SINGLETON", unnamed.get("scope").getAsString());
        assertFalse(unnamed.has("qualifier"), unnamed::toString);
    }

    @Test
    void acceptsAnInterfaceWrittenAsAWholeClassName() {
        addFile("src/Module/AppModule.php", APP_MODULE);

        JsonArray byClassName = envelope(lookup("\\BEAR\\Resource\\RenderInterface", null, null))
            .getAsJsonArray("bindings");

        assertEquals(2, byClassName.size());
    }

    @Test
    void findsTheImplementationBoundUnderANamedQualifier() {
        addFile("src/Module/AppModule.php", APP_MODULE);

        JsonArray bindings = envelope(lookup("RenderInterface", "options", null)).getAsJsonArray("bindings");
        JsonObject binding = bindings.get(0).getAsJsonObject();

        assertEquals(1, bindings.size());
        assertEquals("name", binding.getAsJsonObject("qualifier").get("kind").getAsString());
        assertEquals("options", binding.getAsJsonObject("qualifier").get("value").getAsString());
        assertEquals("\\BEAR\\Resource\\Renderer\\OptionsRenderer", binding.get("implementation").getAsString());
    }

    /**
     * The failure this tool exists for: an injected {@code #[Category] SurrogateKeyInterface} names
     * neither {@code CategorySurrogateKey} nor anything inside it, so a text search cannot reach it.
     */
    @Test
    void findsTheImplementationBoundUnderAQualifierAttributeClass() {
        addFile("src/Module/AppModule.php", APP_MODULE);

        for (String qualifier : List.of("Category", "\\MyVendor\\MyProject\\Annotation\\Category")) {
            JsonArray bindings = envelope(lookup("SurrogateKeyInterface", qualifier, null))
                .getAsJsonArray("bindings");
            JsonObject binding = bindings.get(0).getAsJsonObject();

            assertEquals(1, bindings.size(), qualifier);
            assertEquals("class", binding.getAsJsonObject("qualifier").get("kind").getAsString());
            assertEquals(
                "\\MyVendor\\MyProject\\Annotation\\Category",
                binding.getAsJsonObject("qualifier").get("value").getAsString()
            );
            assertEquals("\\MyVendor\\MyProject\\CategorySurrogateKey", binding.get("implementation").getAsString());
        }
    }

    /** The same short name aliases a different class in each file, so only one is the query's. */
    @Test
    void filtersTheQualifierByTheResolvedClassRatherThanByTheTextWritten() {
        addFile("src/Module/AppModule.php", APP_MODULE);
        addFile("src/Module/OtherModule.php", OTHER_MODULE);

        JsonArray byShortName = envelope(lookup(null, "Category", null)).getAsJsonArray("bindings");
        JsonArray byClassName = envelope(lookup(null, "\\MyVendor\\MyProject\\Other\\Category", null))
            .getAsJsonArray("bindings");

        assertEquals(2, byShortName.size(), byShortName::toString);
        assertEquals(1, byClassName.size(), byClassName::toString);
        assertEquals(
            "\\MyVendor\\MyProject\\OtherSurrogateKey",
            byClassName.get(0).getAsJsonObject().get("implementation").getAsString()
        );
    }

    @Test
    void reportsTheModuleClassFileAndLineThatBinds() {
        addFile("src/Module/AppModule.php", APP_MODULE);

        JsonObject binding = envelope(lookup("SurrogateKeyInterface", null, null))
            .getAsJsonArray("bindings").get(0).getAsJsonObject();

        assertEquals("\\MyVendor\\MyProject\\Module\\AppModule", binding.get("moduleClass").getAsString());
        assertEquals("src/Module/AppModule.php", binding.get("filePath").getAsString());
        assertEquals(20, binding.get("line").getAsInt(), binding::toString);
        assertTrue(binding.get("text").getAsString().startsWith("$this->bind(SurrogateKeyInterface::class)"));
    }

    /**
     * Every form whose implementation a running container decides is reported rather than dropped,
     * with the class its argument names kept: an agent must be able to tell "not bound" from
     * "bound in a way I cannot follow".
     */
    @Test
    void labelsABindingWhoseImplementationNeedsAContainerAsUnresolved() {
        addFile("src/Module/CacheModule.php", CACHE_MODULE);

        JsonObject provider = binding(envelope(lookup("Memcached", null, null)), 0);
        JsonObject constructor = binding(envelope(lookup("CacheItemPoolInterface", null, null)), 0);
        JsonObject untargeted = binding(envelope(lookup("OptionsMethods", null, null)), 0);

        assertEquals("toProvider", provider.get("boundBy").getAsString());
        assertEquals("dynamic-unresolved", provider.get("resolution").getAsString());
        assertFalse(provider.has("implementation"), provider::toString);
        assertEquals("\\MyVendor\\MyProject\\MemcachedProvider", provider.get("targetClass").getAsString());

        assertEquals("toConstructor", constructor.get("boundBy").getAsString());
        assertEquals("dynamic-unresolved", constructor.get("resolution").getAsString());
        assertEquals("\\MyVendor\\MyProject\\ApcuAdapter", constructor.get("targetClass").getAsString());

        assertEquals("untargeted", untargeted.get("boundBy").getAsString());
        assertEquals("dynamic-unresolved", untargeted.get("resolution").getAsString());
    }

    /** {@code bind()} with no argument binds a name alone; that is a binding, not an unreadable one. */
    @Test
    void keepsABindingThatNamesNoInterface() {
        addFile("src/Module/CacheModule.php", CACHE_MODULE);

        JsonObject binding = binding(envelope(lookup(null, "AppName", null)), 0);

        assertFalse(binding.has("interface"), binding::toString);
        assertFalse(binding.has("interfaceUnreadable"), binding::toString);
        assertEquals("toInstance", binding.get("boundBy").getAsString());
        assertEquals(
            "\\MyVendor\\MyProject\\Annotation\\AppName",
            binding.getAsJsonObject("qualifier").get("value").getAsString()
        );
    }

    @Test
    void readsAChainSpreadOverSeveralLinesWithADocblockInsideIt() {
        addFile("src/Module/ReplicationModule.php", REPLICATION_MODULE);

        JsonObject binding = binding(envelope(lookup("ExtendedPdoInterface", null, null)), 0);

        assertEquals("toProvider", binding.get("boundBy").getAsString());
        assertEquals("\\MyVendor\\MyProject\\ReplicationDbProvider", binding.get("targetClass").getAsString());
        assertEquals("unresolved", binding.getAsJsonObject("qualifier").get("kind").getAsString());
        assertEquals("$this->qualifer", binding.getAsJsonObject("qualifier").get("value").getAsString());
    }

    /**
     * A qualifier held in a property cannot be compared against the query, so the binding goes to
     * "unresolved". Excluding it would answer "nothing is bound under that qualifier" -- the
     * confident wrong answer this tool exists to prevent.
     */
    @Test
    void putsABindingWithAnUnreadableQualifierInUnresolvedRatherThanDroppingIt() {
        addFile("src/Module/ReplicationModule.php", REPLICATION_MODULE);

        JsonObject envelope = envelope(lookup(null, "slave", null));
        JsonObject unresolved = envelope.getAsJsonArray("unresolved").get(0).getAsJsonObject();

        assertTrue(envelope.getAsJsonArray("bindings").isEmpty(), envelope::toString);
        assertEquals("qualifier-unreadable", unresolved.get("reason").getAsString());
        assertEquals("\\MyVendor\\MyProject\\ExtendedPdoInterface", unresolved.get("interface").getAsString());
        assertEquals("src/Module/ReplicationModule.php", unresolved.get("filePath").getAsString());
    }

    /** With no qualifier asked for, nothing about the qualifier is being decided, so it is a binding. */
    @Test
    void keepsABindingWithAnUnreadableQualifierWhenNoQualifierIsAsked() {
        addFile("src/Module/ReplicationModule.php", REPLICATION_MODULE);

        JsonObject envelope = envelope(lookup("ExtendedPdoInterface", null, null));

        assertEquals(1, envelope.getAsJsonArray("bindings").size());
        assertTrue(envelope.getAsJsonArray("unresolved").isEmpty(), envelope::toString);
    }

    @Test
    void reportsARenameItDoesNotApply() {
        addFile("src/Module/HaloModule.php", HALO_MODULE);

        JsonObject envelope = envelope(lookup("RenderInterface", null, null));
        JsonObject unresolved = envelope.getAsJsonArray("unresolved").get(0).getAsJsonObject();

        assertEquals(1, envelope.getAsJsonObject("scan").get("renames").getAsInt());
        assertEquals("rename-not-applied", unresolved.get("reason").getAsString());
        assertEquals("\\BEAR\\Resource\\RenderInterface", unresolved.get("interface").getAsString());
        assertEquals("$module->rename(RenderInterface::class, 'original')", unresolved.get("text").getAsString());
    }

    /** A rename of another interface has nothing to say about the interface being asked about. */
    @Test
    void leavesOutARenameOfAnotherInterface() {
        addFile("src/Module/HaloModule.php", HALO_MODULE);

        JsonObject envelope = envelope(lookup("SurrogateKeyInterface", null, null));

        assertTrue(envelope.getAsJsonArray("unresolved").isEmpty(), envelope::toString);
    }

    @Test
    void doesNotReadAMethodNamedBindThatIsNotARayDiBinding() {
        addFile("src/EventListener.php", EVENT_LISTENER);

        JsonObject envelope = envelope(lookup(null, null, null));

        assertTrue(envelope.getAsJsonArray("bindings").isEmpty(), envelope::toString);
        assertTrue(envelope.getAsJsonArray("unresolved").isEmpty(), envelope::toString);
        assertEquals(0, envelope.getAsJsonObject("scan").get("bindings").getAsInt());
        assertEquals(0, envelope.getAsJsonObject("scan").get("moduleFiles").getAsInt());
    }

    /**
     * An interpolated string states a template, not a name: the source says
     * {@code "{$this->qualifer}_dsn"} while Ray.Di keys the binding under whatever the property
     * held. Reporting it as a #[Named] value would answer "nothing is bound under slave_dsn" with
     * full confidence -- the failure this whole tool exists to prevent.
     */
    @Test
    void refusesToReadAnInterpolatedQualifierAsANamedValue() {
        addFile("src/Module/NamedPdoModule.php", NAMED_PDO_MODULE);

        JsonObject filtered = envelope(lookup(null, "slave_dsn", null));
        JsonObject unresolved = filtered.getAsJsonArray("unresolved").get(0).getAsJsonObject();
        JsonObject unfiltered = binding(envelope(lookup(null, null, null)), 0);

        assertTrue(filtered.getAsJsonArray("bindings").isEmpty(), filtered::toString);
        assertEquals("qualifier-unreadable", unresolved.get("reason").getAsString());
        assertEquals("unresolved", unfiltered.getAsJsonObject("qualifier").get("kind").getAsString());
    }

    /**
     * The name a binding answers to is the string PHP builds, not the text between the quotes:
     * double quotes turn {@code \t} into a tab and single quotes leave it as two characters, so
     * the two bindings here are keyed differently despite being written almost the same.
     */
    @Test
    void reportsTheStringAQualifierEscapeStandsFor() {
        addFile("src/Module/NamedPdoModule.php", NAMED_PDO_MODULE);

        JsonObject interpreted = binding(envelope(lookup(null, "pdo\tuser", null)), 0);
        JsonObject literal = binding(envelope(lookup(null, "pdo\\tpass", null)), 0);

        assertEquals("name", interpreted.getAsJsonObject("qualifier").get("kind").getAsString());
        assertEquals("pdo\tuser", interpreted.getAsJsonObject("qualifier").get("value").getAsString());
        assertEquals("pdo\\tpass", literal.getAsJsonObject("qualifier").get("value").getAsString());
    }

    /**
     * {@code Registry::DEFAULT_STORE} holds a value the source does not state. Reading it as
     * {@code Registry::class} would name the wrong implementation and call it resolution "static".
     */
    @Test
    void refusesAClassConstantThatIsNotClass() {
        addFile("src/Module/RegistryModule.php", REGISTRY_MODULE);

        JsonObject bound = binding(envelope(lookup("StoreInterface", null, null)), 0);
        JsonObject unresolved = envelope(lookup("Registry", null, null))
            .getAsJsonArray("unresolved").get(0).getAsJsonObject();

        assertEquals("dynamic-unresolved", bound.get("resolution").getAsString());
        assertFalse(bound.has("implementation"), bound::toString);
        assertEquals("interface-unreadable", unresolved.get("reason").getAsString());
    }

    /** {@code bind('')} passes Ray.Di's own default: it binds a name, it is not unreadable. */
    @Test
    void readsTheStringFormsOfBind() {
        addFile("src/Module/StringModule.php", STRING_MODULE);

        JsonObject empty = binding(envelope(lookup(null, "AppName", null)), 0);
        JsonObject named = binding(envelope(lookup("ClockInterface", null, null)), 0);

        assertFalse(empty.has("interface"), empty::toString);
        assertFalse(empty.has("interfaceUnreadable"), empty::toString);
        assertEquals("\\MyVendor\\MyProject\\ClockInterface", named.get("interface").getAsString());
        assertEquals("\\MyVendor\\MyProject\\Module\\SystemClock", named.get("implementation").getAsString());
    }

    /** {@code to('My\Impl')} is legal Ray.Di: the target takes the string form as bind() does. */
    @Test
    void readsTheStringFormOfATarget() {
        addFile("src/Module/StringModule.php", STRING_MODULE);

        JsonObject stringTarget = binding(envelope(lookup("TimerInterface", null, null)), 0);

        assertEquals("\\MyVendor\\MyProject\\Module\\SystemTimer", stringTarget.get("implementation").getAsString());
        assertEquals("static", stringTarget.get("resolution").getAsString());
        assertFalse(stringTarget.has("targetUnreadable"), stringTarget::toString);
    }

    /**
     * A chain stored in a variable ends the walk, but Ray.Di has not finished with it. Calling that
     * "untargeted" would claim Ray.Di builds the interface itself while the next line names an
     * implementation.
     */
    @Test
    void doesNotCallAChainItCannotFollowUntargeted() {
        addFile("src/Module/SplitModule.php", SPLIT_MODULE);

        JsonObject binding = binding(envelope(lookup("ClockInterface", null, null)), 0);
        JsonObject filtered = envelope(lookup("ClockInterface", "utc", null));

        assertEquals("unknown", binding.get("boundBy").getAsString());
        assertEquals("dynamic-unresolved", binding.get("resolution").getAsString());
        // The qualifier is on the tail this could not read, so a qualifier query cannot decide it.
        assertTrue(filtered.getAsJsonArray("bindings").isEmpty(), filtered::toString);
        assertEquals(
            "chain-unreadable",
            filtered.getAsJsonArray("unresolved").get(0).getAsJsonObject().get("reason").getAsString()
        );
    }

    /**
     * A broken chain that named its qualifier before it broke answers a qualifier query outright:
     * the tail cannot take back a qualifier the source already states.
     */
    @Test
    void answersAQualifierQueryFromABrokenChainThatAlreadyNamedOne() {
        addFile("src/Module/SplitModule.php", SPLIT_MODULE
            .replace("$bind = $this->bind(ClockInterface::class);", "$bind = $this->bind(ClockInterface::class)->annotatedWith('utc');")
            .replace("$bind->annotatedWith('utc')->to(SystemClock::class);", "$bind->to(SystemClock::class);"));

        JsonObject envelope = envelope(lookup("ClockInterface", "utc", null));

        assertEquals(1, envelope.getAsJsonArray("bindings").size(), envelope::toString);
        assertTrue(envelope.getAsJsonArray("unresolved").isEmpty(), envelope::toString);
    }

    /**
     * A trait cannot extend anything, but the bind it hosts runs in the module that uses it.
     * Refusing to read it would answer "nothing binds this interface" and count nothing scanned.
     */
    @Test
    void readsABindWrittenInATraitAModuleUses() {
        addFile("src/Module/TraitModule.php", TRAIT_MODULE);

        JsonObject envelope = envelope(lookup("ClockInterface", null, null));
        JsonObject binding = binding(envelope, 0);

        assertEquals("\\MyVendor\\MyProject\\SystemClock", binding.get("implementation").getAsString());
        assertEquals(1, envelope.getAsJsonObject("scan").get("bindings").getAsInt());
    }

    /** A returned bind is finished by its caller, so it is not Ray.Di's untargeted binding either. */
    @Test
    void doesNotCallAReturnedBindUntargeted() {
        addFile("src/Module/ReturnModule.php", RETURN_MODULE);

        JsonObject binding = binding(envelope(lookup("ClockInterface", null, null)), 0);

        assertEquals("unknown", binding.get("boundBy").getAsString());
    }

    /** rename() on something that is not a module renames a file, not a binding. */
    @Test
    void doesNotReadARenameThatIsNotARayDiRename() {
        addFile("src/MoveFile.php", MOVE_FILE);

        JsonObject envelope = envelope(lookup(null, null, null));

        assertTrue(envelope.getAsJsonArray("unresolved").isEmpty(), envelope::toString);
        assertEquals(0, envelope.getAsJsonObject("scan").get("renames").getAsInt());
    }

    /**
     * A four-argument rename moves the binding onto its last argument, so a query for the
     * interface it lands on has to see it -- argument 0 names the one it came from.
     */
    @Test
    void reportsARenameByTheInterfaceItMovesTheBindingOnto() {
        addFile("src/Module/MoveModule.php", MOVE_MODULE);

        JsonObject onTarget = envelope(lookup("TargetInterface", null, null));
        JsonObject onSource = envelope(lookup("SourceInterface", null, null));
        JsonObject onOther = envelope(lookup("OtherInterface", null, null));

        assertEquals(1, onTarget.getAsJsonArray("unresolved").size(), onTarget::toString);
        assertEquals(1, onSource.getAsJsonArray("unresolved").size(), onSource::toString);
        assertTrue(onOther.getAsJsonArray("unresolved").isEmpty(), onOther::toString);
    }

    /**
     * A module may inherit {@code configure()} from a base module and declare none of its own; the
     * rename it makes is still a rename. Ray.Di reads an empty target interface as the source one,
     * so this rename moves nothing between interfaces and answers only for RenderInterface.
     */
    @Test
    void readsARenameInAModuleThatInheritsConfigure() {
        addFile("src/Module/ProdModule.php", PROD_MODULE);

        JsonObject onRenamed = envelope(lookup("RenderInterface", null, null));
        JsonObject onOther = envelope(lookup("OtherInterface", null, null));

        assertEquals(1, onRenamed.getAsJsonObject("scan").get("renames").getAsInt());
        assertEquals(1, onRenamed.getAsJsonArray("unresolved").size(), onRenamed::toString);
        assertTrue(onOther.getAsJsonArray("unresolved").isEmpty(), onOther::toString);
    }

    /**
     * {@code to()} names a class, so an argument this cannot read leaves the binding with no class
     * at all. Saying so separates it from {@code toInstance()}, which names no class by design.
     */
    @Test
    void saysSoWhenATargetNamesAClassItCannotRead() {
        addFile("src/Module/RegistryModule.php", REGISTRY_MODULE);
        addFile("src/Module/CacheModule.php", CACHE_MODULE);

        JsonObject unreadable = binding(envelope(lookup("StoreInterface", null, null)), 0);
        JsonObject instance = binding(envelope(lookup(null, "AppName", null)), 0);

        assertTrue(unreadable.get("targetUnreadable").getAsBoolean(), unreadable::toString);
        assertFalse(unreadable.has("targetClass"), unreadable::toString);
        assertFalse(instance.has("targetUnreadable"), instance::toString);
    }

    /** A rename whose arguments are variables is the one that can most easily be wrong about. */
    @Test
    void reportsARenameWhoseArgumentsItCannotRead() {
        addFile("src/Module/DynamicRenameModule.php", DYNAMIC_RENAME_MODULE);

        JsonObject envelope = envelope(lookup("AnyInterface", null, null));
        JsonObject unresolved = envelope.getAsJsonArray("unresolved").get(0).getAsJsonObject();

        assertEquals(1, envelope.getAsJsonObject("scan").get("renames").getAsInt());
        assertEquals("rename-not-applied", unresolved.get("reason").getAsString());
        assertFalse(unresolved.has("interface"), unresolved::toString);
    }

    @Test
    void countsWhatItRead() {
        addFile("src/Module/AppModule.php", APP_MODULE);
        addFile("src/Module/CacheModule.php", CACHE_MODULE);
        addFile("src/EventListener.php", EVENT_LISTENER);

        JsonObject scan = envelope(lookup("Nothing\\AtAll", null, null)).getAsJsonObject("scan");

        assertEquals("src", scan.get("moduleRoot").getAsString());
        assertEquals(3, scan.get("files").getAsInt());
        assertEquals(2, scan.get("moduleFiles").getAsInt());
        assertEquals(7, scan.get("bindings").getAsInt());
        assertEquals(0, scan.get("renames").getAsInt());
    }

    /** An interface that nothing binds is an answer, not an error. */
    @Test
    void answersOkWithNoBindingForAnInterfaceNothingBinds() {
        addFile("src/Module/AppModule.php", APP_MODULE);

        JsonObject envelope = envelope(lookup("NothingInterface", null, null));

        assertEquals("ok", envelope.get("status").getAsString());
        assertTrue(envelope.getAsJsonArray("bindings").isEmpty());
        assertTrue(envelope.getAsJsonArray("unresolved").isEmpty());
    }

    @Test
    void readsTheModuleRootItIsGiven() {
        addFile("src/Module/AppModule.php", APP_MODULE);
        addFile("vendor/acme/src/CacheModule.php", CACHE_MODULE);

        JsonObject envelope = envelope(lookup(null, null, "vendor/acme/src"));

        assertEquals("vendor/acme/src", envelope.getAsJsonObject("scan").get("moduleRoot").getAsString());
        assertEquals(1, envelope.getAsJsonObject("scan").get("files").getAsInt());
    }

    @Test
    void refusesAModuleRootThatLeavesTheProject() {
        JsonObject envelope = envelope(lookup(null, null, "../etc"));

        assertEquals("not_found", envelope.get("status").getAsString());
        assertTrue(envelope.get("error").getAsString().contains("../etc"), envelope::toString);
    }

    /** "." reads as the project itself, which would parse every PHP file in it, vendor included. */
    @Test
    void refusesAModuleRootThatIsTheProjectItself() {
        JsonObject envelope = envelope(lookup(null, null, "."));

        assertEquals("not_found", envelope.get("status").getAsString());
    }

    @Test
    void reportsNotFoundForAModuleRootThatDoesNotExist() {
        JsonObject envelope = envelope(lookup(null, null, "src/Nowhere"));

        assertEquals("not_found", envelope.get("status").getAsString());
        assertTrue(envelope.get("error").getAsString().contains("src/Nowhere"), envelope::toString);
    }

    /**
     * The question a directory cannot answer: of two modules binding the same interface, which one
     * the context actually installs. The unscoped answer still holds both, which is what makes the
     * scoped one worth asking for.
     */
    @Test
    void readsOnlyTheModulesAContextInstalls() {
        addContextApp();

        JsonObject scoped = envelope(lookupInContext("MailerInterface", null, "app"));
        JsonObject binding = binding(scoped, 0);

        assertEquals("ok", scoped.get("status").getAsString(), scoped::toString);
        assertEquals(1, scoped.getAsJsonArray("bindings").size(), scoped::toString);
        assertEquals("\\MyVendor\\MyProject\\SmtpMailer", binding.get("implementation").getAsString());
        assertEquals("\\MyVendor\\MyProject\\Module\\MailModule", binding.get("moduleClass").getAsString());
        assertEquals("app", binding.get("segment").getAsString());
        assertEquals(1, binding.get("priority").getAsInt());

        JsonObject unscoped = envelope(lookup("MailerInterface", null, null));
        assertEquals(2, unscoped.getAsJsonArray("bindings").size(), unscoped::toString);
        // A directory is not a tree, so nothing in that answer is ordered by a segment.
        assertFalse(binding(unscoped, 0).has("priority"), unscoped::toString);
    }

    /**
     * A module that leaves its wiring to a base module states its bindings there and nowhere else.
     * Reading only the module's own file would answer "nothing binds this" for a context whose
     * module binds it one class up -- the silence the tree tool already ended for install().
     */
    @Test
    void readsTheBindingsAModuleInheritsFromItsBaseModule() {
        addContextApp();
        addFile("src/Module/AbstractProdModule.php", CONTEXT_ABSTRACT_PROD_MODULE);
        addFile("src/Module/ProdModule.php", CONTEXT_PROD_MODULE);

        JsonObject binding = binding(envelope(lookupInContext("ClockInterface", null, "prod")), 0);

        assertEquals("\\MyVendor\\MyProject\\UtcClock", binding.get("implementation").getAsString());
        assertEquals("\\MyVendor\\MyProject\\Module\\AbstractProdModule", binding.get("moduleClass").getAsString());
        assertEquals("prod", binding.get("segment").getAsString());
    }

    /**
     * The two modules the loader adds itself are reached by no segment, and naming one for them
     * would invent a segment the caller did not write; their priority places them all the same.
     */
    @Test
    void namesNoSegmentForTheModuleTheLoaderOverridesEverythingWith() {
        addContextApp();
        addFile("vendor/bear/package/src/Module/AppMetaModule.php", CONTEXT_APP_META_MODULE);

        JsonObject binding = binding(envelope(lookupInContext("AbstractAppMeta", null, "app")), 0);

        assertEquals("\\MyVendor\\MyProject\\ProjectMeta", binding.get("implementation").getAsString());
        assertFalse(binding.has("segment"), binding::toString);
        assertEquals(0, binding.get("priority").getAsInt(), binding::toString);
    }

    /**
     * A segment nothing answers to takes its whole subtree out of the scan, and an answer that did
     * not say so would report the bindings of part of a context as the bindings of all of it.
     */
    @Test
    void saysWhichSegmentsNothingAnsweredTo() {
        addContextApp();

        JsonObject scan = envelope(lookupInContext(null, null, "app-nowhere")).getAsJsonObject("scan");

        assertEquals("app-nowhere", scan.get("context").getAsString());
        assertEquals(1, scan.getAsJsonArray("unresolvedSegments").size(), scan::toString);
        assertEquals("nowhere", scan.getAsJsonArray("unresolvedSegments").get(0).getAsString());
        // The loader's own two modules, neither installed in this fixture: the tree has holes in
        // it wherever bear/package and ray/di are not there to be read.
        assertEquals(2, scan.get("classesUnresolved").getAsInt(), scan::toString);
        assertFalse(scan.has("moduleRoot"), scan::toString);
    }

    /** The two name the scan in different terms, and answering one of them would answer neither. */
    @Test
    void refusesAContextAndAModuleRootTogether() {
        addContextApp();

        JsonObject envelope = envelope(
            DiBindingLookupService.getInstance(fixture.getProject()).lookup(null, null, "src", "app")
        );

        assertEquals("not_found", envelope.get("status").getAsString());
        assertTrue(envelope.get("error").getAsString().contains("either context or moduleRoot"), envelope::toString);
    }

    private void addContextApp() {
        addFile("src/Module/AppModule.php", CONTEXT_APP_MODULE);
        addFile("src/Module/MailModule.php", CONTEXT_MAIL_MODULE);
        addFile("src/Module/StrayModule.php", CONTEXT_STRAY_MODULE);
    }

    private String lookup(String interfaceName, String qualifier, String moduleRoot) {
        return DiBindingLookupService.getInstance(fixture.getProject())
            .lookup(interfaceName, qualifier, moduleRoot, null);
    }

    private String lookupInContext(String interfaceName, String qualifier, String context) {
        return DiBindingLookupService.getInstance(fixture.getProject())
            .lookup(interfaceName, qualifier, null, context);
    }

    private static JsonObject binding(JsonObject envelope, int position) {
        JsonArray bindings = envelope.getAsJsonArray("bindings");
        if (position >= bindings.size()) {
            throw new AssertionError("no binding at " + position + " in " + envelope);
        }

        return bindings.get(position).getAsJsonObject();
    }

    private static JsonObject envelope(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private void addFile(String relativePath, String contents) {
        fixture.addFileToProject(relativePath, contents);
    }
}
