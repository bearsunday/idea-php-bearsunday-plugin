package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonElement;

/**
 * How much of a file an answer may carry verbatim. An MCP answer lands in the agent's context
 * whole, so one call on a large JSON Schema, OpenAPI document or ALPS profile can spend the whole
 * window. The tools always answer with the parts that summarise -- the field names, the paths,
 * the pointers -- and leave the verbatim part out when it is over the limit, marking the answer
 * {@code truncated} so the caller knows the file is there to be opened directly.
 */
final class Payloads {

    /**
     * Characters of serialized JSON one embedded document may carry. Roughly sixteen thousand
     * tokens: large enough that no schema written by hand comes near it, small enough that a
     * generated one cannot take the context with it.
     */
    static final int MAX_EMBEDDED_CHARS = 65_536;

    private Payloads() {
    }

    /** Whether a document is small enough to be carried in an answer as it stands. */
    static boolean fitsInAnAnswer(JsonElement element) {
        return element.toString().length() <= MAX_EMBEDDED_CHARS;
    }
}
