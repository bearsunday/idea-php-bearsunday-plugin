package idea.bear.sunday.relation;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import com.intellij.psi.PsiFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResourceRelationIndexUtilFixtureTest {

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
    void indexesNamedLinkAndEmbedAttributes() {
        PsiFile psiFile = fixture.addFileToProject("src/Resource/App/Dashboard.php", """
            <?php
            namespace MyVendor\\Todo\\Resource\\App;

            use BEAR\\Resource\\Annotation\\Embed;
            use BEAR\\Resource\\Annotation\\Link;

            class Dashboard
            {
                #[Embed(src: '/user{?id}', rel: 'user')]
                #[Link(method: 'post', href: 'page://self/user/edit{?id}', rel: 'edit')]
                public function onGet(): static
                {
                    return $this;
                }
            }
            """);

        Map<String, List<ResourceRelation>> index = index(psiFile);

        ResourceRelation embed = onlyRelation(index, "src/Resource/App/User.php");
        assertEquals("Embed", embed.kind());
        assertEquals("user", embed.rel());
        assertEquals("app://self/dashboard", embed.sourceUri());
        assertEquals("\\MyVendor\\Todo\\Resource\\App\\Dashboard", embed.sourceFqn());
        assertEquals("app://self/user", embed.targetUri());
        assertEquals("onGet", embed.targetMethod());
        assertEquals("/user{?id}", embed.rawTargetUri());
        assertEquals("src/Resource/App/Dashboard.php", embed.sourceFilePath());
        assertTrue(embed.attributeTextOffset() > 0);

        ResourceRelation link = onlyRelation(index, "src/Resource/Page/User/Edit.php");
        assertEquals("Link", link.kind());
        assertEquals("edit", link.rel());
        assertEquals("page://self/user/edit", link.targetUri());
        assertEquals("onPost", link.targetMethod());
        assertEquals("page://self/user/edit{?id}", link.rawTargetUri());
    }

    @Test
    void indexesPositionalLinkAndEmbedAttributes() {
        PsiFile psiFile = fixture.addFileToProject("src/Resource/App/Dashboard.php", """
            <?php
            namespace MyVendor\\Todo\\Resource\\App;

            use BEAR\\Resource\\Annotation\\Embed;
            use BEAR\\Resource\\Annotation\\Link;

            class Dashboard
            {
                #[Embed('app://self/profile{?id}', 'profile')]
                #[Link('next', 'app://self/next{?id}', 'put')]
                public function onGet(): static
                {
                    return $this;
                }
            }
            """);

        Map<String, List<ResourceRelation>> index = index(psiFile);

        ResourceRelation embed = onlyRelation(index, "src/Resource/App/Profile.php");
        assertEquals("Embed", embed.kind());
        assertEquals("profile", embed.rel());
        assertEquals("app://self/profile", embed.targetUri());
        assertEquals("onGet", embed.targetMethod());

        ResourceRelation link = onlyRelation(index, "src/Resource/App/Next.php");
        assertEquals("Link", link.kind());
        assertEquals("next", link.rel());
        assertEquals("app://self/next", link.targetUri());
        assertEquals("onPut", link.targetMethod());
    }

    @Test
    void ignoresDynamicAndUnsupportedTargets() {
        PsiFile psiFile = fixture.addFileToProject("src/Resource/App/Dashboard.php", """
            <?php
            namespace MyVendor\\Todo\\Resource\\App;

            use BEAR\\Resource\\Annotation\\Embed;
            use BEAR\\Resource\\Annotation\\Link;

            class Dashboard
            {
                #[Embed(src: $uri, rel: 'dynamic')]
                #[Embed(src: 'query://self/user', rel: 'query')]
                #[Link(rel: 'external', href: 'https://example.com/user')]
                public function onGet(): static
                {
                    return $this;
                }
            }
            """);

        assertTrue(index(psiFile).isEmpty());
    }

    private static Map<String, List<ResourceRelation>> index(PsiFile psiFile) {
        VirtualFile baseDir = tempProjectRoot(psiFile);
        return ApplicationManager.getApplication().runReadAction(
            (Computable<Map<String, List<ResourceRelation>>>) () -> ResourceRelationIndexUtil.index(psiFile, baseDir)
        );
    }

    private static VirtualFile tempProjectRoot(PsiFile psiFile) {
        VirtualFile file = psiFile.getVirtualFile();
        assertNotNull(file);
        VirtualFile root = file;
        for (int i = 0; i < 4; i++) {
            root = root.getParent();
            assertNotNull(root);
        }

        return root;
    }

    private static ResourceRelation onlyRelation(Map<String, List<ResourceRelation>> index, String targetResourcePath) {
        List<ResourceRelation> relations = index.get(targetResourcePath);
        assertNotNull(relations, targetResourcePath + " in " + index);
        assertEquals(1, relations.size(), targetResourcePath);
        return relations.get(0);
    }
}
