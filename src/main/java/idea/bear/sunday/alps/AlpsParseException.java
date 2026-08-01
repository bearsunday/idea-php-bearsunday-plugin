package idea.bear.sunday.alps;

/**
 * Thrown when an ALPS profile cannot be normalized into {@link AlpsProfile}.
 */
public class AlpsParseException extends RuntimeException {

    public AlpsParseException(String message) {
        super(message);
    }
}
