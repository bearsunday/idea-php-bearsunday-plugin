package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.psi.PsiFile;
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
 * The pointcuts in these fixtures are {@code bear/query-repository}'s own, copied rather than
 * invented: between them they use every form this has to evaluate -- a priority binding, a nested
 * {@code logicalOr}, a {@code logicalNot}, and an {@code annotatedWith} that names a base
 * attribute class rather than the attribute actually written on the resource.
 *
 * <p>Classes are resolved through the project index, so the light fixture recipe is the one the
 * module tree test uses.
 */
class AopPointcutLookupServiceFixtureTest {

    private static final String CACHEABLE_MODULE = """
        <?php

        namespace BEAR\\QueryRepository;

        use BEAR\\RepositoryModule\\Annotation\\AbstractCacheControl;
        use BEAR\\RepositoryModule\\Annotation\\Cacheable;
        use BEAR\\RepositoryModule\\Annotation\\Purge;
        use BEAR\\RepositoryModule\\Annotation\\Refresh;
        use Ray\\Di\\AbstractModule;

        class CacheableModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bindPriorityInterceptor(
                    $this->matcher->annotatedWith(Cacheable::class),
                    $this->matcher->startsWith('onGet'),
                    [CacheInterceptor::class],
                );
                $this->bindInterceptor(
                    $this->matcher->annotatedWith(Cacheable::class),
                    $this->matcher->logicalOr(
                        $this->matcher->startsWith('onPut'),
                        $this->matcher->logicalOr(
                            $this->matcher->startsWith('onPatch'),
                            $this->matcher->startsWith('onDelete'),
                        ),
                    ),
                    [CommandInterceptor::class],
                );
                $this->bindInterceptor(
                    $this->matcher->logicalNot(
                        $this->matcher->annotatedWith(Cacheable::class),
                    ),
                    $this->matcher->logicalOr(
                        $this->matcher->annotatedWith(Purge::class),
                        $this->matcher->annotatedWith(Refresh::class),
                    ),
                    [RefreshInterceptor::class],
                );
                $this->bindInterceptor(
                    $this->matcher->annotatedWith(AbstractCacheControl::class),
                    $this->matcher->startsWith('onGet'),
                    [HttpCacheInterceptor::class],
                );
            }
        }
        """;

    /** The attribute classes the pointcuts name; CacheControl is the one that extends another. */
    private static final String ATTRIBUTES = """
        <?php

        namespace BEAR\\RepositoryModule\\Annotation;

        #[\\Attribute]
        final class Cacheable
        {
        }

        #[\\Attribute]
        final class Purge
        {
        }

        #[\\Attribute]
        final class Refresh
        {
        }

        #[\\Attribute]
        abstract class AbstractCacheControl
        {
        }

        #[\\Attribute]
        final class CacheControl extends AbstractCacheControl
        {
        }
        """;

    /** A cacheable resource carrying a cache-control attribute that extends the named base. */
    private static final String TICKET = """
        <?php

        namespace MyVendor\\MyProject\\Resource\\App;

        use BEAR\\RepositoryModule\\Annotation\\Cacheable;
        use BEAR\\RepositoryModule\\Annotation\\CacheControl;

        #[Cacheable]
        #[CacheControl]
        class Ticket
        {
            public function onGet(string $id): static
            {
                return $this;
            }

            public function onPut(string $id): static
            {
                return $this;
            }

            public function offsetGet($offset): mixed
            {
                return null;
            }

            public function __toString(): string
            {
                return '';
            }

            protected function helper(): void
            {
            }
        }
        """;

    /** Not cacheable, and the method says what it does to the cache instead. */
    private static final String GUEST = """
        <?php

        namespace MyVendor\\MyProject\\Resource\\App;

        use BEAR\\RepositoryModule\\Annotation\\Purge;

        class Guest
        {
            #[Purge]
            public function onDelete(string $id): static
            {
                return $this;
            }
        }
        """;

    /** The two shapes a matcher takes when it cannot be decided at all. */
    private static final String DYNAMIC_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        class DynamicModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bindInterceptor(
                    $this->matcher->annotatedWith($this->attribute),
                    $this->matcher->any(),
                    [DynamicInterceptor::class],
                );
            }
        }
        """;

    /** subclassesOf on the method side is what Ray.Aop throws InvalidAnnotationException on. */
    private static final String INVALID_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use MyVendor\\MyProject\\Resource\\App\\Ticket;
        use Ray\\Di\\AbstractModule;

        class InvalidModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bindInterceptor(
                    $this->matcher->any(),
                    $this->matcher->subclassesOf(Ticket::class),
                    [WrongInterceptor::class],
                );
            }
        }
        """;

    private static final String ANY_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        class AnyModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bindInterceptor(
                    $this->matcher->any(),
                    $this->matcher->any(),
                    [LogInterceptor::class],
                );
            }
        }
        """;

    /** The app module a context reaches, so that a context-scoped scan has a tree to walk. */
    private static final String APP_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use BEAR\\QueryRepository\\CacheableModule;
        use Ray\\Di\\AbstractModule;

        class AppModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->install(new CacheableModule());
            }
        }
        """;

    /** A resource whose onHead() comes from a trait: PHP flattens it in, get_class_methods() lists it. */
    private static final String COUNTER = """
        <?php

        namespace MyVendor\\MyProject\\Resource\\App;

        trait HeadTrait
        {
            public function onHead(string $id): static
            {
                return $this;
            }
        }

        class Counter
        {
            use HeadTrait;

            public function onGet(string $id): static
            {
                return $this;
            }
        }
        """;

    /** An interface that reaches its parent through another interface, and one nothing implements. */
    private static final String TYPES = """
        <?php

        namespace MyVendor\\MyProject\\Type;

        interface Base
        {
        }

        interface Combined extends Base
        {
        }

        interface Unrelated
        {
        }
        """;

    private static final String IMPLEMENTOR = """
        <?php

        namespace MyVendor\\MyProject\\Resource\\App;

        use MyVendor\\MyProject\\Type\\Combined;

        class Report implements Combined
        {
            public function onGet(string $id): static
            {
                return $this;
            }
        }
        """;

    private static final String SUBCLASS_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use MyVendor\\MyProject\\Type\\Base;
        use MyVendor\\MyProject\\Type\\Unrelated;
        use Ray\\Di\\AbstractModule;

        class SubclassModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bindInterceptor(
                    $this->matcher->subclassesOf(Base::class),
                    $this->matcher->startsWith('onGet'),
                    [BaseInterceptor::class],
                );
                $this->bindInterceptor(
                    $this->matcher->subclassesOf(Unrelated::class),
                    $this->matcher->startsWith('onGet'),
                    [UnrelatedInterceptor::class],
                );
            }
        }
        """;

    /** The three-argument logicalAnd ray/aura-sql-module writes; Ray.Aop folds every argument. */
    private static final String THREE_ARGUMENT_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        class ThreeArgumentModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bindInterceptor(
                    $this->matcher->any(),
                    $this->matcher->logicalAnd(
                        $this->matcher->startsWith('on'),
                        $this->matcher->startsWith('onG'),
                        $this->matcher->startsWith('onGe'),
                    ),
                    [ThreeInterceptor::class],
                );
            }
        }
        """;

    /** The same prefix written with and without the leading backslash reflection's name lacks. */
    private static final String PREFIX_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        class PrefixModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bindInterceptor(
                    $this->matcher->startsWith('\\MyVendor\\MyProject'),
                    $this->matcher->startsWith('onGet'),
                    [BackslashInterceptor::class],
                );
                $this->bindInterceptor(
                    $this->matcher->startsWith('MyVendor\\MyProject'),
                    $this->matcher->startsWith('onGet'),
                    [PlainInterceptor::class],
                );
            }
        }
        """;

    /** An interceptor list this can read only part of, and a class name that is not one at all. */
    private static final String PARTIAL_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        class PartialModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bindInterceptor(
                    $this->matcher->any(),
                    $this->matcher->startsWith('onGet'),
                    [PartialA::class, $this->dynamic, PartialB::class, new Wrapper(Nested::class)],
                );
            }
        }
        """;

    /** A bind call whose arguments this cannot count, and one that names them out of order. */
    private static final String SPREAD_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        class SpreadModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bindInterceptor(...$pointcut);
            }
        }
        """;

    private static final String NAMED_ARGUMENT_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use BEAR\\RepositoryModule\\Annotation\\Cacheable;
        use Ray\\Di\\AbstractModule;

        class NamedArgumentModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bindInterceptor(
                    methodMatcher: $this->matcher->startsWith('onGet'),
                    classMatcher: $this->matcher->annotatedWith(Cacheable::class),
                    interceptors: [NamedInterceptor::class],
                );
            }
        }
        """;

    /** What Ray.Di's own AssistedInjectModule does: a matcher class of its own on the method side. */
    private static final String HAND_WRITTEN_MATCHER_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use Ray\\Di\\AbstractModule;

        class HandWrittenMatcherModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bindInterceptor(
                    $this->matcher->any(),
                    new AssistedInjectMatcher(),
                    [AssistedInterceptor::class],
                );
            }
        }
        """;

    /** Binds on the interface the wide hierarchy declares last, past where the walk stops. */
    private static final String WIDE_MODULE = """
        <?php

        namespace MyVendor\\MyProject\\Module;

        use MyVendor\\MyProject\\Type\\Iface40;
        use Ray\\Di\\AbstractModule;

        class WideModule extends AbstractModule
        {
            protected function configure(): void
            {
                $this->bindInterceptor(
                    $this->matcher->subclassesOf(Iface40::class),
                    $this->matcher->startsWith('onGet'),
                    [WideInterceptor::class],
                );
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
     * The failure this exists for: nothing at {@code onPut()} says an interceptor wraps it, and
     * the pointcut that does names the method by the spelling of its name.
     */
    @Test
    void namesTheInterceptorAPointcutPutsOnAMethod() {
        addCacheable();

        JsonObject envelope = envelope(lookup("Ticket", "onPut"));
        JsonObject method = method(envelope, "onPut");
        JsonObject interceptor = method.getAsJsonArray("interceptors").get(0).getAsJsonObject();

        assertEquals("ok", envelope.get("status").getAsString(), envelope::toString);
        assertEquals("\\BEAR\\QueryRepository\\CommandInterceptor", interceptor.get("interceptor").getAsString());
        assertEquals("\\BEAR\\QueryRepository\\CacheableModule", interceptor.get("moduleClass").getAsString());
        assertEquals(
            "annotatedWith(\\BEAR\\RepositoryModule\\Annotation\\Cacheable)",
            interceptor.get("classMatcher").getAsString()
        );
        // The nested logicalOr is written back whole: which branch matched is not the claim.
        assertTrue(
            interceptor.get("methodMatcher").getAsString().contains("startsWith('onPut')"),
            interceptor::toString
        );
        assertFalse(interceptor.get("priority").getAsBoolean(), interceptor::toString);
    }

    /**
     * Ray.Aop binds every priority pointcut before any other, so the interceptor that wraps
     * outermost is the one listed first.
     */
    @Test
    void putsPriorityInterceptorsFirst() {
        addCacheable();

        JsonArray interceptors = method(envelope(lookup("Ticket", "onGet")), "onGet")
            .getAsJsonArray("interceptors");

        assertEquals(2, interceptors.size(), interceptors::toString);
        JsonObject first = interceptors.get(0).getAsJsonObject();
        assertEquals("\\BEAR\\QueryRepository\\CacheInterceptor", first.get("interceptor").getAsString());
        assertTrue(first.get("priority").getAsBoolean(), first::toString);
        assertFalse(interceptors.get(1).getAsJsonObject().get("priority").getAsBoolean(), interceptors::toString);
    }

    /**
     * Ray.Aop reads attributes with {@code IS_INSTANCEOF}, so a pointcut naming a base attribute
     * class matches the attribute written on the resource. Comparing names alone would miss it,
     * and miss it silently.
     */
    @Test
    void matchesAnAttributeThatExtendsTheOneAMatcherNames() {
        addCacheable();

        JsonArray interceptors = method(envelope(lookup("Ticket", "onGet")), "onGet")
            .getAsJsonArray("interceptors");
        JsonObject byBaseClass = interceptors.get(1).getAsJsonObject();

        assertEquals("\\BEAR\\QueryRepository\\HttpCacheInterceptor", byBaseClass.get("interceptor").getAsString());
        // The pointcut names the base; the resource carries the subclass.
        assertEquals(
            "annotatedWith(\\BEAR\\RepositoryModule\\Annotation\\AbstractCacheControl)",
            byBaseClass.get("classMatcher").getAsString()
        );
    }

    /** A logicalNot on the class side and an attribute on the method side, both evaluated. */
    @Test
    void readsAMethodLevelAttributeUnderALogicalNot() {
        addCacheable();

        JsonObject interceptor = method(envelope(lookup("Guest", "onDelete")), "onDelete")
            .getAsJsonArray("interceptors").get(0).getAsJsonObject();

        assertEquals("\\BEAR\\QueryRepository\\RefreshInterceptor", interceptor.get("interceptor").getAsString());
        assertTrue(interceptor.get("classMatcher").getAsString().startsWith("logicalNot("), interceptor::toString);
    }

    /** The same pointcut does not reach the cacheable resource, whose class matcher says not. */
    @Test
    void leavesOutAPointcutWhoseClassMatcherDoesNotMatch() {
        addCacheable();

        JsonObject envelope = envelope(lookup("Ticket", null));

        for (String method : List.of("onGet", "onPut")) {
            for (String interceptor : interceptors(method(envelope, method))) {
                assertFalse(
                    interceptor.endsWith("RefreshInterceptor"),
                    () -> "RefreshInterceptor is bound under logicalNot(annotatedWith(Cacheable)): " + envelope
                );
            }
        }
    }

    /**
     * {@code any()} is not quite every method: {@code AnyMatcher} excludes magic methods and the
     * ones it takes as built-in, and a caller asking about offsetGet() would otherwise be told an
     * interceptor wraps it.
     */
    @Test
    void excludesMagicAndBuiltinMethodsFromAny() {
        addFile("src/Resource/App/Ticket.php", TICKET);
        addFile("src/Annotation/CacheAttributes.php", ATTRIBUTES);
        addFile("src/Module/AnyModule.php", ANY_MODULE);

        JsonObject envelope = envelope(lookupIn("Ticket", null, "src"));

        assertEquals(List.of("\\MyVendor\\MyProject\\Module\\LogInterceptor"), interceptors(method(envelope, "onGet")));
        assertFalse(hasMethod(envelope, "offsetGet"), envelope::toString);
        assertFalse(hasMethod(envelope, "__toString"), envelope::toString);
        // A protected method is not woven either: Ray.Aop binds the public ones.
        assertFalse(hasMethod(envelope, "helper"), envelope::toString);
    }

    /** An argument whose value the source does not state is undecided, and says which it was. */
    @Test
    void reportsAMatcherItCannotRead() {
        addFile("src/Resource/App/Ticket.php", TICKET);
        addFile("src/Annotation/CacheAttributes.php", ATTRIBUTES);
        addFile("src/Module/DynamicModule.php", DYNAMIC_MODULE);

        JsonObject envelope = envelope(lookupIn("Ticket", "onGet", "src"));
        JsonObject undecided = envelope.getAsJsonArray("unevaluated").get(0).getAsJsonObject();

        assertEquals("matcher-unreadable", undecided.get("reason").getAsString());
        assertEquals("$this->matcher->annotatedWith($this->attribute)", undecided.get("classMatcher").getAsString());
        assertEquals("\\MyVendor\\MyProject\\Module\\DynamicModule", undecided.get("moduleClass").getAsString());
        // Undecided is not matched: the interceptor is not reported as wrapping the method.
        assertTrue(interceptors(method(envelope, "onGet")).isEmpty(), envelope::toString);
    }

    /** Ray.Aop throws on subclassesOf as a method matcher, so the pointcut cannot be decided. */
    @Test
    void reportsSubclassesOfWrittenOnTheMethodSide() {
        addFile("src/Resource/App/Ticket.php", TICKET);
        addFile("src/Annotation/CacheAttributes.php", ATTRIBUTES);
        addFile("src/Module/InvalidModule.php", INVALID_MODULE);

        JsonObject undecided = envelope(lookupIn("Ticket", "onGet", "src"))
            .getAsJsonArray("unevaluated").get(0).getAsJsonObject();

        assertEquals("matcher-invalid", undecided.get("reason").getAsString());
        assertEquals("onGet", undecided.get("method").getAsString());
    }

    /** Asked about one method, an empty answer must say the method was looked at. */
    @Test
    void keepsAMethodItWasAskedAboutEvenWhenNothingWrapsIt() {
        addFile("src/Resource/App/Guest.php", GUEST);
        addFile("src/Annotation/CacheAttributes.php", ATTRIBUTES);
        addFile("src/Module/AnyModule.php", ANY_MODULE);

        JsonObject envelope = envelope(lookupIn("Guest", "onDelete", "src/Resource"));

        assertEquals(1, envelope.getAsJsonArray("methods").size(), envelope::toString);
        assertTrue(interceptors(method(envelope, "onDelete")).isEmpty(), envelope::toString);
        assertEquals(0, envelope.getAsJsonObject("scan").get("pointcuts").getAsInt(), envelope::toString);
    }

    @Test
    void saysSoWhenTheClassHasNoSuchMethod() {
        addCacheable();

        JsonObject envelope = envelope(lookup("Ticket", "onNowhere"));

        assertEquals("not_found", envelope.get("status").getAsString());
        assertTrue(envelope.get("error").getAsString().contains("onNowhere"), envelope::toString);
    }

    /**
     * Most pointcuts are declared in vendor, so a default scan of the app's own src would answer
     * "nothing wraps this" for a method three interceptors wrap.
     */
    @Test
    void refusesToScanWithoutAContextOrAModuleRoot() {
        addCacheable();

        JsonObject envelope = envelope(
            AopPointcutLookupService.getInstance(fixture.getProject()).lookup("Ticket", null, null, null, null)
        );

        assertEquals("not_found", envelope.get("status").getAsString());
        assertTrue(envelope.get("error").getAsString().contains("vendor"), envelope::toString);
    }

    @Test
    void refusesAContextAndAModuleRootTogether() {
        addCacheable();

        JsonObject envelope = envelope(
            AopPointcutLookupService.getInstance(fixture.getProject()).lookup("Ticket", null, null, "app", "vendor")
        );

        assertEquals("not_found", envelope.get("status").getAsString());
        assertTrue(envelope.get("error").getAsString().contains("either context or moduleRoot"), envelope::toString);
    }

    /** Under a context, a pointcut says which segment reached the module that declares it. */
    @Test
    void readsThePointcutsTheModulesOfAContextDeclare() {
        addCacheable();
        addFile("src/Module/AppModule.php", APP_MODULE);

        JsonObject envelope = envelope(
            AopPointcutLookupService.getInstance(fixture.getProject()).lookup("Ticket", null, "onPut", "app", null)
        );
        JsonObject interceptor = method(envelope, "onPut").getAsJsonArray("interceptors").get(0).getAsJsonObject();

        assertEquals("\\BEAR\\QueryRepository\\CommandInterceptor", interceptor.get("interceptor").getAsString());
        assertEquals("app", interceptor.get("segment").getAsString());
        assertEquals(1, interceptor.get("modulePriority").getAsInt());
        assertEquals("app", envelope.getAsJsonObject("scan").get("context").getAsString());
    }

    /**
     * Ray.Aop lists the methods to weave with get_class_methods(), which flattens in what a trait
     * brings. Reading only the methods a class declares itself answers "no such method" about one
     * that three interceptors may wrap -- and answers it with full confidence.
     */
    @Test
    void weavesAMethodATraitBringsIn() {
        addFile("src/Resource/App/Counter.php", COUNTER);
        addFile("src/Module/AnyModule.php", ANY_MODULE);

        JsonObject envelope = envelope(lookupIn("Counter", "onHead", "src"));

        assertEquals("ok", envelope.get("status").getAsString(), envelope::toString);
        assertEquals(
            List.of("\\MyVendor\\MyProject\\Module\\LogInterceptor"),
            interceptors(method(envelope, "onHead"))
        );
    }

    /**
     * An interface names its parents in its extends list, where getSuperClass() has nothing to
     * return -- so walking by superclass alone reported every implementor's whole hierarchy as
     * unresolved. Both halves are asserted here: the one that matches through an interface chain,
     * and the one that must answer a plain no rather than "cannot tell".
     */
    @Test
    void walksAnInterfaceChainWithoutCallingItUnresolved() {
        addFile("src/Type/Types.php", TYPES);
        addFile("src/Resource/App/Report.php", IMPLEMENTOR);
        addFile("src/Module/SubclassModule.php", SUBCLASS_MODULE);

        JsonObject envelope = envelope(lookupIn("Report", "onGet", "src"));

        assertEquals(
            List.of("\\MyVendor\\MyProject\\Module\\BaseInterceptor"),
            interceptors(method(envelope, "onGet"))
        );
        assertEquals(0, envelope.getAsJsonArray("unevaluated").size(), envelope::toString);
    }

    /**
     * Ray.Aop's Matcher declares two parameters but collects them with func_get_args(), and
     * LogicalAndMatcher folds every one -- so ray/aura-sql-module's three-argument logicalAnd is a
     * pointcut, not an unreadable expression.
     */
    @Test
    void foldsALogicalAndWrittenWithThreeArguments() {
        addFile("src/Resource/App/Ticket.php", TICKET);
        addFile("src/Annotation/CacheAttributes.php", ATTRIBUTES);
        addFile("src/Module/ThreeArgumentModule.php", THREE_ARGUMENT_MODULE);

        JsonObject envelope = envelope(lookupIn("Ticket", null, "src"));

        assertEquals(
            List.of("\\MyVendor\\MyProject\\Module\\ThreeInterceptor"),
            interceptors(method(envelope, "onGet"))
        );
        // Every branch is a prefix of onGet and 'onGe' is not one of onPut, so the fold decides
        // both -- and a method nothing wraps is not listed when the question named no method.
        assertFalse(hasMethod(envelope, "onPut"), envelope::toString);
        assertEquals(0, envelope.getAsJsonArray("unevaluated").size(), envelope::toString);
    }

    /**
     * StartsWithMatcher compares str_starts_with($class->name, $prefix), and reflection's name
     * carries no leading backslash. A prefix written with one matches nothing at runtime, so
     * matching it here would report an interceptor that never runs.
     */
    @Test
    void comparesAClassPrefixAsRayAopComparesIt() {
        addFile("src/Resource/App/Ticket.php", TICKET);
        addFile("src/Annotation/CacheAttributes.php", ATTRIBUTES);
        addFile("src/Module/PrefixModule.php", PREFIX_MODULE);

        JsonObject envelope = envelope(lookupIn("Ticket", "onGet", "src"));

        assertEquals(
            List.of("\\MyVendor\\MyProject\\Module\\PlainInterceptor"),
            interceptors(method(envelope, "onGet")),
            envelope::toString
        );
    }

    /**
     * An interceptor array this can read only part of is reported with the count it could not
     * read: told two interceptors when three are bound, a caller reads the list as the whole of
     * what wraps the method. A class name inside another expression is not one of them.
     */
    @Test
    void countsTheInterceptorsItCouldNotRead() {
        addFile("src/Resource/App/Ticket.php", TICKET);
        addFile("src/Annotation/CacheAttributes.php", ATTRIBUTES);
        addFile("src/Module/PartialModule.php", PARTIAL_MODULE);

        JsonArray interceptors = method(envelope(lookupIn("Ticket", "onGet", "src")), "onGet")
            .getAsJsonArray("interceptors");
        List<String> named = new ArrayList<>();
        int unreadable = 0;
        for (var element : interceptors) {
            JsonObject json = element.getAsJsonObject();
            if (json.has("interceptorsUnreadable")) {
                unreadable = json.get("interceptorsUnreadable").getAsInt();
            } else {
                named.add(json.get("interceptor").getAsString());
            }
        }

        assertEquals(
            List.of("\\MyVendor\\MyProject\\Module\\PartialA", "\\MyVendor\\MyProject\\Module\\PartialB"),
            named
        );
        // $this->dynamic and new Wrapper(Nested::class): two elements, neither naming a class.
        assertEquals(2, unreadable, interceptors::toString);
    }

    /**
     * A bind call written in a shape this cannot read is a pointcut whose interceptors may wrap
     * the method. Dropping it answers "nothing wraps this" from a scan that never read it.
     */
    @Test
    void reportsABindCallItCannotRead() {
        addFile("src/Resource/App/Ticket.php", TICKET);
        addFile("src/Annotation/CacheAttributes.php", ATTRIBUTES);
        addFile("src/Module/SpreadModule.php", SPREAD_MODULE);

        JsonObject envelope = envelope(lookupIn("Ticket", "onGet", "src"));
        JsonObject undecided = envelope.getAsJsonArray("unevaluated").get(0).getAsJsonObject();

        assertEquals("binding-unreadable", undecided.get("reason").getAsString(), envelope::toString);
        assertEquals("\\MyVendor\\MyProject\\Module\\SpreadModule", undecided.get("moduleClass").getAsString());
    }

    /**
     * PHP 8 lets the arguments be written by name, in any order, and the pointcut is the same one.
     * Read by position, the method matcher would be applied to the class name and answer no.
     */
    @Test
    void readsArgumentsWrittenByName() {
        addFile("src/Resource/App/Ticket.php", TICKET);
        addFile("src/Annotation/CacheAttributes.php", ATTRIBUTES);
        addFile("src/Module/NamedArgumentModule.php", NAMED_ARGUMENT_MODULE);

        JsonObject envelope = envelope(lookupIn("Ticket", "onGet", "src"));

        assertEquals(
            List.of("\\MyVendor\\MyProject\\Module\\NamedInterceptor"),
            interceptors(method(envelope, "onGet")),
            envelope::toString
        );
        assertEquals(0, envelope.getAsJsonArray("unevaluated").size(), envelope::toString);
    }

    /**
     * A matcher this cannot read cannot be read for any method, so one pointcut is one entry.
     * Ray.Di's own AssistedInjectModule is written this way and every context installs it, so the
     * alternative is a copy of it per method examined, burying the entries about the caller's code.
     */
    @Test
    void reportsAnUndecidedPointcutOnceForAllTheMethodsItCovers() {
        addFile("src/Resource/App/Ticket.php", TICKET);
        addFile("src/Annotation/CacheAttributes.php", ATTRIBUTES);
        addFile("src/Module/HandWrittenMatcherModule.php", HAND_WRITTEN_MATCHER_MODULE);

        JsonObject envelope = envelope(lookupIn("Ticket", null, "src"));
        JsonArray unevaluated = envelope.getAsJsonArray("unevaluated");
        JsonObject undecided = unevaluated.get(0).getAsJsonObject();

        assertEquals(1, unevaluated.size(), unevaluated::toString);
        assertEquals("matcher-unreadable", undecided.get("reason").getAsString());
        assertEquals(
            envelope.getAsJsonObject("scan").get("methodsExamined").getAsInt(),
            undecided.get("methodsAffected").getAsInt(),
            undecided::toString
        );
    }

    /**
     * The walk stops at a hierarchy larger than it goes, and what it did not walk may be the class
     * that extends the one a matcher names. Answering no there drops a pointcut that really binds.
     */
    @Test
    void doesNotAnswerNoForAHierarchyItStoppedShortOf() {
        addFile("src/Type/Wide.php", wideHierarchy());
        addFile("src/Module/WideModule.php", WIDE_MODULE);

        JsonObject envelope = envelope(lookupIn("Wide", "onGet", "src"));
        JsonArray unevaluated = envelope.getAsJsonArray("unevaluated");

        assertTrue(interceptors(method(envelope, "onGet")).isEmpty(), envelope::toString);
        assertEquals(1, unevaluated.size(), unevaluated::toString);
        assertEquals(
            "hierarchy-unresolved",
            unevaluated.get(0).getAsJsonObject().get("reason").getAsString(),
            unevaluated::toString
        );
    }

    /**
     * The target's own file decides the class side of every pointcut and holds every method the
     * answer lists, so an unsaved edit to it changes the answer. Reading freshness from the module
     * files alone reports an answer read out of a buffer as one read from disk.
     */
    @Test
    void countsAnUnsavedEditToTheTargetAsUnsaved() {
        PsiFile target = fixture.addFileToProject("src/Resource/App/Ticket.php", TICKET);
        addFile("src/Annotation/CacheAttributes.php", ATTRIBUTES);
        addFile("src/Module/AnyModule.php", ANY_MODULE);
        Document document = ReadAction.compute(
            () -> FileDocumentManager.getInstance().getDocument(target.getVirtualFile())
        );
        WriteCommandAction.runWriteCommandAction(
            fixture.getProject(),
            () -> document.insertString(document.getTextLength(), "\n// edited, not saved\n")
        );

        JsonObject envelope = envelope(lookupIn("Ticket", "onGet", "src"));

        assertEquals("unsaved", envelope.getAsJsonObject("provenance").get("fresh").getAsString(), envelope::toString);
    }

    /**
     * More interfaces than the walk visits, with the one a matcher names declared last. Written
     * out here rather than by hand because the point of it is the count.
     */
    private static String wideHierarchy() {
        StringBuilder php = new StringBuilder("<?php\n\nnamespace MyVendor\\\\MyProject\\\\Type;\n\n");
        StringBuilder implemented = new StringBuilder();
        for (int i = 0; i <= 40; i++) {
            php.append("interface Iface").append(i).append("\n{\n}\n\n");
            implemented.append(i == 0 ? "" : ", ").append("Iface").append(i);
        }
        php.append("class Wide implements ").append(implemented).append("\n{\n")
            .append("    public function onGet(string $id): static\n    {\n        return $this;\n    }\n}\n");

        return php.toString();
    }

    private void addCacheable() {
        addFile("vendor/bear/query-repository/src/CacheableModule.php", CACHEABLE_MODULE);
        addFile("src/Annotation/CacheAttributes.php", ATTRIBUTES);
        addFile("src/Resource/App/Ticket.php", TICKET);
        addFile("src/Resource/App/Guest.php", GUEST);
    }

    private String lookup(String className, String method) {
        return lookupIn(className, method, "vendor");
    }

    private String lookupIn(String className, String method, String moduleRoot) {
        return AopPointcutLookupService.getInstance(fixture.getProject())
            .lookup(className, null, method, null, moduleRoot);
    }

    private static JsonObject method(JsonObject envelope, String name) {
        for (var element : envelope.getAsJsonArray("methods")) {
            JsonObject method = element.getAsJsonObject();
            if (name.equals(method.get("method").getAsString())) {
                return method;
            }
        }

        throw new AssertionError("no method " + name + " in " + envelope);
    }

    private static boolean hasMethod(JsonObject envelope, String name) {
        for (var element : envelope.getAsJsonArray("methods")) {
            if (name.equals(element.getAsJsonObject().get("method").getAsString())) {
                return true;
            }
        }

        return false;
    }

    private static List<String> interceptors(JsonObject method) {
        List<String> names = new ArrayList<>();
        for (var element : method.getAsJsonArray("interceptors")) {
            names.add(element.getAsJsonObject().get("interceptor").getAsString());
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
