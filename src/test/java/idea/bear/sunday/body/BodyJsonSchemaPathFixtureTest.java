package idea.bear.sunday.body;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BodyJsonSchemaPathFixtureTest {

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
    void usesResourcePathWithoutMethodName() {
        PsiFile psiFile = fixture.addFileToProject("src/Resource/App/BodyTypeDemo.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class BodyTypeDemo extends \\BEAR\\Resource\\ResourceObject {}
            """);

        String relativePath = schemaRelativePath(psiFile);

        assertEquals("var/json_schema/body-type-demo.json", relativePath);
    }

    @Test
    void usesNestedResourcePathWithoutScheme() {
        PsiFile psiFile = fixture.addFileToProject("src/Resource/Page/Admin/UserProfile.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\Page\\Admin;

            final class UserProfile extends \\BEAR\\Resource\\ResourceObject {}
            """);

        String relativePath = schemaRelativePath(psiFile);

        assertEquals("var/json_schema/admin/user-profile.json", relativePath);
    }

    private String schemaRelativePath(PsiFile psiFile) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            PhpClass phpClass = PsiTreeUtil.findChildOfType(psiFile, PhpClass.class);
            assertNotNull(phpClass);
            Path path = BodyJsonSchemaPath.fromClass(psiFile.getProject(), phpClass);
            assertNotNull(path);

            return BodyJsonSchemaPath.relativeDisplayPath(psiFile.getProject(), path).replace('\\', '/');
        });
    }

}
