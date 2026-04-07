package idea.bear.sunday.router;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.WordUtils;

public final class RouterUtil {

    private RouterUtil() {
    }

    public static String toResourceFileName(String resourceName) {
        return StringUtils.remove(WordUtils.capitalizeFully(resourceName, '/', '-'), "-") + ".php";
    }
}
