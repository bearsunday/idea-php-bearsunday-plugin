package idea.bear.sunday.graph;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.text.StringUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The HTML page the module tree is drawn in. Mermaid is bundled rather than fetched, so the
 * picture is the same one every time and an IDE with no network still draws it.
 */
final class ModuleTreeDiagramPage {

    private static final Logger LOG = Logger.getInstance(ModuleTreeDiagramPage.class);
    private static final String MERMAID_RESOURCE = "/webview/mermaid.min.js";

    private static String mermaidScript;

    private ModuleTreeDiagramPage() {
    }

    /**
     * @param diagram Mermaid source, as {@code ModuleTreeDiagram} wrote it
     * @param dark    whether the IDE is on a dark theme, so the page is not the one bright rectangle
     *                in a dark editor
     */
    static String html(String diagram, boolean dark) {
        String background = dark ? "#2b2d30" : "#ffffff";
        String foreground = dark ? "#dfe1e5" : "#1e1f22";

        return """
            <!doctype html>
            <html>
            <head>
            <meta charset="utf-8">
            <style>
              html, body { margin: 0; padding: 0; background: %s; color: %s; }
              body { font-family: system-ui, sans-serif; }
              /* The graph is wider than the tool window long before it is taller, so the page
                 scrolls in both directions rather than shrinking the boxes to illegibility. */
              #diagram { padding: 12px; overflow: auto; }
              #error { padding: 12px; white-space: pre-wrap; font-family: monospace; }
            </style>
            <script>%s</script>
            </head>
            <body>
            <div id="diagram"></div>
            <div id="error"></div>
            <script>
              const source = %s;
              mermaid.initialize({ startOnLoad: false, theme: '%s', securityLevel: 'strict' });
              mermaid.render('moduleTree', source).then(function (result) {
                document.getElementById('diagram').innerHTML = result.svg;
              }).catch(function (error) {
                // A diagram that will not draw is shown as its source: the answer is still the
                // answer, and hiding it would leave an empty panel saying nothing at all.
                document.getElementById('error').textContent = String(error) + '\\n\\n' + source;
              });
            </script>
            </body>
            </html>
            """.formatted(background, foreground, mermaidScript(), json(diagram), dark ? "dark" : "default");
    }

    /** Read once: the bundle is megabytes, and the panel redraws on every context change. */
    private static synchronized String mermaidScript() {
        if (mermaidScript != null) {
            return mermaidScript;
        }
        try (InputStream stream = ModuleTreeDiagramPage.class.getResourceAsStream(MERMAID_RESOURCE)) {
            mermaidScript = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOG.warn("Could not read the bundled Mermaid script", exception);
            mermaidScript = "";
        }

        return mermaidScript;
    }

    /** The diagram reaches the page as a JS string literal, so nothing in it can end the script. */
    private static String json(String text) {
        return '"' + StringUtil.escapeStringCharacters(text).replace("</", "<\\/") + '"';
    }
}
