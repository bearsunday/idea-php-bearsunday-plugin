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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The forms in these fixtures are the ones {@code bear/*} and {@code ray/*} actually write. Every
 * rule they check was read out of {@code demo-app/vendor/ray/di} rather than assumed: the container
 * key, the first-attribute-only qualifier, the untargeted self-binding, the setter injection on the
 * base class of every resource, and the three ways two bindings of one key are resolved.
 */
class DiObjectGraphServiceFixtureTest {

    /** The Ray.Di attributes a project reads through, as vendor declares them. */
    private static final String RAY_DI_INJECT_INTERFACE = """
        <?php

        namespace Ray\\Di\\Di;

        interface InjectInterface
        {
        }
        """;

    private static final String RAY_DI_INJECT = """
        <?php

        namespace Ray\\Di\\Di;

        use Attribute;

        #[Attribute(Attribute::TARGET_METHOD)]
        final class Inject implements InjectInterface
        {
            public function __construct(public bool $optional = false)
            {
            }
        }
        """;

    private static final String RAY_DI_QUALIFIER = """
        <?php

        namespace Ray\\Di\\Di;

        use Attribute;

        #[Attribute(Attribute::TARGET_CLASS)]
        final class Qualifier
        {
        }
        """;

    /** An application qualifier: an attribute class marked #[Qualifier], which Ray.Di names bindings by. */
    private static final String DB_QUALIFIER = """
        <?php

        namespace MyVendor\\MyProject\\Annotation;

        use Attribute;
        use Ray\\Di\\Di\\Qualifier;

        #[Attribute(Attribute::TARGET_PARAMETER)]
        #[Qualifier]
        final class Primary
        {
        }
        """;

    /** A qualifier no module in these fixtures binds. */
    private static final String UNBOUND_QUALIFIER = """
        <?php

        namespace MyVendor\\MyProject\\Annotation;

        use Attribute;
        use Ray\\Di\\Di\\Qualifier;

        #[Attribute(Attribute::TARGET_PARAMETER)]
        #[Qualifier]
        final class Secondary
        {
        }
        """;

    /** An attribute that is NOT a qualifier, used to check that only the FIRST one is read. */
    private static final String PLAIN_ATTRIBUTE = """
        <?php

        namespace MyVendor\\MyProject\\Annotation;

        use Attribute;

        #[Attribute(Attribute::TARGET_PARAMETER)]
        final class Documented
        {
        }
        """;

    private static final String APP_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use MyVendor\\MyProject\\Annotation\\Primary;
        use MyVendor\\MyProject\\App;
        use MyVendor\\MyProject\\AppInterface;
        use MyVendor\\MyProject\\ClockInterface;
        use MyVendor\\MyProject\\LoggerInterface;
        use MyVendor\\MyProject\\OptionsRenderer;
        use MyVendor\\MyProject\\RendererInterface;
        use MyVendor\\MyProject\\StoreInterface;
        use MyVendor\\MyProject\\StoreProvider;
        use MyVendor\\MyProject\\SystemClock;
        use MyVendor\\MyProject\\UtcClock;
        use Ray\\Di\\AbstractModule;
        use Ray\\Di\\Scope;

        class AppModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->install(new MailModule());
                $this->bind(AppInterface::class)->to(App::class);
                $this->bind(ClockInterface::class)->to(SystemClock::class)->in(Scope::SINGLETON);
                $this->bind(ClockInterface::class)->annotatedWith(Primary::class)->to(UtcClock::class);
                $this->bind(RendererInterface::class)->to(OptionsRenderer::class);
                $this->bind(StoreInterface::class)->toProvider(StoreProvider::class);
                $this->bind(LoggerInterface::class)->toNull();
                $this->bind()->annotatedWith('dsn')->toInstance('mysql:host=localhost');
                $this->bind()->annotatedWith(Primary::class)->toInstance(['a']);
            }
        }
        """;

    private static final String MAIL_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use MyVendor\\MyProject\\MailerInterface;
        use MyVendor\\MyProject\\SmtpMailer;
        use Ray\\Di\\AbstractModule;

        final class MailModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bind(MailerInterface::class)->to(SmtpMailer::class);
            }
        }
        """;

    /** The entry, with a setter injection brought in by a trait -- the shape every BEAR resource has. */
    private static final String APP = """
        <?php

        namespace MyVendor\\MyProject;

        interface AppInterface
        {
        }

        final class App implements AppInterface
        {
            use RendererInject;

            public function __construct(
                private readonly ClockInterface $clock,
                private readonly MailerInterface $mailer,
            ) {
            }
        }
        """;

    private static final String RENDERER_INJECT = """
        <?php

        namespace MyVendor\\MyProject;

        use Ray\\Di\\Di\\Inject;

        trait RendererInject
        {
            #[Inject(optional: true)]
            public function setRenderer(RendererInterface $renderer): void
            {
                $this->renderer = $renderer;
            }
        }
        """;

    /** Every parameter form the key is built out of: a qualifier, a scalar name, and a plain type. */
    private static final String MAILER = """
        <?php

        namespace MyVendor\\MyProject;

        use MyVendor\\MyProject\\Annotation\\Documented;
        use MyVendor\\MyProject\\Annotation\\Secondary;
        use MyVendor\\MyProject\\Annotation\\Primary;
        use Ray\\Di\\Di\\Named;

        interface MailerInterface
        {
        }

        final class SmtpMailer implements MailerInterface
        {
            public function __construct(
                #[Named('dsn')] private readonly string $dsn,
                #[Primary] private readonly ClockInterface $clock,
                #[Documented] #[Named('retries')] private readonly int $retries,
                #[Primary] private readonly array $hosts,
                #[Named(Primary::class)] private readonly array $imports,
                #[Secondary] private readonly string $tag,
                private readonly StoreInterface $store,
                private readonly ?AuditInterface $audit = null,
            ) {
            }
        }
        """;

    private static final String SUPPORT = """
        <?php

        namespace MyVendor\\MyProject;

        interface ClockInterface
        {
        }

        final class SystemClock implements ClockInterface
        {
        }

        final class UtcClock implements ClockInterface
        {
        }

        interface RendererInterface
        {
        }

        final class OptionsRenderer implements RendererInterface
        {
        }

        interface LoggerInterface
        {
        }

        interface AuditInterface
        {
        }

        interface StoreInterface
        {
        }

        final class StoreProvider
        {
            public function __construct(private readonly ClockInterface $clock)
            {
            }

            public function get(): StoreInterface
            {
            }
        }
        """;

    /**
     * A module written the way {@code BEAR\\Sunday\\Module\\Constant\\NamedModule} is written, under a
     * name of the project's own: nothing in the reading matches on the class name, so a module of
     * one's own in this shape is read the same way the framework's is.
     */
    /** An entry whose every parameter is keyed by a name alone, which is what an array binds. */
    private static final String SETTINGS = """
        <?php

        namespace MyVendor\\MyProject;

        use Ray\\Di\\Di\\Named;

        final class Settings
        {
            public function __construct(
                #[Named('retries')] private readonly string $retries,
                #[Named('timeout')] private readonly string $timeout,
                #[Named('elsewhere')] private readonly string $elsewhere,
            ) {
            }
        }
        """;

    private static final String CONSTANTS_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        final class ConstantsModule extends AbstractModule
        {
            public function __construct(private readonly array $names)
            {
                parent::__construct();
            }

            protected function configure(): void
            {
                foreach ($this->names as $annotatedWith => $instance) {
                    $this->bind()->annotatedWith($annotatedWith)->toInstance($instance);
                }
            }
        }
        """;

    private CodeInsightTestFixture fixture;

    /** The light fixture recipe the binding lookup test uses; see its setUp for why. */
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
     * The whole point in one answer: an interface, the class a module binds it to, and what THAT
     * class is built out of -- which no single file states and no text search can follow.
     */
    @Test
    void followsAnInterfaceThroughItsBindingIntoWhatTheImplementationNeeds() {
        addApp();

        JsonObject envelope = envelope(graph("AppInterface", "app"));
        JsonObject entry = node(envelope, "\\MyVendor\\MyProject\\AppInterface-");
        JsonObject clock = node(envelope, "\\MyVendor\\MyProject\\ClockInterface-");

        assertEquals("ok", envelope.get("status").getAsString(), envelope::toString);
        assertEquals("static", entry.get("resolution").getAsString(), entry::toString);
        assertEquals("\\MyVendor\\MyProject\\App", entry.get("implementation").getAsString());
        assertEquals("\\MyVendor\\MyProject\\SystemClock", clock.get("implementation").getAsString());
        assertEquals("Scope::SINGLETON", clock.get("scope").getAsString(), clock::toString);
        assertTrue(hasEdge(envelope, entry.get("key").getAsString(), clock.get("key").getAsString()));
    }

    /**
     * With no className and no uri the entry is the application class itself, not the interface the
     * bootstrap resolves. {@code AppMetaModule} binds that interface with
     * {@code ->to($this->appMeta->name . '\\Module\\App')} -- a class name built while the
     * application runs -- so a graph started at the interface is one node long, while the class it
     * names is knowable from the app namespace.
     */
    @Test
    void startsFromTheClassTheApplicationIsBuiltFromWhenGivenNoneItself() {
        addApp();
        addFile("src/Module/App.php", """
            <?php

            namespace MyVendor\\MyProject\\Module;

            use MyVendor\\MyProject\\ClockInterface;

            final class App
            {
                public function __construct(public readonly ClockInterface $clock)
                {
                }
            }
            """);

        JsonObject envelope = envelope(
            DiObjectGraphService.getInstance(fixture.getProject()).graph(null, null, "app", false)
        );

        assertEquals("ok", envelope.get("status").getAsString(), envelope::toString);
        assertEquals("default", envelope.getAsJsonObject("entry").get("via").getAsString());
        assertEquals(
            "\\MyVendor\\MyProject\\Module\\App-",
            envelope.getAsJsonObject("entry").get("key").getAsString(),
            envelope::toString
        );
        // Walked, not merely named: the whole reason for starting at the class.
        assertNotNull(node(envelope, "\\MyVendor\\MyProject\\ClockInterface-"), envelope::toString);
    }

    /**
     * A module that binds in a loop -- {@code NamedModule(['defaultSchemeType' => 'content'])} is the
     * one every BEAR application installs -- states its qualifier in a variable, so the key cannot be
     * read. "Nothing binds this" would then be a confident answer about a key one of those may bind,
     * and the node says how many were unreadable instead.
     */
    @Test
    void doesNotClaimAKeyIsUnboundWhenABindingCouldNotBeFiled() {
        addApp("\n        $this->install(new LoopModule());");
        addFile("src/Module/LoopModule.php", """
            <?php

            namespace MyVendor\\MyProject\\Module;

            use Ray\\Di\\AbstractModule;

            final class LoopModule extends AbstractModule
            {
                public function __construct(private readonly array $names)
                {
                    parent::__construct();
                }

                protected function configure(): void
                {
                    foreach ($this->names as $name => $value) {
                        $this->bind()->annotatedWith($name)->toInstance($value);
                    }
                }
            }
            """);

        JsonObject envelope = envelope(graph("AppInterface", "app"));
        JsonObject unbound = node(envelope, "-\\MyVendor\\MyProject\\Annotation\\Secondary");

        assertEquals("unbound", unbound.get("resolution").getAsString(), unbound::toString);
        assertTrue(unbound.get("keysUnreadable").getAsInt() > 0, unbound::toString);
    }

    /**
     * {@code AnnotatedClass::getNewInstance()} walks the methods {@code get_class_methods()} hands
     * it, so a setter a TRAIT brings in is an injection point. In BEAR that is the common case --
     * {@code ResourceObject} itself puts one on the base class of every resource -- and a graph that
     * walked constructors alone would miss the most characteristic edge in the framework.
     */
    @Test
    void walksASetterInjectionBroughtInByATrait() {
        addApp();

        JsonObject envelope = envelope(graph("AppInterface", "app"));
        JsonObject edge = edge(envelope, "\\MyVendor\\MyProject\\RendererInterface-");

        assertNotNull(edge, envelope::toString);
        assertEquals("setter-param", edge.get("kind").getAsString());
        assertEquals("setRenderer", edge.get("method").getAsString());
        // #[Inject(optional: true)] -- a missing binding is skipped rather than thrown for.
        assertTrue(edge.get("optional").getAsBoolean(), edge::toString);
        assertEquals(
            "\\MyVendor\\MyProject\\OptionsRenderer",
            node(envelope, "\\MyVendor\\MyProject\\RendererInterface-").get("implementation").getAsString()
        );
    }

    /**
     * {@code Argument::getType()} keys a scalar under an EMPTY type, so the key for
     * {@code #[Named('dsn')] string $dsn} is {@code "-dsn"} and not {@code "string-dsn"}. Keying it
     * the other way would look for a binding no module can ever have made.
     */
    @Test
    void keysAScalarParameterUnderItsNameAloneAsRayDiDoes() {
        addApp();

        JsonObject envelope = envelope(graph("AppInterface", "app"));
        JsonObject dsn = node(envelope, "-dsn");

        assertNotNull(dsn, envelope::toString);
        assertEquals("", dsn.get("type").getAsString());
        assertEquals("dsn", dsn.get("name").getAsString());
        assertEquals("instance", dsn.get("resolution").getAsString(), dsn::toString);
    }

    /**
     * An array carrying a qualifier is how a framework module hands configuration in -- bear/resource
     * writes {@code #[ImportAppConfig] private array $importAppConfig}. The type half is empty, as
     * for any other value, and the name half is the attribute class all the same.
     */
    @Test
    void keysAQualifiedArrayUnderItsQualifierAndNoType() {
        addApp();

        JsonObject envelope = envelope(graph("AppInterface", "app"));
        JsonObject hosts = node(envelope, "-\\MyVendor\\MyProject\\Annotation\\Primary");

        assertNotNull(hosts, envelope::toString);
        assertEquals("", hosts.get("type").getAsString());
    }

    /**
     * {@code #[Named(Foo::class)]} puts a class name inside the attribute's string -- which is how
     * {@code bear/package} names its imported-app config -- and Ray.Di reads it as the name like any
     * other. Reading only quoted literals left the key half-guessed and the edge marked unreadable.
     */
    @Test
    void readsAClassConstantGivenToNamedAsTheNameItIs() {
        addApp();

        JsonObject envelope = envelope(graph("AppInterface", "app"));
        JsonObject key = node(envelope, "-\\MyVendor\\MyProject\\Annotation\\Primary");

        assertEquals("instance", key.get("resolution").getAsString(), key::toString);
        assertFalse(
            envelope.getAsJsonObject("scan").has("qualifiersUnreadable"),
            envelope::toString
        );
    }

    /**
     * Ray.Di binds InjectorInterface in PHP, not in a module: Injector::__construct() does it after
     * the container is built, so it beats anything a module said. Reporting it unbound would report
     * a failure no application has -- and every ProviderInterface in bear/resource takes one.
     */
    @Test
    void answersForTheKeysTheContainerBindsWithoutAModule() {
        addApp();
        addFile("src/Wired.php", """
            <?php

            namespace MyVendor\\MyProject;

            use Ray\\Di\\InjectorInterface;

            final class Wired
            {
                public function __construct(private readonly InjectorInterface $injector)
                {
                }
            }
            """);

        JsonObject envelope = envelope(graph("Wired", "app"));

        assertEquals(
            "builtin",
            node(envelope, "\\Ray\\Di\\InjectorInterface-").get("resolution").getAsString(),
            envelope::toString
        );
    }

    /** A qualifier attribute names the binding by its own CLASS, which is what annotatedWith() took. */
    @Test
    void keysAQualifierAttributeUnderTheAttributeClass() {
        addApp();

        JsonObject envelope = envelope(graph("AppInterface", "app"));
        String key = "\\MyVendor\\MyProject\\ClockInterface-\\MyVendor\\MyProject\\Annotation\\Primary";
        JsonObject qualified = node(envelope, key);

        assertNotNull(qualified, envelope::toString);
        assertEquals("\\MyVendor\\MyProject\\UtcClock", qualified.get("implementation").getAsString());
    }

    /**
     * {@code Name::withAttributes()} reads {@code $attributes[0]} and nothing else, so a #[Named]
     * written second names nothing at all. Reading every attribute would find a qualifier Ray.Di
     * never applies, and answer for a binding the application does not use.
     */
    @Test
    void readsOnlyTheFirstAttributeOnAParameterAsRayDiDoes() {
        addApp();

        JsonObject envelope = envelope(graph("AppInterface", "app"));

        // #[Documented] is first and names nothing, so the retries argument is keyed "-" like any
        // other unqualified scalar -- not "-retries".
        assertNotNull(node(envelope, "-"), envelope::toString);
        assertTrue(maybeNode(envelope, "-retries") == null, envelope::toString);
    }

    /**
     * A provider is built by the container like anything else, so its own dependencies belong in
     * the graph. What its get() returns does not: only a running provider knows that, which is the
     * whole reason the binding form exists.
     */
    @Test
    void walksAProvidersOwnDependenciesButNotWhatItReturns() {
        addApp();

        JsonObject envelope = envelope(graph("AppInterface", "app"));
        JsonObject store = node(envelope, "\\MyVendor\\MyProject\\StoreInterface-");

        assertEquals("provider", store.get("resolution").getAsString(), store::toString);
        assertEquals("\\MyVendor\\MyProject\\StoreProvider", store.get("implementation").getAsString());
        assertTrue(
            hasEdge(envelope, "\\MyVendor\\MyProject\\StoreInterface-", "\\MyVendor\\MyProject\\ClockInterface-"),
            envelope::toString
        );
    }

    @Test
    void stopsAtABindingWhoseTargetIsNoClassToWalk() {
        addApp();
        addFile("src/Reporter.php", """
            <?php

            namespace MyVendor\\MyProject;

            final class Reporter
            {
                public function __construct(private readonly LoggerInterface $logger)
                {
                }
            }
            """);

        JsonObject envelope = envelope(graph("Reporter", "app"));

        assertEquals(
            "null-object",
            node(envelope, "\\MyVendor\\MyProject\\LoggerInterface-").get("resolution").getAsString()
        );
    }

    /**
     * Ray.Di binds an unbound concrete class on the spot only where {@code Injector::getInstance()}
     * catches {@code Untargeted} -- at the entry. Below it, {@code Arguments::getParameter()} lets
     * {@code Unbound} out, and {@code Ray\\Compiler\\InstanceScript::addArg()} throws the same. So an
     * unbound node in the middle of a graph is not a gap in this answer; it is what the application
     * would throw.
     */
    @Test
    void bindsAnUnboundConcreteClassAtTheEntryAndNowhereBelowIt() {
        addApp();
        addFile("src/Standalone.php", """
            <?php

            namespace MyVendor\\MyProject;

            final class Standalone
            {
                public function __construct(private readonly AuditInterface $audit)
                {
                }
            }
            """);

        JsonObject envelope = envelope(graph("Standalone", "app"));

        assertEquals(
            "entry-untargeted",
            node(envelope, "\\MyVendor\\MyProject\\Standalone-").get("resolution").getAsString(),
            envelope::toString
        );
        assertEquals(
            "unbound",
            node(envelope, "\\MyVendor\\MyProject\\AuditInterface-").get("resolution").getAsString(),
            envelope::toString
        );
    }

    /**
     * An unbound key a default value covers is not the failure the same key is without one:
     * {@code Arguments::getParameter()} catches {@code Unbound} and uses the default.
     */
    @Test
    void marksAnEdgeWhoseParameterHasADefaultToFallBackOn() {
        addApp();

        JsonObject envelope = envelope(graph("AppInterface", "app"));
        JsonObject edge = edge(envelope, "\\MyVendor\\MyProject\\AuditInterface-");

        assertNotNull(edge, envelope::toString);
        assertTrue(edge.get("defaultAvailable").getAsBoolean(), edge::toString);
    }

    /** A graph that leads back into itself is walked once and the edge back is marked, not followed. */
    @Test
    void marksAnEdgeThatLeadsBackIntoThePathItCameFrom() {
        addFile("src/Cycle.php", """
            <?php

            namespace MyVendor\\MyProject;

            final class Ping
            {
                public function __construct(private readonly Pong $pong)
                {
                }
            }

            final class Pong
            {
                public function __construct(private readonly Ping $ping)
                {
                }
            }
            """);
        addApp("\n        $this->install(new CycleModule());");
        addFile("src/Module/CycleModule.php", """
            <?php

            namespace MyVendor\\MyProject\\Module;

            use MyVendor\\MyProject\\Ping;
            use MyVendor\\MyProject\\Pong;
            use Ray\\Di\\AbstractModule;

            final class CycleModule extends AbstractModule
            {
                protected function configure(): void
                {
                    $this->bind(Ping::class);
                    $this->bind(Pong::class);
                }
            }
            """);

        JsonObject envelope = envelope(graph("Ping", "app"));
        JsonObject back = edge(envelope, "\\MyVendor\\MyProject\\Ping-");

        assertEquals("ok", envelope.get("status").getAsString(), envelope::toString);
        assertNotNull(back, envelope::toString);
        assertTrue(back.get("cycle").getAsBoolean(), back::toString);
    }

    /**
     * A later bind() in one module REPLACES an earlier one -- {@code register()} does
     * {@code $container[$index] = $bound} -- while the one it replaced is a binding the source
     * plainly makes, and is named rather than dropped.
     */
    @Test
    void takesTheLaterOfTwoBindingsWrittenInOneModule() {
        addApp("\n        $this->install(new TwiceModule());");
        addFile("src/Module/TwiceModule.php", """
            <?php

            namespace MyVendor\\MyProject\\Module;

            use MyVendor\\MyProject\\SystemClock;
            use MyVendor\\MyProject\\TimerInterface;
            use MyVendor\\MyProject\\UtcClock;
            use Ray\\Di\\AbstractModule;

            final class TwiceModule extends AbstractModule
            {
                protected function configure(): void
                {
                    $this->bind(TimerInterface::class)->to(SystemClock::class);
                    $this->bind(TimerInterface::class)->to(UtcClock::class);
                }
            }
            """);
        addFile("src/Timed.php", """
            <?php

            namespace MyVendor\\MyProject;

            interface TimerInterface
            {
            }

            final class Timed
            {
                public function __construct(private readonly TimerInterface $timer)
                {
                }
            }
            """);

        JsonObject envelope = envelope(graph("Timed", "app"));
        JsonObject timer = node(envelope, "\\MyVendor\\MyProject\\TimerInterface-");

        assertEquals("\\MyVendor\\MyProject\\UtcClock", timer.get("implementation").getAsString(), timer::toString);
        assertEquals(1, timer.getAsJsonArray("shadowedBy").size(), timer::toString);
        assertEquals(
            "\\MyVendor\\MyProject\\SystemClock",
            timer.getAsJsonArray("shadowedBy").get(0).getAsJsonObject().get("implementation").getAsString()
        );
    }

    /**
     * {@code Container::merge()} is {@code $this->container += $other}, which keeps what the
     * RECEIVING container already holds -- so a module's own bind beats one from a module it
     * installs, and of two installed modules the one installed first wins.
     */
    @Test
    void letsAModulesOwnBindingBeatOneFromTheModuleItInstalls() {
        addApp();

        JsonObject envelope = envelope(graph("AppInterface", "app"));
        JsonObject mailer = node(envelope, "\\MyVendor\\MyProject\\MailerInterface-");

        // AppModule installs MailModule and binds nothing for MailerInterface itself, so the
        // installed module supplies it; the module it was read from is what says so.
        assertEquals("\\MyVendor\\MyProject\\SmtpMailer", mailer.get("implementation").getAsString());
        assertEquals("\\MyVendor\\MyProject\\Module\\MailModule", mailer.get("moduleClass").getAsString());
    }

    /**
     * {@code override()} reverses the merge: Ray.Di merges the RECEIVER into the named module and
     * then keeps the named module's container, so the module named in the call wins -- while the
     * walk reaches the receiver first. {@code BEAR\\Package\\PackageModule} does exactly this, which
     * puts it in the tree of every BEAR application.
     */
    @Test
    void letsAnOverriddenModuleBeatTheModuleThatOverrodeWithIt() {
        addApp("\n        $this->override(new NullClockModule());");
        addFile("src/Module/NullClockModule.php", """
            <?php

            namespace MyVendor\\MyProject\\Module;

            use MyVendor\\MyProject\\ClockInterface;
            use MyVendor\\MyProject\\UtcClock;
            use Ray\\Di\\AbstractModule;

            final class NullClockModule extends AbstractModule
            {
                protected function configure(): void
                {
                    $this->bind(ClockInterface::class)->to(UtcClock::class);
                }
            }
            """);

        JsonObject envelope = envelope(graph("AppInterface", "app"));
        JsonObject clock = node(envelope, "\\MyVendor\\MyProject\\ClockInterface-");

        assertEquals("\\MyVendor\\MyProject\\UtcClock", clock.get("implementation").getAsString(), clock::toString);
        assertEquals("\\MyVendor\\MyProject\\Module\\NullClockModule", clock.get("moduleClass").getAsString());
    }

    /** A context is refused rather than defaulted: the wiring lives where the default would not look. */
    @Test
    void refusesToAnswerWithoutAContext() {
        addApp();

        JsonObject envelope = envelope(
            DiObjectGraphService.getInstance(fixture.getProject()).graph("AppInterface", null, "  ", false)
        );

        assertEquals("not_found", envelope.get("status").getAsString());
        assertTrue(envelope.get("error").getAsString().contains("context"), envelope::toString);
    }

    @Test
    void countsWhatItWalkedAndWhatItCouldNotApply() {
        addApp();

        JsonObject scan = envelope(graph("AppInterface", "app")).getAsJsonObject("scan");

        assertEquals("app", scan.get("context").getAsString());
        assertTrue(scan.get("nodes").getAsInt() > 0, scan::toString);
        assertTrue(scan.get("edges").getAsInt() > 0, scan::toString);
        assertFalse(scan.has("nodesCapped"), scan::toString);
    }

    /** The drawing is a rendering of the answer, so every node in it is a node of the answer. */
    @Test
    void drawsTheSameGraphItAnswersWith() {
        addApp();

        JsonObject envelope = envelope(
            DiObjectGraphService.getInstance(fixture.getProject()).graph("AppInterface", null, "app", true)
        );
        String mermaid = envelope.get("diagram").getAsString();

        assertTrue(mermaid.startsWith("flowchart TD"), mermaid);
        assertTrue(mermaid.contains("SystemClock"), mermaid);
        assertEquals(
            envelope.getAsJsonArray("nodes").size(),
            envelope.getAsJsonObject("diagramNodes").size(),
            mermaid
        );
    }

    // --------------------------------------------------------------- helpers

    /**
     * The shape {@code BEAR\\Sunday\\Module\\Constant\\NamedModule} has, and the reason this reading
     * exists: the VALUES are calls no reading of the source can evaluate, while the KEYS are string
     * literals in the installing module's own file -- and a key is all the container needs. Read as
     * one chain this binds under a variable and could be filed under nothing at all.
     */
    @Test
    void bindsEveryEntryOfAnArrayAModuleWasInstalledWith() {
        addApp("\n        $this->install(new ConstantsModule(['retries' => getenv('RETRIES')]));");
        addFile("src/Module/ConstantsModule.php", CONSTANTS_MODULE);
        addFile("src/Settings.php", SETTINGS);

        JsonObject envelope = envelope(graph("Settings", "app"));
        JsonObject retries = node(envelope, "-retries");

        assertEquals("instance", retries.get("resolution").getAsString(), retries::toString);
        // The bind is written in the module that loops; the entry is written in the module that
        // installs it. Both are named, because they are in different files.
        assertEquals("\\MyVendor\\MyProject\\Module\\ConstantsModule", retries.get("moduleClass").getAsString());
        assertEquals("\\MyVendor\\MyProject\\Module\\AppModule", retries.get("installedBy").getAsString());
        assertEquals("src/Module/AppModule.php", retries.get("filePath").getAsString(), retries::toString);
    }

    /**
     * A module that binds an array is one container per INSTALL and not one per class: Ray.Di
     * merges each install separately, so of two installs of one module the FIRST keeps the key.
     * Folding the two into one node -- which is what walking a module once per class name does --
     * would drop every name the second install binds.
     */
    @Test
    void letsTheFirstOfTwoInstallsOfOneArrayModuleKeepTheKey() {
        addApp("""

                $this->install(new ConstantsModule(['retries' => 'first']));
                $this->install(new ConstantsModule(['retries' => 'second', 'timeout' => '30']));"""
        );
        addFile("src/Module/ConstantsModule.php", CONSTANTS_MODULE);
        addFile("src/Settings.php", SETTINGS);

        JsonObject envelope = envelope(graph("Settings", "app"));
        JsonObject retries = node(envelope, "-retries");

        assertEquals("instance", retries.get("resolution").getAsString(), retries::toString);
        // The loser is named rather than dropped: a reader whose second array is not taking effect
        // has no other way to find out why. It is the LATER of the two lines, which is the whole
        // rule: what the first install put in the container, the second one does not replace.
        assertEquals(1, retries.getAsJsonArray("shadowedBy").size(), retries::toString);
        assertTrue(
            retries.get("line").getAsInt()
                < retries.getAsJsonArray("shadowedBy").get(0).getAsJsonObject().get("line").getAsInt(),
            retries::toString
        );
        // The second install is walked as well, or the name only it binds would be unbound.
        assertEquals("instance", node(envelope, "-timeout").get("resolution").getAsString());
    }

    /**
     * {@code Container::merge()} keeps what the RECEIVING container holds, so a module's own bind
     * beats one from a module it installs -- including when it is written BEFORE the install, which
     * is the case a reading that just took the later statement would get backwards.
     */
    @Test
    void letsAModulesOwnBindingBeatAnEntryOfAnArrayItInstallsAfterIt() {
        addApp("""

                $this->bind()->annotatedWith('retries')->toInstance('own');
                $this->install(new ConstantsModule(['retries' => 'installed']));"""
        );
        addFile("src/Module/ConstantsModule.php", CONSTANTS_MODULE);
        addFile("src/Settings.php", SETTINGS);

        JsonObject envelope = envelope(graph("Settings", "app"));
        JsonObject retries = node(envelope, "-retries");

        assertEquals("\\MyVendor\\MyProject\\Module\\AppModule", retries.get("moduleClass").getAsString(), retries::toString);
        assertFalse(retries.has("installedBy"), retries::toString);
    }

    /**
     * Two entries of ONE array under one key: PHP builds the array before the module ever sees it
     * and keeps the last, and so does this -- the two are the same container, which is the one case
     * where a later binding replaces an earlier one.
     */
    @Test
    void keepsTheLastOfTwoEntriesWrittenUnderOneKeyInOneArray() {
        addApp("\n        $this->install(new ConstantsModule(['retries' => 'first', 'retries' => 'last']));");
        addFile("src/Module/ConstantsModule.php", CONSTANTS_MODULE);
        addFile("src/Settings.php", SETTINGS);

        JsonObject envelope = envelope(graph("Settings", "app"));
        JsonObject retries = node(envelope, "-retries");

        assertEquals("instance", retries.get("resolution").getAsString(), retries::toString);
        assertEquals(1, retries.getAsJsonArray("shadowedBy").size(), retries::toString);
    }

    /**
     * An entry whose key is not a literal is left as unreadable as the whole loop used to be. The
     * expansion is partial, and a node this walk calls unbound still says how sure that is --
     * because the entry it could not read may be the very key being asked about.
     */
    @Test
    void countsAnEntryWhoseKeyTheSourceDoesNotState() {
        addApp("\n        $this->install(new ConstantsModule(['retries' => '3', self::TAG => 'x']));");
        addFile("src/Module/ConstantsModule.php", CONSTANTS_MODULE);
        addFile("src/Settings.php", SETTINGS);

        JsonObject envelope = envelope(graph("Settings", "app"));
        JsonObject unbound = node(envelope, "-elsewhere");

        assertEquals("instance", node(envelope, "-retries").get("resolution").getAsString());
        assertEquals("unbound", unbound.get("resolution").getAsString(), unbound::toString);
        assertEquals(1, unbound.get("keysUnreadable").getAsInt(), unbound::toString);
    }

    /**
     * The module is found by its SHAPE, and a loop that binds under something other than its own
     * key is not that shape: the entries would all be filed under one name. Left unexpanded, and
     * counted, which is what the reading did before it could expand anything.
     */
    @Test
    void refusesToExpandALoopThatBindsUnderSomethingOtherThanItsKey() {
        addApp("\n        $this->install(new FixedModule(['retries' => '3']));");
        addFile("src/Settings.php", SETTINGS);
        addFile("src/Module/FixedModule.php", """
            <?php

            namespace MyVendor\\MyProject\\Module;

            use Ray\\Di\\AbstractModule;

            final class FixedModule extends AbstractModule
            {
                public function __construct(private readonly array $names)
                {
                    parent::__construct();
                }

                protected function configure(): void
                {
                    foreach ($this->names as $name => $value) {
                        $this->bind()->annotatedWith($value)->toInstance($name);
                    }
                }
            }
            """);

        JsonObject envelope = envelope(graph("Settings", "app"));
        JsonObject retries = node(envelope, "-retries");

        assertEquals("unbound", retries.get("resolution").getAsString(), retries::toString);
        assertTrue(retries.get("keysUnreadable").getAsInt() > 0, retries::toString);
    }

    /**
     * An install whose array the source does not state binds names this cannot list. Reported as
     * its own count, and still counted against every unbound answer, because the alternative is an
     * expansion that quietly made the reading surer than it is.
     */
    @Test
    void saysWhenAModuleBindsAnArrayItWasNotShown() {
        addApp("\n        $this->install(new ConstantsModule($this->names));");
        addFile("src/Module/ConstantsModule.php", CONSTANTS_MODULE);
        addFile("src/Settings.php", SETTINGS);

        JsonObject envelope = envelope(graph("Settings", "app"));
        JsonObject scan = envelope.getAsJsonObject("scan");

        assertEquals(1, scan.get("installArgumentsUnreadable").getAsInt(), scan::toString);
        assertTrue(scan.get("bindingsWithNoReadableKey").getAsInt() > 0, scan::toString);
    }

    /**
     * Two installs of one module, neither of them stating its array. Folding them into one node --
     * which is what a walk keyed by class name does -- would report the second as expanded
     * somewhere it is not, and count the names it binds once instead of twice. The scan counts two
     * installs, so the tally against unbound answers has to count two as well.
     */
    @Test
    void keepsTwoInstallsApartWhenNEITHERStatesItsArray() {
        addApp("""

                $this->install(new ConstantsModule($this->one));
                $this->install(new ConstantsModule($this->two));"""
        );
        addFile("src/Module/ConstantsModule.php", CONSTANTS_MODULE);
        addFile("src/Settings.php", SETTINGS);

        JsonObject envelope = envelope(graph("Settings", "app"));
        JsonObject scan = envelope.getAsJsonObject("scan");
        JsonObject retries = node(envelope, "-retries");

        assertEquals(2, scan.get("installArgumentsUnreadable").getAsInt(), scan::toString);
        assertEquals(2, scan.get("bindingsWithNoReadableKey").getAsInt(), scan::toString);
        assertEquals(2, retries.get("keysUnreadable").getAsInt(), retries::toString);
    }

    private void addApp() {
        addApp("");
    }

    /** @param extraInstalls lines added to AppModule::configure(), which is how a context reaches a module */
    private void addApp(String extraInstalls) {
        addFile("vendor/ray/di/src/di/Di/InjectInterface.php", RAY_DI_INJECT_INTERFACE);
        addFile("vendor/ray/di/src/di/Di/Inject.php", RAY_DI_INJECT);
        addFile("vendor/ray/di/src/di/Di/Qualifier.php", RAY_DI_QUALIFIER);
        addFile("src/Annotation/Primary.php", DB_QUALIFIER);
        addFile("src/Annotation/Documented.php", PLAIN_ATTRIBUTE);
        addFile("src/Annotation/Secondary.php", UNBOUND_QUALIFIER);
        addFile("src/Module/AppModule.php", APP_MODULE.replace(
            "$this->install(new MailModule());",
            "$this->install(new MailModule());" + extraInstalls
        ));
        addFile("src/Module/MailModule.php", MAIL_MODULE);
        addFile("src/App.php", APP);
        addFile("src/RendererInject.php", RENDERER_INJECT);
        addFile("src/SmtpMailer.php", MAILER);
        addFile("src/Support.php", SUPPORT);
    }

    private String graph(String className, String context) {
        return DiObjectGraphService.getInstance(fixture.getProject()).graph(className, null, context, false);
    }

    private static JsonObject node(JsonObject envelope, String key) {
        JsonObject node = maybeNode(envelope, key);
        if (node == null) {
            throw new AssertionError("no node " + key + " in " + envelope);
        }

        return node;
    }

    private static JsonObject maybeNode(JsonObject envelope, String key) {
        JsonArray nodes = envelope.getAsJsonArray("nodes");
        if (nodes == null) {
            throw new AssertionError("no graph at all in " + envelope);
        }
        for (JsonElement element : nodes) {
            JsonObject node = element.getAsJsonObject();
            if (key.equals(node.get("key").getAsString())) {
                return node;
            }
        }

        return null;
    }

    /** The first edge that leads to a key, whichever node it leads from. */
    private static JsonObject edge(JsonObject envelope, String to) {
        for (JsonElement element : envelope.getAsJsonArray("edges")) {
            JsonObject edge = element.getAsJsonObject();
            if (to.equals(edge.get("to").getAsString())) {
                return edge;
            }
        }

        return null;
    }

    private static boolean hasEdge(JsonObject envelope, String from, String to) {
        for (JsonElement element : envelope.getAsJsonArray("edges")) {
            JsonObject edge = element.getAsJsonObject();
            if (from.equals(edge.get("from").getAsString()) && to.equals(edge.get("to").getAsString())) {
                return true;
            }
        }

        return false;
    }

    private static JsonObject envelope(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private void addFile(String relativePath, String contents) {
        fixture.addFileToProject(relativePath, contents);
    }
}
