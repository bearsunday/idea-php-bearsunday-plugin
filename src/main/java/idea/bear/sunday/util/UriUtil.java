package idea.bear.sunday.util;

import com.damnhandy.uri.template.MalformedUriTemplateException;
import com.damnhandy.uri.template.UriTemplateComponent;
import com.damnhandy.uri.template.impl.UriTemplateParser;
import com.intellij.openapi.diagnostic.Logger;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.WordUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedList;

public class UriUtil {

    private static final Logger logger = Logger.getInstance(UriUtil.class);

    private static final char[] URI_PATH_DELIMITERS = {'/', '-'};

    public static String getUriValue(String uri) {
        UriTemplateParser uriTemplateParser = new UriTemplateParser();
        String value = "";

        try {
            LinkedList<UriTemplateComponent> list = uriTemplateParser.scan(uri);
            if (!list.isEmpty() && list.get(0) != null) {
                value = list.get(0).getValue();
            }
        } catch (MalformedUriTemplateException me) {
            logger.error("MalformedUriTemplateException encountered: ", me);
        }

        return value;
    }

    /**
     * Resolves a BEAR.Resource URI to the resource class file path relative to the project root.
     * Returns {@code null} when the URI cannot be parsed.
     *
     * <p>Both camelCase and hyphenated URIs are supported: only the first letter of each
     * {@code '/'} or {@code '-'} delimited segment is capitalized while the rest is preserved,
     * so {@code "blogPosting"} resolves to {@code "BlogPosting"} instead of being lower-cased to
     * {@code "Blogposting"} (see issue #11).
     *
     * @param uri         the resource URI, optionally containing a URI-Template part (e.g. {@code {?id}})
     * @param pageContext when the URI has no scheme, {@code true} resolves under {@code Resource/Page},
     *                    {@code false} under {@code Resource/App}
     */
    public static String toResourceRelativePath(String uri, boolean pageContext) {
        String value = getUriValue(uri);
        if (value.isEmpty()) {
            return null;
        }

        try {
            URI parsed = new URI(value);
            String relPath = "src/Resource/";
            if (parsed.getScheme() == null) {
                relPath += pageContext ? "Page" : "App";
                relPath += capitalizePath(parsed.getPath());
            } else {
                relPath += WordUtils.capitalize(parsed.getScheme()) + capitalizePath(parsed.getPath());
            }

            return relPath.endsWith("/") ? relPath + "index.php" : relPath + ".php";
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String capitalizePath(String path) {
        return StringUtils.remove(WordUtils.capitalize(path, URI_PATH_DELIMITERS), "-");
    }
}
