package idea.bear.sunday.resource;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import com.jetbrains.php.lang.psi.elements.MethodReference;
import com.jetbrains.php.lang.psi.resolve.types.PhpType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the index-based halves of {@code ResourceMethodTypeProvider}'s URI resolution: the
 * strategies that run once a resource is not sitting at the path its URI names.
 *
 * <p>These need a fixture whose files actually reach PhpIndex and FilenameIndex, which means
 * registering the temp directory that {@code addFileToProject} writes into as a content root.
 * {@link ResourceMethodTypeProviderFixtureTest} deliberately does not do that: it writes resources
 * straight to the project base directory and resolves them by path, and moving its root would
 * change what {@code ProjectUtil.guessProjectDir} reports and break page-context detection.
 */
class ResourceIndexResolutionFixtureTest {

    private CodeInsightTestFixture fixture;
    private ResourceMethodTypeProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        IdeaTestFixtureFactory factory = IdeaTestFixtureFactory.getFixtureFactory();
        TestFixtureBuilder<IdeaProjectTestFixture> builder = factory.createFixtureBuilder(getClass().getSimpleName());
        builder.addModule(EmptyModuleFixtureBuilder.class);
        fixture = factory.createCodeInsightFixture(builder.getFixture(), factory.createTempDirTestFixture());
        fixture.setUp();
        VirtualFile tempRoot = fixture.getTempDirFixture().getFile("");
        assertNotNull(tempRoot);
        Module[] modules = ModuleManager.getInstance(fixture.getProject()).getModules();
        assertTrue(modules.length > 0);
        PsiTestUtil.addSourceContentToRoots(modules[0], tempRoot);

        provider = new ResourceMethodTypeProvider();
    }

    @AfterEach
    void tearDown() throws Exception {
        fixture.tearDown();
    }

    @Test
    void resolvesResourceOutsideUriPathThroughClassNameIndex() {
        // Monorepo layout: nothing sits at `<root>/src/Resource/App`, so the path strategies
        // cannot resolve `app://self/widget` and the class-name index has to.
        fixture.addFileToProject("packages/api/src/Resource/App/Widget.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class Widget extends \\BEAR\\Resource\\ResourceObject {}
            """);

        PhpType completedType = completedType(callerFor("widget"), "get");

        assertTrue(
            completedType.getTypes().contains("\\MyVendor\\MyProject\\Resource\\App\\Widget"),
            completedType::toString
        );
    }

    @Test
    void resolvesResourceOutsideUriPathThroughFilenameIndex() {
        // Same layout, but the PSR-4 root does not mirror the directory tree, so the FQN carries
        // no `\Resource\App` segment for the class-name index to match. Only the filename lookup,
        // which compares the file path rather than the namespace, is left.
        fixture.addFileToProject("packages/api/src/Resource/App/Gadget.php", """
            <?php
            namespace Acme\\Api;

            final class Gadget extends \\BEAR\\Resource\\ResourceObject {}
            """);

        PhpType completedType = completedType(callerFor("gadget"), "get");

        assertTrue(completedType.getTypes().contains("\\Acme\\Api\\Gadget"), completedType::toString);
    }

    @Test
    void ignoresVendoredResourceSharingTheUriShape() {
        // An installed BEAR application ships `src/Resource/App/*.php` under a namespace ending in
        // `\Resource\App\Gizmo`, so it satisfies both index strategies on shape alone. Resolving to
        // it would send navigation and type inference into a dependency.
        fixture.addFileToProject("vendor/acme/cms/src/Resource/App/Gizmo.php", """
            <?php
            namespace Acme\\Cms\\Resource\\App;

            final class Gizmo extends \\BEAR\\Resource\\ResourceObject {}
            """);

        assertNull(completedTypeOrNull(callerFor("gizmo"), "get"));
    }

    private PsiFile callerFor(String uriPath) {
        return fixture.addFileToProject("src/Resource/App/Caller.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class Caller
            {
                public function onGet(): void
                {
                    $resource = $this->resource->get('app://self/%s');
                }
            }
            """.formatted(uriPath));
    }

    private PhpType completedType(PsiFile psiFile, String methodName) {
        PhpType completed = completedTypeOrNull(psiFile, methodName);
        assertNotNull(completed, "basePath=" + psiFile.getProject().getBasePath());

        return completed;
    }

    private PhpType completedTypeOrNull(PsiFile psiFile, String methodName) {
        return ApplicationManager.getApplication().runReadAction((Computable<PhpType>) () -> {
            MethodReference reference = methodReference(psiFile, methodName);
            PhpType type = provider.getType(reference);
            assertNotNull(type);

            return provider.complete(type.getTypes().iterator().next(), psiFile.getProject());
        });
    }

    private static MethodReference methodReference(PsiFile psiFile, String methodName) {
        Collection<MethodReference> references = PsiTreeUtil.findChildrenOfType(psiFile, MethodReference.class);

        return references.stream()
            .filter(reference -> methodName.equals(reference.getName()))
            .findFirst()
            .orElseThrow();
    }
}
