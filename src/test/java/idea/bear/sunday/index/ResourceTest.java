package idea.bear.sunday.index;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResourceTest {

    @Test
    void equalResources() {
        Resource a = new Resource("app://self/user", "\\App\\Resource\\App\\User");
        Resource b = new Resource("app://self/user", "\\App\\Resource\\App\\User");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentUri() {
        Resource a = new Resource("app://self/user", "\\App\\Resource\\App\\User");
        Resource b = new Resource("page://self/user", "\\App\\Resource\\App\\User");
        assertNotEquals(a, b);
    }

    @Test
    void differentFqn() {
        Resource a = new Resource("app://self/user", "\\App\\Resource\\App\\User");
        Resource b = new Resource("app://self/user", "\\App\\Resource\\App\\Admin");
        assertNotEquals(a, b);
    }

    @Test
    void accessors() {
        Resource resource = new Resource("app://self/user", "\\App\\Resource\\App\\User");
        assertEquals("app://self/user", resource.uri());
        assertEquals("\\App\\Resource\\App\\User", resource.fqn());
    }
}
