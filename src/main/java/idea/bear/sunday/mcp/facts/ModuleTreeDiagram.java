package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a module tree answer as a Mermaid flowchart. The picture states exactly what the JSON
 * states and nothing more: the same nodes, the same edges, and the same marks for what could not
 * be read, so a reader who trusts the diagram is trusting the answer rather than a tidied version
 * of it.
 *
 * <p>Written from the payload rather than during the walk, so there is one place where the tree is
 * decided and the drawing cannot drift from it.
 */
final class ModuleTreeDiagram {

    private static final String HEADER = "flowchart LR";
    private static final String KIND_OVERRIDE = "override";

    private final StringBuilder nodes = new StringBuilder();
    private final StringBuilder edges = new StringBuilder();
    private final Map<String, String> idsByClass = new HashMap<>();
    private int nextId;

    private ModuleTreeDiagram() {
    }

    static String mermaid(JsonObject payload) {
        ModuleTreeDiagram diagram = new ModuleTreeDiagram();
        diagram.render(payload);

        return HEADER + "\n" + diagram.nodes + diagram.edges;
    }

    private void render(JsonObject payload) {
        // Drawn first because it is priority 0: what it binds beats every segment, and a reader
        // scanning left to right should meet the strongest node first, as the JSON's ordering does.
        JsonObject frameworkOverride = payload.getAsJsonObject("frameworkOverride");
        if (frameworkOverride != null) {
            walk(frameworkOverride, node(frameworkOverride, "framework override · priority 0"));
        }
        for (JsonElement element : array(payload, "segments")) {
            JsonObject segment = element.getAsJsonObject();
            walk(segment, node(segment, segmentNote(segment)));
        }
        // Drawn last because every segment wraps it, which makes it the weakest node in the tree --
        // the far end of the same left-to-right reading the framework override opens.
        JsonObject assistedModule = payload.getAsJsonObject("assistedModule");
        if (assistedModule != null) {
            walk(assistedModule, node(assistedModule, "assisted injection · priority " + string(assistedModule, "priority")));
        }
        // A segment nothing answers to is part of the picture: leaving it out would draw a context
        // that resolves cleanly when it does not.
        for (JsonElement element : array(payload, "unresolvedSegments")) {
            JsonObject segment = element.getAsJsonObject();
            String id = "u" + nextId++;
            nodes.append("  ").append(id)
                .append("[\"").append(escape(string(segment, "segment"))).append(" · segment unresolved\"]\n");
        }
    }

    private void walk(JsonObject module, String fromId) {
        for (JsonElement element : array(module, "installs")) {
            JsonObject edge = element.getAsJsonObject();
            String toId = node(edge, null);
            boolean override = KIND_OVERRIDE.equals(string(edge, "kind"));
            edges.append("  ").append(fromId)
                // A thick arrow for override, because override() is the edge that changes which
                // binding wins, and a reader skimming the picture should not have to read labels
                // to see one.
                .append(override ? " ==>|" : " -->|").append(escape(edgeLabel(edge))).append("| ")
                .append(toId).append('\n');
            walk(edge, toId);
        }
    }

    /**
     * What an edge says about itself. These belong on the arrow rather than in the box: one module
     * installed from two places is one box with two arrows, and a mark that is true of one arrow is
     * not necessarily true of the other.
     */
    private static String edgeLabel(JsonObject edge) {
        StringBuilder label = new StringBuilder(String.valueOf(string(edge, "kind")));
        String inheritedFrom = string(edge, "inheritedFrom");
        if (inheritedFrom != null) {
            label.append(" \u00b7 from ").append(shortName(inheritedFrom));
        }
        if (edge.has("ownMethod")) {
            label.append(" \u00b7 own method");
        }

        return label.toString();
    }

    /**
     * Declares a node once per module class and answers its id. A module two segments both install
     * is one box with two arrows into it, which is what the walk's own {@code visited} mark says.
     */
    private String node(JsonObject module, @Nullable String note) {
        String moduleClass = string(module, "moduleClass");
        String id = moduleClass == null ? null : idsByClass.get(moduleClass.toLowerCase(Locale.ROOT));
        if (id != null) {
            return id;
        }
        id = "m" + nextId++;
        if (moduleClass != null) {
            idsByClass.put(moduleClass.toLowerCase(Locale.ROOT), id);
        }
        nodes.append("  ").append(id).append("[\"").append(escape(label(module, note))).append("\"]\n");

        return id;
    }

    private static String label(JsonObject module, @Nullable String note) {
        StringBuilder label = new StringBuilder();
        String moduleClass = string(module, "moduleClass");
        label.append(moduleClass == null ? "module not named" : shortName(moduleClass));
        if (note != null) {
            label.append("<br/>").append(note);
        }
        // Every mark the answer carries is drawn, because a box that hides one reads as a module
        // that was read whole.
        if (module.has("moduleUnreadable")) {
            label.append("<br/>").append(string(module, "text"));
        }
        if (module.has("classUnresolved")) {
            label.append("<br/>class unresolved");
        }
        if (module.has("baseClassUnresolved")) {
            label.append("<br/>base unread: ").append(shortName(string(module, "baseClassUnresolved")));
        }
        if (module.has("skipped")) {
            label.append("<br/>cut by node cap");
        }
        if (module.has("visited")) {
            label.append("<br/>expanded above");
        }

        return label.toString();
    }

    private static String segmentNote(JsonObject segment) {
        return string(segment, "segment") + " · priority " + string(segment, "priority")
            + " · " + string(segment, "origin");
    }

    private static String shortName(@Nullable String fqn) {
        return fqn == null ? "?" : fqn.substring(fqn.lastIndexOf('\\') + 1);
    }

    /**
     * Mermaid reads {@code "} as the end of a label and {@code #} as the start of an entity, and a
     * label is one line. An install this could not read carries source text, which is where all
     * three turn up.
     */
    private static String escape(String text) {
        return text.replace("#", "#35;")
            .replace("\"", "#quot;")
            // A pipe ends an edge label as a quote ends a node one.
            .replace("|", "#124;")
            .replace("\n", " ");
    }

    private static JsonArray array(JsonObject json, String key) {
        JsonArray array = json.getAsJsonArray(key);

        return array == null ? new JsonArray() : array;
    }

    @Nullable
    private static String string(JsonObject json, String key) {
        JsonElement element = json.get(key);

        return element == null || element.isJsonNull() ? null : element.getAsString();
    }
}
