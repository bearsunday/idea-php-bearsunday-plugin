package idea.bear.sunday.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleTreeDiagramPageTest {

    @Test
    void bundlesMermaidRatherThanFetchingIt() {
        String html = ModuleTreeDiagramPage.html("flowchart LR\n  a-->b\n", false);

        assertTrue(html.contains("mermaid.initialize"), "the page must drive Mermaid itself");
        // Bundled: the page carries the library, so an IDE with no network still draws.
        assertTrue(html.length() > 500_000, "the Mermaid bundle is missing from the page");
        // Nothing is loaded from anywhere: no src/href at all, so there is no host to reach.
        assertFalse(html.contains("<script src"), "the page must not fetch a script");
        assertFalse(html.contains("<link "), "the page must not fetch a stylesheet");
    }

    /**
     * The diagram reaches the page as a JS string literal, and a module tree can carry source text
     * from an install this could not read. A closing script tag in it would end the script.
     */
    @Test
    void cannotLetTheDiagramEndTheScript() {
        String html = ModuleTreeDiagramPage.html("A[\"</script><script>stolen()</script>\"]", false);
        String source = html.lines().filter(line -> line.contains("const source =")).findFirst().orElseThrow();

        // Only a closing tag can end a script block, so that is the one form that must not survive
        // raw inside the literal the diagram is passed in as.
        assertFalse(source.contains("</script>"), source);
        assertTrue(source.contains("<\\/script>"), source);
    }

    @Test
    void followsTheIdeTheme() {
        assertTrue(ModuleTreeDiagramPage.html("flowchart LR", true).contains("theme: 'dark'"));
        assertTrue(ModuleTreeDiagramPage.html("flowchart LR", false).contains("theme: 'default'"));
    }
}
