package idea.bear.sunday.graph;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The HTML page the module tree is drawn in. Mermaid is bundled rather than fetched, so the
 * picture is the same one every time and an IDE with no network still draws it.
 *
 * <p>A box carries a short name because that is all a box has room for. Hovering one shows what it
 * left out -- the class in full, and the file it is written in -- and clicking one asks the IDE to
 * open that class, which is the step a reader would otherwise take by hand through a search.
 */
final class ModuleTreeDiagramPage {

    private static final Logger LOG = Logger.getInstance(ModuleTreeDiagramPage.class);
    private static final String MERMAID_RESOURCE = "/webview/mermaid.min.js";

    private static String mermaidScript;

    private ModuleTreeDiagramPage() {
    }

    /**
     * @param diagram Mermaid source, as {@code ModuleTreeDiagram} wrote it
     * @param nodes   what each node id stands for, or {@code null} when nothing can be opened --
     *                without a bridge to the IDE a box that highlights under the pointer would
     *                promise a click that does nothing
     * @param open    JavaScript that sends the class named by the {@code fqn} variable to the IDE
     * @param dark    whether the IDE is on a dark theme, so the page is not the one bright rectangle
     *                in a dark editor
     */
    static String html(String diagram, @Nullable JsonObject nodes, String open, boolean dark) {
        String background = dark ? "#2b2d30" : "#ffffff";
        String foreground = dark ? "#dfe1e5" : "#1e1f22";
        String tipBackground = dark ? "#3c3f41" : "#f7f8fa";
        String tipBorder = dark ? "#5a5d5f" : "#c9ccd6";

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
              /* Only a box with a class behind it is marked, so the pointer tells the truth about
                 which ones will open something. */
              g.node.bear-open { cursor: pointer; }
              g.node.bear-open:hover rect { stroke-width: 2px; }
              #tip {
                position: fixed; display: none; z-index: 10; pointer-events: none;
                max-width: 42em; padding: 6px 8px; border-radius: 4px;
                background: %s; border: 1px solid %s; color: %s;
                font-family: system-ui, sans-serif; font-size: 12px; line-height: 1.5;
              }
              #tip .fqn { font-family: ui-monospace, monospace; }
              #tip .path { opacity: 0.75; }
              #tip .hint { opacity: 0.6; }
            </style>
            <script>%s</script>
            </head>
            <body>
            <div id="diagram"></div>
            <div id="error"></div>
            <div id="tip"></div>
            <script>
              const source = %s;
              const nodes = %s;

              function openModule(fqn) { %s }

              /* Mermaid names a node's group "flowchart-<id>-<n>", where <id> is the id written in
                 the source. That is the only thread back from a drawn box to what it was drawn
                 for; a box whose id does not read that way is simply left alone. */
              function nodeIdOf(element) {
                const id = element.getAttribute('data-id') || element.id || '';
                const match = /^flowchart-(.+)-\\d+$/.exec(id);

                return match ? match[1] : id;
              }

              function tipHtml(facts) {
                let html = '<div class="fqn">' + escapeHtml(facts['class']) + '</div>';
                if (facts.note) { html += '<div>' + escapeHtml(facts.note) + '</div>'; }
                if (facts.filePath) { html += '<div class="path">' + escapeHtml(facts.filePath) + '</div>'; }

                return html + '<div class="hint">Click to open</div>';
              }

              function escapeHtml(text) {
                const div = document.createElement('div');
                div.textContent = String(text);

                return div.innerHTML;
              }

              function bind(svg) {
                const tip = document.getElementById('tip');
                svg.querySelectorAll('g.node').forEach(function (node) {
                  const facts = nodes[nodeIdOf(node)];
                  if (!facts) { return; }
                  node.classList.add('bear-open');
                  node.addEventListener('mousemove', function (event) {
                    tip.innerHTML = tipHtml(facts);
                    tip.style.display = 'block';
                    /* Kept inside the window: a box near the right edge would otherwise open a
                       tooltip that is cut off exactly where the class name gets specific. */
                    const width = tip.offsetWidth;
                    const height = tip.offsetHeight;
                    const x = Math.min(event.clientX + 12, window.innerWidth - width - 8);
                    const y = event.clientY + 18 + height > window.innerHeight
                      ? event.clientY - height - 12
                      : event.clientY + 18;
                    tip.style.left = Math.max(8, x) + 'px';
                    tip.style.top = Math.max(8, y) + 'px';
                  });
                  node.addEventListener('mouseleave', function () { tip.style.display = 'none'; });
                  node.addEventListener('click', function () {
                    tip.style.display = 'none';
                    openModule(facts['class']);
                  });
                });
              }

              mermaid.initialize({ startOnLoad: false, theme: '%s', securityLevel: 'strict' });
              mermaid.render('moduleTree', source).then(function (result) {
                document.getElementById('diagram').innerHTML = result.svg;
                bind(document.getElementById('diagram'));
              }).catch(function (error) {
                // A diagram that will not draw is shown as its source: the answer is still the
                // answer, and hiding it would leave an empty panel saying nothing at all.
                document.getElementById('error').textContent = String(error) + '\\n\\n' + source;
              });
            </script>
            </body>
            </html>
            """.formatted(
            background, foreground,
            tipBackground, tipBorder, foreground,
            mermaidScript(), json(diagram), nodesJson(nodes), open, dark ? "dark" : "default"
        );
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

    /**
     * The node map, written into the script the same way. A class name cannot hold a closing script
     * tag, but it is read from source this did not write, and the page must not depend on that.
     */
    private static String nodesJson(@Nullable JsonObject nodes) {
        return nodes == null ? "{}" : nodes.toString().replace("</", "<\\/");
    }
}
