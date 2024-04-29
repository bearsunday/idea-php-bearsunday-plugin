package idea.bear.sunday.util;

import com.damnhandy.uri.template.MalformedUriTemplateException;
import com.damnhandy.uri.template.UriTemplateComponent;
import com.damnhandy.uri.template.impl.UriTemplateParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.WordUtils;

import java.util.LinkedList;

public class UriUtil {

    public static String getUriValue(String resourceName) {
        UriTemplateParser uriTemplateParser = new UriTemplateParser();

        try {
            LinkedList<UriTemplateComponent> list = uriTemplateParser.scan(resourceName);
            if (list.get(0) != null) {
                return list.get(0).getValue();
            }
        } catch (MalformedUriTemplateException me) {
            System.out.println("MalformedUriTemplateException: " + me);
        }

        return null;
    }

    public static String getSearchClassName(String resourceName) {
        String uri = getUriValue(resourceName);
        if (uri == null) {
            return null;
        }

        uri = StringUtils.remove(WordUtils.capitalize(uri, new char[]{'/', '-'}), "-");

        String[] paths = uri.split("/");

        return paths[paths.length - 1];
    }
}
