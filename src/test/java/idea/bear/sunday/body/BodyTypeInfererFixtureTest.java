package idea.bear.sunday.body;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import com.jetbrains.php.lang.psi.elements.AssignmentExpression;
import com.jetbrains.php.lang.psi.elements.PhpExpression;
import com.jetbrains.php.lang.psi.elements.PhpPsiElement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BodyTypeInfererFixtureTest {

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
    void infersNestedListArrayShapeFromPhpPsi() {
        PsiFile psiFile = fixture.addFileToProject("BodyTypeDemo.php", """
            <?php
            final class BodyTypeDemo
            {
                public function onGet(string $name): static
                {
                    $this->body = [
                        'id' => 1,
                        'name' => $name,
                        'posts' => [
                            [
                                'id' => 1,
                                'title' => 'Hello',
                            ],
                            [
                                'id' => 2,
                                'title' => 'Body Type',
                            ],
                        ],
                    ];

                    return $this;
                }
            }
            """);

        BodyType bodyType = inferAssignedBody(psiFile);

        assertEquals(
            "array{id: int, name: string, posts: list<array{id: int, title: string}>}",
            bodyType.render()
        );
    }

    @Test
    void infersImplicitElementsInMixedArrayShapeFromPhpPsi() {
        PsiFile psiFile = fixture.addFileToProject("MixedBody.php", """
            <?php
            final class MixedBody
            {
                public function onPost(): static
                {
                    $this->body = [
                        'status' => 'created',
                        'id' => 2,
                        'apple',
                    ];

                    return $this;
                }
            }
            """);

        BodyType bodyType = inferAssignedBody(psiFile);

        assertEquals(
            "array{status: string, id: int, 0: string}",
            bodyType.render()
        );
    }

    @Test
    void fallsBackToGenericArrayForDynamicKeys() {
        PsiFile psiFile = fixture.addFileToProject("DynamicKeyBody.php", """
            <?php
            final class DynamicKeyBody
            {
                public function onGet(string $key): static
                {
                    $this->body = [
                        $key => 1,
                        'label' => 'x',
                    ];

                    return $this;
                }
            }
            """);

        BodyType bodyType = inferAssignedBody(psiFile);

        assertEquals("array<array-key, int|string>", bodyType.render());
    }

    private static BodyType inferAssignedBody(PsiFile psiFile) {
        return ApplicationManager.getApplication().runReadAction((Computable<BodyType>) () -> {
            AssignmentExpression assignment = PsiTreeUtil.findChildOfType(psiFile, AssignmentExpression.class);
            assertNotNull(assignment);
            PhpPsiElement value = assignment.getValue();
            assertInstanceOf(PhpExpression.class, value);

            return new BodyTypeInferer().infer((PhpExpression) value);
        });
    }
}
