package idea.bear.sunday.template;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.jetbrains.php.lang.psi.elements.PhpClass;

public class GoToTemplateOrResourceActionTest extends BasePlatformTestCase {

    public void testTwigResourceResolvesToTemplate() {
        VirtualFile resource = addFile("src/Resource/Page/Index.php",
            "<?php\nnamespace MyVendor\\App\\Resource\\Page;\nclass Index {}\n");
        addFile("var/templates/Page/Index.html.twig", "{{ greeting }}\n");

        GoToTemplateOrResourceAction.Resolution resolution =
            GoToTemplateOrResourceAction.resolve(resource, getProject());

        assertNotNull(resolution);
        assertEquals("Open Template", resolution.actionText());
        assertEquals("Index.html.twig", asPsiFile(resolution.target()).getName());
    }

    public void testTwigTemplateResolvesToResource() {
        addFile("src/Resource/Page/Index.php",
            "<?php\nnamespace MyVendor\\App\\Resource\\Page;\nclass Index {}\n");
        VirtualFile template = addFile("var/templates/Page/Index.html.twig", "{{ greeting }}\n");

        GoToTemplateOrResourceAction.Resolution resolution =
            GoToTemplateOrResourceAction.resolve(template, getProject());

        assertNotNull(resolution);
        assertEquals("Open Resource", resolution.actionText());
        assertEquals("Index", asPhpClass(resolution.target()).getName());
    }

    public void testQiqResourceResolvesToTemplate() {
        VirtualFile resource = addFile("src/Resource/Page/User.php",
            "<?php\nnamespace MyVendor\\App\\Resource\\Page;\nclass User {}\n");
        addFile("templates/Page/User.php", "{{= $this->name }}\n");

        GoToTemplateOrResourceAction.Resolution resolution =
            GoToTemplateOrResourceAction.resolve(resource, getProject());

        assertNotNull(resolution);
        assertEquals("Open Template", resolution.actionText());
        assertEquals("User.php", asPsiFile(resolution.target()).getName());
    }

    public void testQiqTemplateResolvesToResource() {
        addFile("src/Resource/Page/User.php",
            "<?php\nnamespace MyVendor\\App\\Resource\\Page;\nclass User {}\n");
        VirtualFile template = addFile("templates/Page/User.php", "{{= $this->name }}\n");

        GoToTemplateOrResourceAction.Resolution resolution =
            GoToTemplateOrResourceAction.resolve(template, getProject());

        assertNotNull(resolution);
        assertEquals("Open Resource", resolution.actionText());
        assertEquals("User", asPhpClass(resolution.target()).getName());
    }

    public void testResourceWithoutTemplateResolvesToNull() {
        VirtualFile resource = addFile("src/Resource/Page/Orphan.php",
            "<?php\nnamespace MyVendor\\App\\Resource\\Page;\nclass Orphan {}\n");

        assertNull(GoToTemplateOrResourceAction.resolve(resource, getProject()));
    }

    public void testUnrelatedPhpResolvesToNull() {
        VirtualFile unrelated = addFile("var/lib/Helper.php",
            "<?php\nnamespace MyVendor\\App\\Lib;\nclass Helper {}\n");

        assertNull(GoToTemplateOrResourceAction.resolve(unrelated, getProject()));
    }

    private VirtualFile addFile(String path, String content) {
        return myFixture.addFileToProject(path, content).getVirtualFile();
    }

    private PsiFile asPsiFile(PsiElement element) {
        return assertInstanceOf(element, PsiFile.class);
    }

    private PhpClass asPhpClass(PsiElement element) {
        return assertInstanceOf(element, PhpClass.class);
    }
}
