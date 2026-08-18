package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Renders an object graph answer as a Mermaid flowchart. The picture states exactly what the JSON
 * states and nothing more -- the same nodes, the same edges, the same marks for what could not be
 * resolved -- so a reader who trusts the drawing is trusting the answer and not a tidied version of
 * it.
 *
 * <p>Written from the payload rather than during the walk, so the graph is decided in one place and
 * the drawing cannot drift from it.
 */
final class ObjectGraphDiagram {

    private static final String HEADER = "flowchart TD";

    private final StringBuilder nodes = new StringBuilder();
    private final StringBuilder edges = new StringBuilder();
    private final Map<String, String> idsByKey = new HashMap<>();
    private final JsonObject nodeFacts = new JsonObject();
    private int nextId;

    private ObjectGraphDiagram() {
    }

    /**
     * The drawing, and what each of its boxes stands for -- keyed by the id the Mermaid source uses,
     * because those ids are this renderer's own and mean nothing to a client holding the graph.
     */
    record Drawing(String mermaid, JsonObject nodes) {
    }

    static Drawing draw(JsonObject payload) {
        ObjectGraphDiagram diagram = new ObjectGraphDiagram();
        diagram.render(payload);

        return new Drawing(HEADER + "\n" + diagram.nodes + diagram.edges, diagram.nodeFacts);
    }

    private void render(JsonObject payload) {
        for (JsonElement element : array(payload, "nodes")) {
            node(element.getAsJsonObject());
        }
        for (JsonElement element : array(payload, "edges")) {
            edge(element.getAsJsonObject());
        }
    }

    private void node(JsonObject node) {
        String key = string(node, "key");
        String id = idsByKey.computeIfAbsent(key, ignored -> "n" + nextId++);
        JsonObject facts = new JsonObject();
        facts.addProperty("key", key);
        nodeFacts.add(id, facts);

        String label = shortName(string(node, "type"));
        String name = string(node, "name");
        if (name != null) {
            // The name half of the key is what tells two bindings of one interface apart, so it is
            // on the box for the same reason it is in the key.
            label = label + "#" + shortName(name);
        }
        String implementation = string(node, "implementation");
        String resolution = string(node, "resolution");
        String note = implementation == null
            ? resolution
            : shortName(implementation) + (isPlain(resolution) ? "" : " · " + resolution);
        nodes.append("  ")
            .append(id)
            .append("[\"")
            .append(escape(label))
            .append("<br/>")
            .append(escape(note))
            .append("\"]\n");
    }

    private void edge(JsonObject edge) {
        String from = idsByKey.get(string(edge, "from"));
        String to = idsByKey.get(string(edge, "to"));
        if (from == null || to == null) {
            // An edge into a node the walk stopped before drawing would point at nothing; the JSON
            // still holds it, which is where a reader counting edges should be looking.
            return;
        }
        String label = string(edge, "parameter");
        String method = string(edge, "method");
        if (method != null) {
            label = method + "($" + label + ")";
        } else {
            label = "$" + label;
        }
        if (edge.has("optional")) {
            label = label + " · optional";
        }
        edges.append("  ")
            .append(from)
            .append(edge.has("cycle") ? " -.->" : " -->")
            .append("|\"")
            .append(escape(label))
            .append("\"| ")
            .append(to)
            .append("\n");
    }

    /** A resolution the implementation already tells the reader; naming it twice adds nothing. */
    private static boolean isPlain(String resolution) {
        return "static".equals(resolution);
    }

    private static String shortName(String fqn) {
        int at = fqn.lastIndexOf('\\');

        return at < 0 ? fqn : fqn.substring(at + 1);
    }

    /**
     * Mermaid reads a quoted label as text but still takes the quote and the backslash for its own,
     * and a PHP name is nothing but backslashes.
     */
    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "#quot;");
    }

    private static JsonArray array(JsonObject json, String name) {
        JsonArray array = json.getAsJsonArray(name);

        return array == null ? new JsonArray() : array;
    }

    private static String string(JsonObject json, String name) {
        JsonElement element = json.get(name);

        return element == null || element.isJsonNull() ? null : element.getAsString();
    }
}
