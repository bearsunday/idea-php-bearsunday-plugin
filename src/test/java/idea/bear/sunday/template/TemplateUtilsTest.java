package idea.bear.sunday.template;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TemplateUtilsTest {

    @Test
    void trimSlashStripsTrailingSlash() {
        assertEquals("var/templates", TemplateUtils.trimSlash("var/templates/"));
    }

    @Test
    void trimSlashStripsLeadingSlash() {
        assertEquals("var/templates", TemplateUtils.trimSlash("/var/templates"));
    }

    @Test
    void trimSlashStripsBothSlashes() {
        assertEquals("var/templates", TemplateUtils.trimSlash("/var/templates/"));
    }

    @Test
    void trimSlashLeavesNormalPathUnchanged() {
        assertEquals("var/templates", TemplateUtils.trimSlash("var/templates"));
    }

    @Test
    void trimSlashHandlesEmptyString() {
        assertEquals("", TemplateUtils.trimSlash(""));
    }

    @Test
    void trimSlashHandlesSingleSlash() {
        assertEquals("", TemplateUtils.trimSlash("/"));
    }
}
