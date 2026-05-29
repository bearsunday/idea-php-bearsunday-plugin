package idea.bear.sunday;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;

@State(
        name = "BEAR.SundaySettings",
        storages = {
                @Storage("bear-sunday.xml")
        }
)

public class Settings implements PersistentStateComponent<Settings> {

    public boolean pluginEnabled = true;

    public static final String DEFAULT_AURA_ROUTE_FILE = "aura.route.php";
    public static final Collection<String> DEFAULT_RESOURCE_PATH = Arrays.asList(
            "src/Resource/Page",
            "src/Resource/Page/Content"
    );
    public static final Collection<String> DEFAULT_SQL_PATH = Arrays.asList(
            "var/db/sql",
            "var/db/sql_preview"
    );
    public static final Collection<String> DEFAULT_JSON_SCHEMA_PATH = Arrays.asList(
            "var/json_schema",
            "var/schema/response"
    );

    public static final Collection<String> DEFAULT_JSON_VALIDATE_PATH = Arrays.asList(
            "var/json_validate",
            "var/schema/request"
    );

    public String auraRouteFile = DEFAULT_AURA_ROUTE_FILE;
    public Collection<String> resourcePaths = DEFAULT_RESOURCE_PATH;
    public Collection<String> sqlPaths = DEFAULT_SQL_PATH;
    public Collection<String> jsonSchemaPath = DEFAULT_JSON_SCHEMA_PATH;
    public Collection<String> jsonValidatePath = DEFAULT_JSON_VALIDATE_PATH;

    protected Project project;

    public static Settings getInstance(Project project) { return project.getService(Settings.class); }

    @Nullable
    @Override
    public Settings getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull Settings settings) {
        XmlSerializerUtil.copyBean(settings, this);
    }
}

