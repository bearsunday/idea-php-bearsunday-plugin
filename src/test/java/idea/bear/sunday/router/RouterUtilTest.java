package idea.bear.sunday.router;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RouterUtilTest {

    @Test
    void simpleResource() {
        assertEquals("/User.php", RouterUtil.toResourceFileName("/user"));
    }

    @Test
    void nestedResource() {
        assertEquals("/User/Profile.php", RouterUtil.toResourceFileName("/user/profile"));
    }

    @Test
    void hyphenatedResource() {
        assertEquals("/UserProfile.php", RouterUtil.toResourceFileName("/user-profile"));
    }

    @Test
    void deeplyNestedResource() {
        assertEquals("/Admin/User/Setting.php", RouterUtil.toResourceFileName("/admin/user/setting"));
    }

    @Test
    void hyphenatedNestedResource() {
        assertEquals("/Api/UserProfile.php", RouterUtil.toResourceFileName("/api/user-profile"));
    }

    @Test
    void emptyPath() {
        assertEquals(".php", RouterUtil.toResourceFileName(""));
    }

    @Test
    void rootPath() {
        assertEquals("/.php", RouterUtil.toResourceFileName("/"));
    }

    @Test
    void trailingSlash() {
        assertEquals("/User/.php", RouterUtil.toResourceFileName("/user/"));
    }

    @Test
    void nullInput() {
        // WordUtils.capitalizeFully(null) returns null, and "null" + ".php" results from string concatenation
        assertEquals("null.php", RouterUtil.toResourceFileName(null));
    }
}
