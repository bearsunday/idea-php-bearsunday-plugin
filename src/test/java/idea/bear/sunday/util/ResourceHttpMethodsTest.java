package idea.bear.sunday.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResourceHttpMethodsTest {

    @Test
    void mapsVerbsToOnMethods() {
        assertEquals("onGet", ResourceHttpMethods.methodName("get"));
        assertEquals("onPost", ResourceHttpMethods.methodName("post"));
        assertEquals("onPut", ResourceHttpMethods.methodName("put"));
        assertEquals("onPatch", ResourceHttpMethods.methodName("patch"));
        assertEquals("onDelete", ResourceHttpMethods.methodName("delete"));
        assertEquals("onHead", ResourceHttpMethods.methodName("head"));
        assertEquals("onOptions", ResourceHttpMethods.methodName("options"));
    }

    @Test
    void rejectsNonVerb() {
        assertNull(ResourceHttpMethods.methodName("uri"));
        assertNull(ResourceHttpMethods.methodName("withQuery"));
        assertNull(ResourceHttpMethods.methodName("request"));
        assertFalse(ResourceHttpMethods.isVerb("uri"));
    }

    @Test
    void defaultMethodIsOnGet() {
        assertEquals("onGet", ResourceHttpMethods.defaultMethodName());
    }

    @Test
    void verbsAreComplete() {
        assertEquals(7, ResourceHttpMethods.verbs().size());
    }
}