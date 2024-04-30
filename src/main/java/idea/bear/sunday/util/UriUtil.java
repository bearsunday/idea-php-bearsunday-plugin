package idea.bear.sunday.util;

import com.damnhandy.uri.template.MalformedUriTemplateException;
import com.damnhandy.uri.template.UriTemplateComponent;
import com.damnhandy.uri.template.impl.UriTemplateParser;

import java.util.LinkedList;

public class UriUtil {

    public static String getUriValue(String uri) {
        UriTemplateParser uriTemplateParser = new UriTemplateParser();

        try {
            LinkedList<UriTemplateComponent> list = uriTemplateParser.scan(uri);
            if (list.get(0) != null) {
                return list.get(0).getValue();
            }
        } catch (MalformedUriTemplateException me) {
            System.out.println("MalformedUriTemplateException: " + me);
        }

        return null;
    }
}
