package idea.bear.sunday.util;

import com.damnhandy.uri.template.MalformedUriTemplateException;
import com.damnhandy.uri.template.UriTemplateComponent;
import com.damnhandy.uri.template.impl.UriTemplateParser;
import com.intellij.openapi.diagnostic.Logger;

import java.util.LinkedList;

public class UriUtil {

    private static final UriTemplateParser uriTemplateParser = new UriTemplateParser();
    private static final Logger logger = Logger.getInstance(UriUtil.class);

    public static String getUriValue(String uri) {
        String value = "";

        try {
            LinkedList<UriTemplateComponent> list = uriTemplateParser.scan(uri);
            if (!list.isEmpty() && list.get(0) != null) {
                value = list.get(0).getValue();
            }
        } catch (MalformedUriTemplateException me) {
            logger.debug(me.getMessage());
        }

        return value;
    }
}
