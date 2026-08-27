package idea.bear.sunday.resource;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResourceClassResolverFixtureTest {

    private CodeInsightTestFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        IdeaTestFixtureFactory factory = IdeaTestFixtureFactory.getFixtureFactory();
        TestFixtureBuilder<IdeaProjectTestFixture> builder = factory.createFixtureBuilder(getClass().getSimpleName());
        fixture = factory.createCodeInsightFixture(builder.getFixture(), factory.createTempDirTestFixture());
        fixture.setUp();
    }

    @AfterEach
    void tearDown() throws Exception {
        fixture.tearDown();
    }

    @Test
    void resolvesLiteralUriToResourceClass() {
        addPhysicalPhpFile("src/Resource/App/Index.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class Index extends \\BEAR\\Resource\\ResourceObject {}
            """);

        assertEquals("\\MyVendor\\MyProject\\Resource\\App\\Index", resolvedFqn("app://self/index"));
    }

    @Test
    void resolvesHyphenatedResourceUriByFilePath() {
        addPhysicalPhpFile("src/Resource/App/BlogPosting.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class BlogPosting extends \\BEAR\\Resource\\ResourceObject {}
            """);

        assertEquals("\\MyVendor\\MyProject\\Resource\\App\\BlogPosting", resolvedFqn("app://self/blog-posting"));
    }

    @Test
    void resolvesPageScopedUri() {
        addPhysicalPhpFile("src/Resource/Page/Profile.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\Page;

            final class Profile extends \\BEAR\\Resource\\ResourceObject {}
            """);

        assertEquals("\\MyVendor\\MyProject\\Resource\\Page\\Profile", resolvedFqn("page://self/profile"));
    }

    @Test
    void returnsEmptyWhenResourceFileDoesNotExist() {
        assertNull(resolvedFqn("app://self/does-not-exist"));
    }

    private @Nullable String resolvedFqn(String normalizedUri) {
        return ApplicationManager.getApplication().runReadAction((Computable<@Nullable String>) () ->
            ResourceClassResolver.resolveCached(fixture.getProject(), normalizedUri)
                .map(PhpClass::getFQN)
                .orElse(null));
    }

    private void addPhysicalPhpFile(String relativePath, String contents) {
        try {
            String basePath = fixture.getProject().getBasePath();
            assertNotNull(basePath);
            Path path = Path.of(basePath, relativePath);
            Files.createDirectories(path.getParent());
            Files.writeString(path, contents, StandardCharsets.UTF_8);
            VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            assertNotNull(virtualFile);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
