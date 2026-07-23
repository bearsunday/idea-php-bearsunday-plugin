package idea.bear.sunday.body;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyTypeBatchGeneratorFixtureTest {

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
    void generatesBodyTypesForResourceObjectsUnderSelectedFolder() {
        addPhysicalPhpFile("src/Resource/App/Article.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class Article extends \\BEAR\\Resource\\ResourceObject
            {
                public function onGet(): static
                {
                    $this->body = ['id' => 1];

                    return $this;
                }

                public function onPost(): static
                {
                    $this->body = ['status' => 'created'];

                    return $this;
                }
            }
            """);
        addPhysicalPhpFile("src/Resource/App/Legacy.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            /**
             * @psalm-type LegacyGetBody = array{old: string}
             * @property LegacyGetBody|null $body
             */
            final class Legacy extends \\BEAR\\Resource\\ResourceObject
            {
                public function onGet(): static
                {
                    $this->body = ['name' => 'legacy'];

                    return $this;
                }
            }
            """);
        addPhysicalPhpFile("src/Resource/App/Plain.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class Plain
            {
                public function run(): void
                {
                    $this->body = ['ignored' => true];
                }
            }
            """);
        VirtualFile resourceDir = virtualFile("src/Resource/App");

        int updated = new BodyTypeBatchGenerator().generate(fixture.getProject(), new VirtualFile[]{resourceDir});

        assertEquals(2, updated);
        String article = psiText("src/Resource/App/Article.php");
        assertTrue(article.contains("@psalm-type ArticleBody = array{id: int}"), article);
        assertTrue(article.contains("@psalm-type ArticlePostBody = array{status: string}"), article);
        assertTrue(article.contains("@property ArticleBody|ArticlePostBody|null $body"), article);

        String legacy = psiText("src/Resource/App/Legacy.php");
        assertTrue(legacy.contains("@psalm-type LegacyBody = array{name: string}"), legacy);
        assertTrue(legacy.contains("@property LegacyBody|null $body"), legacy);
        assertFalse(legacy.contains("LegacyGetBody"), legacy);

        assertFalse(psiText("src/Resource/App/Plain.php").contains("@psalm-type"));
    }

    private PsiFile addPhysicalPhpFile(String relativePath, String contents) {
        try {
            String basePath = fixture.getProject().getBasePath();
            assertNotNull(basePath);
            Path path = Path.of(basePath, relativePath);
            Files.createDirectories(path.getParent());
            Files.writeString(path, contents, StandardCharsets.UTF_8);
            VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            assertNotNull(virtualFile);
            return ApplicationManager.getApplication().runReadAction((Computable<PsiFile>) () -> {
                PsiFile psiFile = PsiManager.getInstance(fixture.getProject()).findFile(virtualFile);
                assertNotNull(psiFile);

                return psiFile;
            });
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private VirtualFile virtualFile(String relativePath) {
        String basePath = fixture.getProject().getBasePath();
        assertNotNull(basePath);
        VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(basePath, relativePath));
        assertNotNull(virtualFile);

        return virtualFile;
    }

    private String psiText(String relativePath) {
        VirtualFile virtualFile = virtualFile(relativePath);
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            PsiFile psiFile = PsiManager.getInstance(fixture.getProject()).findFile(virtualFile);
            assertNotNull(psiFile);

            return psiFile.getText();
        });
    }

}
