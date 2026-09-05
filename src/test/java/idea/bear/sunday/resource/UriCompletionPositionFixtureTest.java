package idea.bear.sunday.resource;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiElement;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import com.intellij.openapi.vfs.LocalFileSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers where URI completion is offered, and where it must stay silent because a resource URI is
 * not what the string names.
 */
class UriCompletionPositionFixtureTest {

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
    void acceptsUriCall() {
        assertTrue(acceptsAtCaret("""
            <?php
            $resource->uri('<caret>');
            """));
    }

    @Test
    void acceptsRequestVerbOnResourceField() {
        assertTrue(acceptsAtCaret("""
            <?php
            $this->resource->get('<caret>');
            """));
    }

    @Test
    void acceptsRequestVerbOnResourceVariable() {
        assertTrue(acceptsAtCaret("""
            <?php
            $resource->post('<caret>');
            """));
    }

    /** The receiver a project names something other than {@code resource} answers by its type. */
    @Test
    void acceptsRequestVerbOnTypedResourceParameter() {
        fixture.addFileToProject("stub.php", """
            <?php
            namespace BEAR\\Resource;

            interface ResourceInterface
            {
                public function get(string $uri);
            }
            """);

        assertTrue(acceptsAtCaret("""
            <?php
            use BEAR\\Resource\\ResourceInterface;

            function request(ResourceInterface $api): void
            {
                $api->get('<caret>');
            }
            """));
    }

    @Test
    void rejectsRequestVerbOnUnrelatedReceiver() {
        assertFalse(acceptsAtCaret("""
            <?php
            $container->get('<caret>');
            """));
    }

    @Test
    void rejectsQueryArgumentOfRequestCall() {
        assertFalse(acceptsAtCaret("""
            <?php
            $resource->get('app://self/user', ['<caret>']);
            """));
    }

    @Test
    void acceptsEmbedSource() {
        assertTrue(acceptsAtCaret("""
            <?php
            class Dashboard
            {
                #[Embed('<caret>', 'user')]
                public function onGet(): void
                {
                }
            }
            """));
    }

    @Test
    void acceptsEmbedNamedSource() {
        assertTrue(acceptsAtCaret("""
            <?php
            class Dashboard
            {
                #[Embed(src: '<caret>', rel: 'user')]
                public function onGet(): void
                {
                }
            }
            """));
    }

    @Test
    void acceptsLinkHref() {
        assertTrue(acceptsAtCaret("""
            <?php
            class Dashboard
            {
                #[Link('next', '<caret>')]
                public function onGet(): void
                {
                }
            }
            """));
    }

    @Test
    void rejectsLinkRel() {
        assertFalse(acceptsAtCaret("""
            <?php
            class Dashboard
            {
                #[Link('<caret>', 'app://self/next')]
                public function onGet(): void
                {
                }
            }
            """));
    }

    /** A fragment of a concatenation is not the URI the relation names. */
    @Test
    void rejectsConcatenatedAttributeArgument() {
        assertFalse(acceptsAtCaret("""
            <?php
            class Dashboard
            {
                #[Link('next', 'app://self/' . '<caret>')]
                public function onGet(): void
                {
                }
            }
            """));
    }

    @Test
    void rejectsAttributeArgumentNestedInArray() {
        assertFalse(acceptsAtCaret("""
            <?php
            class Dashboard
            {
                #[Embed(src: ['<caret>'])]
                public function onGet(): void
                {
                }
            }
            """));
    }

    @Test
    void rejectsUnrelatedAttributeArgument() {
        assertFalse(acceptsAtCaret("""
            <?php
            class PointQuery
            {
                #[DbQuery('<caret>')]
                public function distance(): array
                {
                }
            }
            """));
    }

    @Test
    void acceptsToInstance() {
        assertTrue(acceptsAtCaret("""
            <?php
            $binder->toInstance('<caret>');
            """));
    }

    @Test
    void rejectsSecondArgumentOfUriCall() {
        assertFalse(acceptsAtCaret("""
            <?php
            $resource->uri('app://self/user', '<caret>');
            """));
    }

    /**
     * The cases above drive the narrowing directly; these two drive the registered contributor,
     * so they answer the other half: that the pattern reaches the provider at all in a position
     * only goto used to serve.
     *
     * <p>Two resources, not one: with a single lookup item {@code completeBasic()} inserts it and
     * leaves nothing to assert on.
     */
    @Test
    void offersResourceUriInsideRequestCall() {
        addResources();
        fixture.configureByText("Caller.php", """
            <?php
            $this->resource->get('<caret>');
            """);

        fixture.completeBasic();

        assertNotNull(fixture.getLookupElementStrings());
        assertTrue(
            fixture.getLookupElementStrings().contains("app://self/user"),
            () -> String.valueOf(fixture.getLookupElementStrings())
        );
    }

    @Test
    void offersResourceUriInsideEmbedRelation() {
        addResources();
        fixture.configureByText("Caller.php", """
            <?php
            class Dashboard
            {
                #[Embed(src: '<caret>', rel: 'user')]
                public function onGet(): void
                {
                }
            }
            """);

        fixture.completeBasic();

        assertNotNull(fixture.getLookupElementStrings());
        assertTrue(
            fixture.getLookupElementStrings().contains("app://self/user"),
            () -> String.valueOf(fixture.getLookupElementStrings())
        );
    }

    /**
     * Written through the filesystem, not {@code addFileToProject}: the completion provider walks
     * {@code src/Resource} on disk, so a file that exists only in the in-memory VFS is not found.
     */
    private void addResources() {
        addResource("User");
        addResource("Profile");
    }

    private void addResource(String className) {
        try {
            String basePath = fixture.getProject().getBasePath();
            assertNotNull(basePath);
            Path path = Path.of(basePath, "src/Resource/App/" + className + ".php");
            Files.createDirectories(path.getParent());
            Files.writeString(path, """
                <?php
                namespace MyVendor\\MyProject\\Resource\\App;

                class %s
                {
                    public function onGet(): void
                    {
                    }
                }
                """.formatted(className), StandardCharsets.UTF_8);
            assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean acceptsAtCaret(String source) {
        fixture.configureByText("Caller.php", source);

        return ApplicationManager.getApplication().runReadAction((Computable<Boolean>) () -> {
            PsiElement element = fixture.getFile().findElementAt(fixture.getCaretOffset());
            assertNotNull(element);

            return UriElementPatternHelper.accepts(element);
        });
    }
}
