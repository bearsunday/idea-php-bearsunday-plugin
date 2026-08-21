package idea.bear.sunday.mcp.facts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The convention that ties an ALPS descriptor id to a resource URI and to a schema file name.
 * Both directions are used to look files up, so a spelling either one gets wrong finds nothing.
 */
class NamesTest {

    @Test
    void kebabsAWordBoundary() {
        assertEquals("blog-posting", Names.kebab("BlogPosting"));
        assertEquals("point", Names.kebab("Point"));
        assertEquals("point", Names.kebab("point"));
    }

    /** A run of capitals is one word: url-value is the name a file carries, u-r-l-value is not. */
    @Test
    void keepsARunOfCapitalsTogether() {
        assertEquals("url-value", Names.kebab("URLValue"));
        assertEquals("api-doc", Names.kebab("APIDoc"));
        assertEquals("html", Names.kebab("HTML"));
    }

    @Test
    void keepsDigitsWithTheWordTheyFollow() {
        assertEquals("point3-d", Names.kebab("Point3D"));
    }

    @Test
    void pascalsBackFromAFileName() {
        assertEquals("BlogPosting", Names.pascal("blog-posting"));
        assertEquals("BlogPosting", Names.pascal("blog_posting"));
        assertEquals("Point", Names.pascal("point"));
    }
}
