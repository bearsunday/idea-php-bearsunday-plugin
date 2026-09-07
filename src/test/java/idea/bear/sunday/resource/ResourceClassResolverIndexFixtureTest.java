package idea.bear.sunday.resource;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import com.intellij.testFramework.fixtures.impl.LightTempDirTestFixtureImpl;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises the index-backed fallback stages of {@link ResourceClassResolver}, which the
 * physical-file sibling test cannot reach: its temp-dir fixture keeps physical files out of the
 * project index, so only the light fixture recipe (see ResourceAttributeIndexServiceFixtureTest)
 * makes {@code FilenameIndex}/{@code PhpIndex} answer.
 */
class ResourceClassResolverIndexFixtureTest {

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

    @Test
    void resolvesTheConventionalPathThroughTheProjectFiles() {
        fixture.addFileToProject("src/Resource/App/User.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class User extends \\BEAR\\Resource\\ResourceObject {}
            """);

        assertEquals("\\MyVendor\\MyProject\\Resource\\App\\User", resolvedFqn("app://self/user"));
    }

    private @Nullable String resolvedFqn(String normalizedUri) {
        return ApplicationManager.getApplication().runReadAction((Computable<@Nullable String>) () ->
            ResourceClassResolver.resolveCached(fixture.getProject(), normalizedUri)
                .map(PhpClass::getFQN)
                .orElse(null));
    }
}
