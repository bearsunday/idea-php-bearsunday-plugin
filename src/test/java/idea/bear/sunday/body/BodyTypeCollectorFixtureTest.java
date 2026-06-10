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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BodyTypeCollectorFixtureTest {

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
    void collectsMethodSpecificBodyTypes() {
        PsiFile psiFile = fixture.addFileToProject("Article.php", """
            <?php
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

        BodyTypeCollection collection = collect(psiFile);

        assertEquals(List.of("ArticleGetBody", "ArticlePostBody"), collection.typeNames());
        assertEquals("array{id: int}", collection.declarations().get(0).bodyType().render());
        assertEquals("array{status: string}", collection.declarations().get(1).bodyType().render());
    }

    @Test
    void collectsLiteralBodyOffsetAssignments() {
        PsiFile psiFile = fixture.addFileToProject("ArticleOffset.php", """
            <?php
            final class ArticleOffset extends \\BEAR\\Resource\\ResourceObject
            {
                public function onGet(): static
                {
                    $meta = [1, 2];
                    $this->body = ['a' => 1];
                    $this->body['b'] = $meta;
                    $this->body['c'] = $unknown;

                    return $this;
                }
            }
            """);

        BodyTypeCollection collection = collect(psiFile);

        assertEquals(List.of("ArticleOffsetGetBody"), collection.typeNames());
        assertEquals("array{a: int, b: list<int>, c: mixed}", collection.declarations().get(0).bodyType().render());
    }

    @Test
    void skipsDynamicBodyOffsetAssignments() {
        PsiFile psiFile = fixture.addFileToProject("DynamicOffset.php", """
            <?php
            final class DynamicOffset extends \\BEAR\\Resource\\ResourceObject
            {
                public function onGet(string $key): static
                {
                    $this->body = ['a' => 1];
                    $this->body[$key] = 2;

                    return $this;
                }
            }
            """);

        BodyTypeCollection collection = collect(psiFile);

        assertEquals("array{a: int}", collection.declarations().get(0).bodyType().render());
    }

    @Test
    void collectsBodyOffsetAssignmentsWithoutFullBodyAssignment() {
        PsiFile psiFile = fixture.addFileToProject("OnlyOffset.php", """
            <?php
            final class OnlyOffset extends \\BEAR\\Resource\\ResourceObject
            {
                public function onGet(): static
                {
                    $this->body['foo'] = 1;

                    return $this;
                }
            }
            """);

        BodyTypeCollection collection = collect(psiFile);

        assertEquals("array{foo: int}", collection.declarations().get(0).bodyType().render());
    }

    private static BodyTypeCollection collect(PsiFile psiFile) {
        return ApplicationManager.getApplication().runReadAction((Computable<BodyTypeCollection>) () -> {
            PhpClass phpClass = PsiTreeUtil.findChildOfType(psiFile, PhpClass.class);
            assertNotNull(phpClass);

            return new BodyTypeCollector().collect(phpClass).orElseThrow();
        });
    }

}
