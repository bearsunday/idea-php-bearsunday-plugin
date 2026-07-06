package idea.bear.sunday.util;

import com.damnhandy.uri.template.MalformedUriTemplateException;
import com.damnhandy.uri.template.UriTemplateComponent;
import com.damnhandy.uri.template.impl.UriTemplateParser;
import com.intellij.openapi.diagnostic.Logger;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import org.apache.commons.text.WordUtils;
import org.jetbrains.annotations.Nullable;

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

    /**
     * Normalizes a supported BEAR.Resource URI for display and relation matching.
     * URI-Template variables are removed and scheme-less absolute paths are resolved in the
     * current resource context. Only {@code app://}, {@code page://}, and {@code /path} URIs are
     * supported by the incoming relation index.
     */
    @Nullable
    public static String normalizeSupportedResourceUri(String uri, boolean pageContext) {
        String value = getUriValue(uri);
        if (value.isEmpty()) {
            return null;
        }

        try {
            URI parsed = new URI(value);
            String scheme = parsed.getScheme();
            String path = parsed.getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }

            if (scheme == null) {
                if (!path.startsWith("/")) {
                    return null;
                }
                return (pageContext ? "page" : "app") + "://self" + path;
            }

            if (!scheme.equalsIgnoreCase("app") && !scheme.equalsIgnoreCase("page")) {
                return null;
            }

            String authority = parsed.getRawAuthority();
            if (authority == null || authority.isBlank()) {
                return null;
            }

            return scheme.toLowerCase() + "://" + authority + path;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * Resolves only the supported v1 incoming-relation URI forms to a resource path.
     */
    @Nullable
    public static String toSupportedResourceRelativePath(String uri, boolean pageContext) {
        String normalizedUri = normalizeSupportedResourceUri(uri, pageContext);
        return normalizedUri == null ? null : toResourceRelativePath(normalizedUri, pageContext);
    }

    /**
     * Converts a BEAR.Resource PHP class to its conventional resource URI.
     */
    @Nullable
    public static String toResourceUri(@Nullable PhpClass phpClass) {
        if (phpClass == null) {
            return null;
        }

        String nameSpace = phpClass.getNamespaceName();
        if (!nameSpace.contains("Resource\\")) {
            return null;
        }

        int index = nameSpace.indexOf("Resource\\");
        String scheme = nameSpace.substring(index + 9).replace("\\", "/").toLowerCase();

        if (scheme.startsWith("app")) {
            scheme = scheme.replace("app", "app://self");
        }
        if (scheme.startsWith("page")) {
            scheme = scheme.replace("page", "page://self");
        }

        String className = phpClass.getName().toLowerCase();
        return scheme + className;
    }

    private static String capitalizePath(String path) {
        return WordUtils.capitalize(path, URI_PATH_DELIMITERS).replace("-", "");
    }
}
