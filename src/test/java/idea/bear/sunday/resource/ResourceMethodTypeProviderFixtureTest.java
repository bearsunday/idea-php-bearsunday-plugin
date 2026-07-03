package idea.bear.sunday.resource;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import com.jetbrains.php.lang.psi.elements.FieldReference;
import com.jetbrains.php.lang.psi.elements.MethodReference;
import com.jetbrains.php.lang.psi.resolve.types.PhpType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceMethodTypeProviderFixtureTest {

    private CodeInsightTestFixture fixture;
    private ResourceMethodTypeProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        IdeaTestFixtureFactory factory = IdeaTestFixtureFactory.getFixtureFactory();
        TestFixtureBuilder<IdeaProjectTestFixture> builder = factory.createFixtureBuilder(getClass().getSimpleName());
        fixture = factory.createCodeInsightFixture(builder.getFixture(), factory.createTempDirTestFixture());
        fixture.setUp();
        provider = new ResourceMethodTypeProvider();
    }

    @AfterEach
    void tearDown() throws Exception {
        fixture.tearDown();
    }

    @Test
    void resolvesLiteralGetUriToResourceClass() {
        addPhysicalPhpFile("src/Resource/App/Index.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class Index extends \\BEAR\\Resource\\ResourceObject {}
            """);
        PsiFile caller = fixture.addFileToProject("src/Resource/App/Caller.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class Caller
            {
                public function onGet(): void
                {
                    $index = $this->resource->get('app://self/index');
                }
            }
            """);

        PhpType completedType = completedType(caller, "get");

        assertTrue(completedType.getTypes().contains("\\MyVendor\\MyProject\\Resource\\App\\Index"));
    }

    @Test
    void resolvesLiteralPutUriToResourceClass() {
        addPhysicalPhpFile("src/Resource/App/Index.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class Index extends \\BEAR\\Resource\\ResourceObject {}
            """);
        PsiFile caller = fixture.addFileToProject("src/Resource/App/Caller.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class Caller
            {
                public function onPut(): void
                {
                    $index = $this->resource->put('app://self/index');
                }
            }
            """);

        PhpType completedType = completedType(caller, "put");

        assertTrue(completedType.getTypes().contains("\\MyVendor\\MyProject\\Resource\\App\\Index"));
    }

    @Test
    void resolvesHyphenatedResourceUriByFilePath() {
        addPhysicalPhpFile("src/Resource/App/BlogPosting.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class BlogPosting extends \\BEAR\\Resource\\ResourceObject {}
            """);
        PsiFile caller = fixture.addFileToProject("src/Resource/App/Caller.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class Caller
            {
                public function onGet(): void
                {
                    $posting = $this->resource->get('app://self/blog-posting');
                }
            }
            """);

        PhpType completedType = completedType(caller, "get");

        assertTrue(completedType.getTypes().contains("\\MyVendor\\MyProject\\Resource\\App\\BlogPosting"));
    }

    @Test
    void resolvesRelativeUriInPageContext() {
        addPhysicalPhpFile("src/Resource/Page/Profile.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\Page;

            final class Profile extends \\BEAR\\Resource\\ResourceObject {}
            """);
        PsiFile caller = addPhysicalPhpFile("src/Resource/Page/Caller.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\Page;

            final class Caller
            {
                public function onGet(): void
                {
                    $profile = $this->resource->get('/profile');
                }
            }
            """);

        PhpType completedType = completedType(caller, "get");

        assertTrue(completedType.getTypes().contains("\\MyVendor\\MyProject\\Resource\\Page\\Profile"));
    }

    @Test
    void ignoresDynamicUri() {
        PsiFile caller = fixture.addFileToProject("src/Resource/App/Caller.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class Caller
            {
                public function onGet(string $uri): void
                {
                    $resource = $this->resource->get($uri);
                }
            }
            """);

        ApplicationManager.getApplication().runReadAction(() -> {
            MethodReference reference = methodReference(caller, "get");
            assertNull(provider.getType(reference));
        });
    }

    @Test
    void ignoresConcatenatedUri() {
        PsiFile caller = fixture.addFileToProject("src/Resource/App/Caller.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class Caller
            {
                public function onGet(string $prefix): void
                {
                    $resource = $this->resource->get($prefix . '/user');
                }
            }
            """);

        ApplicationManager.getApplication().runReadAction(() -> {
            MethodReference reference = methodReference(caller, "get");
            assertNull(provider.getType(reference));
        });
    }

    @Test
    void ignoresResourceBodyAfterReassignment() {
        PsiFile caller = fixture.addFileToProject("src/Resource/App/Caller.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class Caller
            {
                public function onGet(): void
                {
                    $article = $this->resource->get('app://self/article');
                    $article = null;
                    $body = $article->body;
                }
            }
            """);

        ApplicationManager.getApplication().runReadAction(() -> {
            FieldReference reference = bodyFieldReference(caller);
            assertNull(provider.getType(reference));
        });
    }

    @Test
    void narrowsGetBodyTypeFromLocalResourceVariable() {
        addPhysicalPhpFile("src/Resource/App/Article.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class Article extends \\BEAR\\Resource\\ResourceObject
            {
                public function onGet(): static
                {
                    $this->body = ['id' => 1, 'title' => 'Hello'];

                    return $this;
                }

                public function onPost(): static
                {
                    $this->body = ['status' => 'created'];

                    return $this;
                }
            }
            """);
        PsiFile caller = fixture.addFileToProject("src/Resource/App/Caller.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class Caller
            {
                public function onGet(): void
                {
                    $article = $this->resource->get('app://self/article');
                    $body = $article->body;
                }
            }
            """);

        PhpType completedType = completedBodyType(caller);

        assertTrue(completedType.getTypes().contains("array{id: int, title: string}"), completedType::toString);
        assertTrue(completedType.isNullable(), completedType::toString);
    }

    @Test
    void narrowsPutBodyTypeFromLocalResourceVariable() {
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

                public function onPut(): static
                {
                    $this->body = ['updated' => true];

                    return $this;
                }
            }
            """);
        PsiFile caller = fixture.addFileToProject("src/Resource/App/Caller.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class Caller
            {
                public function onPut(): void
                {
                    $article = $this->resource->put('app://self/article');
                    $body = $article->body;
                }
            }
            """);

        PhpType completedType = completedBodyType(caller);

        assertTrue(completedType.getTypes().contains("array{updated: bool}"), completedType::toString);
        assertTrue(completedType.isNullable(), completedType::toString);
    }

    @Test
    void narrowsDirectResourceBodyAccess() {
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
            }
            """);
        PsiFile caller = fixture.addFileToProject("src/Resource/App/Caller.php", """
            <?php
            namespace MyVendor\\MyProject\\Resource\\App;

            final class Caller
            {
                public function onGet(): void
                {
                    $body = $this->resource->get('app://self/article')->body;
                }
            }
            """);

        PhpType completedType = completedBodyType(caller);

        assertTrue(completedType.getTypes().contains("array{id: int}"), completedType::toString);
    }

    private PhpType completedType(PsiFile psiFile, String methodName) {
        return ApplicationManager.getApplication().runReadAction((Computable<PhpType>) () -> {
            MethodReference reference = methodReference(psiFile, methodName);
            PhpType type = provider.getType(reference);
            assertNotNull(type);
            String signature = type.getTypes().iterator().next();
            PhpType completed = provider.complete(signature, psiFile.getProject());
            assertNotNull(completed, "signature=" + signature + ", basePath=" + psiFile.getProject().getBasePath());

            return completed;
        });
    }

    private PhpType completedBodyType(PsiFile psiFile) {
        return ApplicationManager.getApplication().runReadAction((Computable<PhpType>) () -> {
            FieldReference reference = bodyFieldReference(psiFile);
            PhpType type = provider.getType(reference);
            assertNotNull(type);
            String signature = type.getTypes().iterator().next();
            PhpType completed = provider.complete(signature, psiFile.getProject());
            assertNotNull(completed, "signature=" + signature + ", basePath=" + psiFile.getProject().getBasePath());

            return completed;
        });
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

    private static MethodReference methodReference(PsiFile psiFile, String methodName) {
        Collection<MethodReference> references = PsiTreeUtil.findChildrenOfType(psiFile, MethodReference.class);
        return references.stream()
            .filter(reference -> methodName.equals(reference.getName()))
            .findFirst()
            .orElseThrow();
    }

    private static FieldReference bodyFieldReference(PsiFile psiFile) {
        Collection<FieldReference> references = PsiTreeUtil.findChildrenOfType(psiFile, FieldReference.class);
        return references.stream()
            .filter(reference -> "body".equals(reference.getName()))
            .findFirst()
            .orElseThrow();
    }

}
