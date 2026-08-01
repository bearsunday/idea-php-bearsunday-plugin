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
                if (i > 0) {
                    kebab.append('-');
                }
                kebab.append(Character.toLowerCase(character));
            } else {
                kebab.append(character);
            }
        }

        return kebab.toString();
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
