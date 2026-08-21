package idea.bear.sunday.mcp.facts;

import java.util.Locale;

/**
 * The BEAR.Sunday naming convention that ties an ALPS descriptor id to a resource URI and to a
 * schema file name: {@code BlogPosting} &lt;-&gt; {@code blog-posting}.
 */
final class Names {

    private Names() {
    }

    static String kebab(String name) {
        StringBuilder kebab = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (Character.isUpperCase(character)) {
                if (i > 0 && startsNewWord(name, i)) {
                    kebab.append('-');
                }
                kebab.append(Character.toLowerCase(character));
            } else {
                kebab.append(character);
            }
        }

        return kebab.toString();
    }

    /**
     * A run of capitals is one word: {@code URLValue} is {@code url-value}, the spelling a
     * resource file and a schema file carry, not {@code u-r-l-value}, which matches nothing. A
     * capital opens a word when what precedes it is not a capital, or when what follows it is
     * lower case -- the last capital of a run belongs to the word after it.
     */
    private static boolean startsNewWord(String name, int index) {
        if (!Character.isUpperCase(name.charAt(index - 1))) {
            return true;
        }

        return index + 1 < name.length() && Character.isLowerCase(name.charAt(index + 1));
    }

    static String pascal(String name) {
        StringBuilder pascal = new StringBuilder();
        for (String part : name.split("[-_]")) {
            if (!part.isBlank()) {
                pascal.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
            }
        }

        return pascal.toString();
    }
}
