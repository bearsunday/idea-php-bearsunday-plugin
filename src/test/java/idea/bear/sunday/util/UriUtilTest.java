package idea.bear.sunday.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UriUtilTest {

    @Test
    void simpleUri() {
        assertEquals("app://self/user", UriUtil.getUriValue("app://self/user"));
    }

    @Test
    void uriWithTemplate() {
        assertEquals("app://self/user", UriUtil.getUriValue("app://self/user{?id}"));
    }

    @Test
    void emptyUri() {
        assertEquals("", UriUtil.getUriValue(""));
    }

    @Test
    void pageScheme() {
        assertEquals("page://self/index", UriUtil.getUriValue("page://self/index"));
    }
}
