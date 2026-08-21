package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * The JSON envelope every MCP fact tool returns: a status, the provenance of the answer, and the
 * payload merged into the root object.
 */
public final class Envelope {

    private final JsonObject root;

    private Envelope(JsonObject root) {
        this.root = root;
    }

    public static Envelope ok(Provenance provenance, JsonObject payload) {
        JsonObject root = withStatus(Status.ok);
        root.add("provenance", toJson(provenance));
        for (Map.Entry<String, JsonElement> entry : payload.entrySet()) {
            root.add(entry.getKey(), entry.getValue());
        }

        return new Envelope(root);
    }

    public static Envelope ambiguous(List<String> candidates) {
        JsonObject root = withStatus(Status.ambiguous);
        JsonArray array = new JsonArray();
        candidates.forEach(array::add);
        root.add("candidates", array);

        return new Envelope(root);
    }

    public static Envelope notFound(String detail) {
        return error(Status.not_found, detail);
    }

    /**
     * The question cannot be answered yet because the indexes are still building. Distinct from
     * {@link #notFound(String)} on purpose: an agent told "not found" concludes the thing does
     * not exist, while this says the same question is worth asking again.
     */
    public static Envelope indexNotReady(String detail) {
        return error(Status.index_not_ready, detail);
    }

    public static Envelope parseError(String detail) {
        return error(Status.parse_error, detail);
    }

    public static Envelope engineUnavailable(String detail) {
        return error(Status.engine_unavailable, detail);
    }

    public String toJson() {
        return root.toString();
    }

    private static Envelope error(Status status, String detail) {
        JsonObject root = withStatus(status);
        root.addProperty("error", detail);

        return new Envelope(root);
    }

    private static JsonObject withStatus(Status status) {
        JsonObject root = new JsonObject();
        root.addProperty("status", status.name());

        return root;
    }

    private static JsonObject toJson(Provenance provenance) {
        JsonObject json = new JsonObject();
        json.addProperty("source", provenance.source());
        json.addProperty("path", provenance.path());
        if (provenance.offset() == null) {
            json.add("offset", JsonNull.INSTANCE);
        } else {
            json.addProperty("offset", provenance.offset());
        }
        json.addProperty("fresh", provenance.fresh());

        return json;
    }
}
