package idea.bear.sunday.alps;

/**
 * Thrown when an ALPS profile's text could not be obtained at all -- an I/O failure, or a file
 * the virtual file system cannot decode. Reported apart from {@link AlpsParseException} so that
 * a caller does not tell an agent the profile is malformed when nobody has read it.
 */
public final class AlpsUnreadableException extends AlpsProfileException {

    public AlpsUnreadableException(String message) {
        super(message);
    }
}
