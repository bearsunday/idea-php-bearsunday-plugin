package idea.bear.sunday.alps;

/**
 * Thrown when an ALPS profile was read but is not a profile: malformed JSON or XML, a missing
 * root, or a descriptor tree nested past the depth limit.
 */
public class AlpsParseException extends AlpsProfileException {

    public AlpsParseException(String message) {
        super(message);
    }
}
