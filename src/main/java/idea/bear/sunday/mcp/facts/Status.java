package idea.bear.sunday.mcp.facts;

/**
 * Envelope status. Constants are lower case because {@link #name()} is the wire value.
 */
public enum Status {
    ok,
    not_found,
    ambiguous,
    index_not_ready,
    engine_unavailable,
    parse_error
}
