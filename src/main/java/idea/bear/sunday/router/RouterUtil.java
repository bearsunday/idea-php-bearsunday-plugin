package idea.bear.sunday.router;

import org.apache.commons.text.WordUtils;

public final class RouterUtil {

    private RouterUtil() {
    }

    public static String toResourceFileName(String resourceName) {
        String capitalized = WordUtils.capitalizeFully(resourceName, '/', '-');
        return (capitalized == null ? null : capitalized.replace("-", "")) + ".php";
    }
}
