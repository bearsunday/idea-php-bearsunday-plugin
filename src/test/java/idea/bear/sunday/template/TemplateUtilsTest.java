package idea.bear.sunday.template;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateUtilsTest {

    @Test
    void appRootOfPathStripsResourceTree() {
        assertEquals("/proj", TemplateUtils.appRootOfPath("/proj/src/Resource/Page/Index.php"));
    }

    @Test
    void appRootOfPathReturnsNullOutsideResourceTree() {
        assertNull(TemplateUtils.appRootOfPath("/proj/var/templates/Page/Index.html.twig"));
    }

    @Test
    void relativeFromResourcePathReturnsPathUnderResource() {
        assertEquals("Page/Index.php", TemplateUtils.relativeFromResourcePath("/proj/src/Resource/Page/Index.php"));
    }

    @Test
    void relativeFromResourcePathReturnsNullOutsideResourceTree() {
        assertNull(TemplateUtils.relativeFromResourcePath("/proj/var/templates/Page/Index.html.twig"));
    }

    @Test
    void isUnderAppRootWithRelMatchesTemplateUnderAnyDirectory() {
        // The template directory is unknown, so only the app root and the relative file must line up.
        assertTrue(TemplateUtils.isUnderAppRootWithRel(
                "/proj/var/twig/Page/Index.html.twig", "/proj", "Page/Index.html.twig"));
        assertTrue(TemplateUtils.isUnderAppRootWithRel(
                "/proj/src/Resource/Page/Index.html.twig", "/proj", "Page/Index.html.twig"));
    }

    @Test
    void isUnderAppRootWithRelRejectsDifferentAppRoot() {
        assertFalse(TemplateUtils.isUnderAppRootWithRel(
                "/other/var/templates/Page/Index.html.twig", "/proj", "Page/Index.html.twig"));
    }

    @Test
    void isUnderAppRootWithRelRejectsPartialSegmentMatch() {
        // "SubPage/Index" must not satisfy a "Page/Index" relative path.
        assertFalse(TemplateUtils.isUnderAppRootWithRel(
                "/proj/var/templates/SubPage/Index.html.twig", "/proj", "Page/Index.html.twig"));
    }

    @Test
    void fileNameOfReturnsLastSegment() {
        assertEquals("Index.html.twig", TemplateUtils.fileNameOf("Page/Index.html.twig"));
    }

    @Test
    void fileNameOfHandlesBareName() {
        assertEquals("Index.php", TemplateUtils.fileNameOf("Index.php"));
    }
}
