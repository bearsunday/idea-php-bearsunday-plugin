package idea.bear.sunday.relation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResourceRelationTest {

    @Test
    void equalRelations() {
        ResourceRelation a = relation();
        ResourceRelation b = relation();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentKind() {
        ResourceRelation a = relation();
        ResourceRelation b = new ResourceRelation(
            "Link",
            a.rel(),
            a.sourceUri(),
            a.sourceFqn(),
            a.targetUri(),
            a.targetMethod(),
            a.rawTargetUri(),
            a.sourceFilePath(),
            a.attributeTextOffset()
        );
        assertNotEquals(a, b);
    }

    @Test
    void accessors() {
        ResourceRelation relation = relation();
        assertEquals("Embed", relation.kind());
        assertEquals("user", relation.rel());
        assertEquals("app://self/dashboard", relation.sourceUri());
        assertEquals("\\MyVendor\\Todo\\Resource\\App\\Dashboard", relation.sourceFqn());
        assertEquals("app://self/user", relation.targetUri());
        assertEquals("onGet", relation.targetMethod());
        assertEquals("app://self/user{?id}", relation.rawTargetUri());
        assertEquals("src/Resource/App/Dashboard.php", relation.sourceFilePath());
        assertEquals(42, relation.attributeTextOffset());
    }

    @Test
    void popupText() {
        assertEquals(
            "Embed rel=\"user\" from Dashboard.php  src=\"app://self/user{?id}\"",
            relation().popupText()
        );
    }

    private static ResourceRelation relation() {
        return new ResourceRelation(
            "Embed",
            "user",
            "app://self/dashboard",
            "\\MyVendor\\Todo\\Resource\\App\\Dashboard",
            "app://self/user",
            "onGet",
            "app://self/user{?id}",
            "src/Resource/App/Dashboard.php",
            42
        );
    }
}
