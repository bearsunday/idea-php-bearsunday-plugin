package idea.bear.sunday.graph;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleTreeDiagramPageTest {

    private static final String OPEN = "window.cefQuery({request: fqn});";

    @Test
    void bundlesMermaidRatherThanFetchingIt() {
        String html = ModuleTreeDiagramPage.html("flowchart LR\n  a-->b\n", null, "", false);

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
        String html = ModuleTreeDiagramPage.html("A[\"</script><script>stolen()</script>\"]", null, "", false);
        String source = html.lines().filter(line -> line.contains("const source =")).findFirst().orElseThrow();

        // Only a closing tag can end a script block, so that is the one form that must not survive
        // raw inside the literal the diagram is passed in as.
        assertFalse(source.contains("</script>"), source);
        assertTrue(source.contains("<\\/script>"), source);
    }

    @Test
    void followsTheIdeTheme() {
        assertTrue(ModuleTreeDiagramPage.html("flowchart LR", null, "", true).contains("theme: 'dark'"));
        assertTrue(ModuleTreeDiagramPage.html("flowchart LR", null, "", false).contains("theme: 'default'"));
    }

    /**
     * Without a bridge to the IDE there is nothing a click could do, so no box is marked: a
     * pointer that turns into a hand over a box that does nothing is a promise the page cannot
     * keep.
     */
    @Test
    void marksNoBoxWhenNothingCanBeOpened() {
        String html = ModuleTreeDiagramPage.html("flowchart LR\n  m0[\"A\"]\n", null, "", false);

        assertTrue(html.contains("const nodes = {}"), "no node may be given something to open");
    }

    /** The class a box stands for reaches the page, which is what hovering and clicking need. */
    @Test
    void carriesWhatEachBoxStandsFor() {
        JsonObject nodes = JsonParser.parseString(
            "{\"m0\":{\"class\":\"\\\\BEAR\\\\Package\\\\Context\\\\ProdModule\","
                + "\"filePath\":\"vendor/bear/package/src/Context/ProdModule.php\"}}"
        ).getAsJsonObject();

        String html = ModuleTreeDiagramPage.html("flowchart LR\n  m0[\"ProdModule\"]\n", nodes, OPEN, false);

        assertTrue(html.contains("ProdModule"), "the class must reach the page");
        assertTrue(html.contains("vendor/bear/package/src/Context/ProdModule.php"), html);
        assertTrue(html.contains("function openModule(fqn) { " + OPEN + " }"), "the bridge must be called");
    }

    /**
     * The node map is read from source this did not write, and reaches the page inside a script.
     * A closing tag in a class name would end that script as surely as one in the diagram would.
     */
    @Test
    void cannotLetTheNodeMapEndTheScript() {
        JsonObject nodes = JsonParser.parseString("{\"m0\":{\"class\":\"</script><script>stolen()</script>\"}}")
            .getAsJsonObject();

        String html = ModuleTreeDiagramPage.html("flowchart LR", nodes, OPEN, false);
        String line = html.lines().filter(text -> text.contains("const nodes =")).findFirst().orElseThrow();

        assertFalse(line.contains("</script>"), line);
        assertTrue(line.contains("<\\/script>"), line);
    }
}
