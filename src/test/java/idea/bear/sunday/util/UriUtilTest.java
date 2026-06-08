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

    @Test
    void relativePathSimpleUri() {
        assertEquals("src/Resource/App/User.php", UriUtil.toResourceRelativePath("app://self/user", false));
    }

    @Test
    void relativePathPageScheme() {
        assertEquals("src/Resource/Page/Index.php", UriUtil.toResourceRelativePath("page://self/index", false));
    }

    @Test
    void relativePathHyphenatedUri() {
        assertEquals("src/Resource/App/BlogPosting.php", UriUtil.toResourceRelativePath("app://self/blog-posting", false));
    }

    @Test
    void relativePathCamelCaseUri() {
        // issue #11: a camelCase URI must keep its inner capital (BlogPosting, not Blogposting)
        assertEquals("src/Resource/App/BlogPosting.php", UriUtil.toResourceRelativePath("app://self/blogPosting", false));
    }

    @Test
    void relativePathCamelCaseUriWithTemplate() {
        assertEquals("src/Resource/App/BlogPosting.php", UriUtil.toResourceRelativePath("app://self/blogPosting{?id}", false));
    }

    @Test
    void relativePathNestedUri() {
        assertEquals("src/Resource/App/Blog/Posting.php", UriUtil.toResourceRelativePath("app://self/blog/posting", false));
    }

    @Test
    void relativePathSchemeLessAppContext() {
        assertEquals("src/Resource/App/User.php", UriUtil.toResourceRelativePath("/user", false));
    }

    @Test
    void relativePathSchemeLessPageContext() {
        assertEquals("src/Resource/Page/Index.php", UriUtil.toResourceRelativePath("/index", true));
    }

    @Test
    void normalizeSupportedAppUriWithTemplate() {
        assertEquals("app://self/user", UriUtil.normalizeSupportedResourceUri("app://self/user{?id}", false));
    }

    @Test
    void normalizeSupportedPageUriWithTemplate() {
        assertEquals("page://self/index", UriUtil.normalizeSupportedResourceUri("page://self/index{?id}", false));
    }

    @Test
    void normalizeSupportedRelativeAppContext() {
        assertEquals("app://self/user", UriUtil.normalizeSupportedResourceUri("/user{?id}", false));
    }

    @Test
    void normalizeSupportedRelativePageContext() {
        assertEquals("page://self/user", UriUtil.normalizeSupportedResourceUri("/user{?id}", true));
    }

    @Test
    void supportedRelativePathCamelCaseUriWithTemplate() {
        assertEquals("src/Resource/App/BlogPosting.php", UriUtil.toSupportedResourceRelativePath("app://self/blogPosting{?id}", false));
    }

    @Test
    void unsupportedQueryScheme() {
        assertNull(UriUtil.normalizeSupportedResourceUri("query://self/user", false));
        assertNull(UriUtil.toSupportedResourceRelativePath("query://self/user", false));
    }

    @Test
    void relativePathEmptyUri() {
        assertNull(UriUtil.toResourceRelativePath("", false));
    }
}
