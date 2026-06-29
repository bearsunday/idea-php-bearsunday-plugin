package idea.bear.sunday.util;

import java.util.Map;
import java.util.Set;

/**
 * Maps BEAR.Resource request verbs to the resource class methods that handle them.
 *
 * <p>{@code $resource->get(...)} dispatches to {@code onGet}, {@code ->post(...)} to
 * {@code onPost}, and so on. {@code ->uri(...)} and {@code ->withQuery(...)} carry no verb of
 * their own; the verb is taken from an earlier {@code ->get}/{@code ->post}/... property in the
 * call chain, defaulting to {@code get} when none is present (matching BEAR.Resource's default
 * request method).
 */
public final class ResourceHttpMethods {

    private ResourceHttpMethods() {
    }

    private static final Map<String, String> VERB_TO_METHOD = Map.of(
            "get", "onGet",
            "post", "onPost",
            "put", "onPut",
            "patch", "onPatch",
            "delete", "onDelete",
            "head", "onHead",
            "options", "onOptions"
    );

    /** The BEAR.Resource request verbs ({@code get}, {@code post}, ...). */
    public static Set<String> verbs() {
        return VERB_TO_METHOD.keySet();
    }

    /** The resource method name ({@code onGet}, ...) for a verb, or {@code null} if not a verb. */
    public static String methodName(String verb) {
        return verb == null ? null : VERB_TO_METHOD.get(verb);
    }

    /** {@code true} if the name is a BEAR.Resource request verb. */
    public static boolean isVerb(String name) {
        return VERB_TO_METHOD.containsKey(name);
    }

    /** The default resource method when a chain carries no explicit verb. */
    public static String defaultMethodName() {
        return "onGet";
    }
}