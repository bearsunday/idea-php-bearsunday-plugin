package idea.bear.sunday.alps;

/**
 * A profile the tools could not turn into an {@link AlpsProfile}. The two reasons are kept apart
 * by the subclasses because they are different things to tell a caller: a profile whose text is
 * malformed will stay malformed until someone edits it, while one that could not be read may
 * well answer on the next attempt.
 */
public abstract class AlpsProfileException extends RuntimeException {

    protected AlpsProfileException(String message) {
        super(message);
    }
}
