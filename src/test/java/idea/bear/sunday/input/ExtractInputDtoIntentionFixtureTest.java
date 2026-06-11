package idea.bear.sunday.input;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractInputDtoIntentionFixtureTest {

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
    void isAvailableOnResourceMethodWithParameters() {
        PsiFile psiFile = addPhysicalPhpFile("src/Resource/App/Point.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class Point extends \\BEAR\\Resource\\ResourceObject
            {
                public function onGet(int $x = 3, int $y = 4): static
                {
                    $this->body = ['x' => $x, 'y' => $y];

                    return $this;
                }
            }
        """);
        fixture.configureFromExistingVirtualFile(psiFile.getVirtualFile());
        int offset = fixture.getEditor().getDocument().getText().indexOf("onGet");
        boolean[] available = {false};
        ApplicationManager.getApplication().invokeAndWait(() -> {
            fixture.getEditor().getCaretModel().moveToOffset(offset);
            PsiElement element = fixture.getFile().findElementAt(offset);
            assertNotNull(element);
            available[0] = new ExtractInputDtoIntention().isAvailable(fixture.getProject(), fixture.getEditor(), element);
        });

        assertTrue(available[0]);
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

}
