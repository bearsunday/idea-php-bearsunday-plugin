package idea.bear.sunday;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {

    @Test
    void defaultValues() {
        Settings settings = new Settings();
        assertEquals("aura.route.php", settings.auraRouteFile);
    }

    @Test
    void defaultResourcePaths() {
        Settings settings = new Settings();
        assertTrue(settings.resourcePaths.contains("src/Resource/Page"));
        assertTrue(settings.resourcePaths.contains("src/Resource/Page/Content"));
        assertEquals(2, settings.resourcePaths.size());
    }

    @Test
    void defaultSqlPaths() {
        Settings settings = new Settings();
        assertTrue(settings.sqlPaths.contains("var/db/sql"));
        assertTrue(settings.sqlPaths.contains("var/db/sql_preview"));
        assertEquals(2, settings.sqlPaths.size());
    }

    @Test
    void defaultJsonSchemaPaths() {
        Settings settings = new Settings();
        assertTrue(settings.jsonSchemaPath.contains("var/json_schema"));
        assertTrue(settings.jsonSchemaPath.contains("var/schema/response"));
        assertEquals(2, settings.jsonSchemaPath.size());
    }

    @Test
    void defaultJsonValidatePaths() {
        Settings settings = new Settings();
        assertTrue(settings.jsonValidatePath.contains("var/json_validate"));
        assertTrue(settings.jsonValidatePath.contains("var/schema/request"));
        assertEquals(2, settings.jsonValidatePath.size());
    }

    @Test
    void getStateReturnsSelf() {
        Settings settings = new Settings();
        assertSame(settings, settings.getState());
    }
}
