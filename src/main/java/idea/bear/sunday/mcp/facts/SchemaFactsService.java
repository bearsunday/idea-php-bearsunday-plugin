package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.jetbrains.php.lang.psi.elements.ClassReference;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.PhpAttribute;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression;
import idea.bear.sunday.Settings;
import idea.bear.sunday.body.BodyJsonSchemaPath;
import idea.bear.sunday.resource.ResourceClassResolver;
import idea.bear.sunday.util.UriUtil;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the JSON Schema files of a resource, either declared by a {@code #[JsonSchema]} attribute
 * or following the {@code var/json_schema} naming convention, and reports what they describe.
 */
@Service(Service.Level.PROJECT)
public final class SchemaFactsService {

    static final String KIND_RESPONSE = "response";
    static final String KIND_REQUEST = "request";

    private static final String JSON_SCHEMA = "JsonSchema";
    private static final String SOURCE_ATTRIBUTE = "attribute";
    private static final String SOURCE_CONVENTION = "convention";
    private static final String SOURCE_FILE = "file";

    private final Project project;

    public SchemaFactsService(Project project) {
        this.project = project;
    }

    public static SchemaFactsService getInstance(Project project) {
        return project.getService(SchemaFactsService.class);
    }

    public String lookup(@Nullable String resourceUri, @Nullable String method, @Nullable String schemaFile, @Nullable String kind) {
        return ReadAction.compute(() -> lookupSchema(resourceUri, method, schemaFile, kind));
    }

    /**
     * Schemas of one resource, in declaration order: the attribute-declared ones, or the
     * conventional {@code var/json_schema} file when the resource declares none.
     */
    List<SchemaMatch> matchesForResource(String resourceUri, @Nullable String method, String kind) {
        String normalizedUri = UriUtil.normalizeSupportedResourceUri(resourceUri.trim(), false);
        if (normalizedUri == null) {
            return List.of();
        }
        Optional<PhpClass> resolved = ResourceClassResolver.resolveCached(project, normalizedUri);
        if (resolved.isEmpty()) {
            return List.of();
        }
        PhpClass phpClass = resolved.get();
        Map<String, SchemaMatch> matches = new LinkedHashMap<>();
        for (Method resourceMethod : targetMethods(phpClass, method)) {
            for (String fileName : declaredSchemaFiles(resourceMethod, kind)) {
                for (SchemaMatch match : byFileName(fileName, kind, SOURCE_ATTRIBUTE)) {
                    matches.putIfAbsent(match.path(), match);
                }
            }
        }
        if (!matches.isEmpty() || !KIND_RESPONSE.equals(kind)) {
            return List.copyOf(matches.values());
        }
        SchemaMatch conventional = conventionMatch(phpClass);

        return conventional == null ? List.of() : List.of(conventional);
    }

    private String lookupSchema(@Nullable String resourceUri, @Nullable String method, @Nullable String schemaFile, @Nullable String kind) {
        String resolvedKind = normalizeKind(kind);
        if (resolvedKind == null) {
            return Envelope.notFound("Unknown kind: " + kind + " (expected response or request).").toJson();
        }
        if (!isSet(resourceUri) && !isSet(schemaFile)) {
            return Envelope.notFound("Either resourceUri or schemaFile is required.").toJson();
        }
        List<SchemaMatch> matches = new ArrayList<>();
        if (isSet(schemaFile)) {
            matches.addAll(byFileName(schemaFile.trim(), resolvedKind, SOURCE_FILE));
        }
        if (isSet(resourceUri)) {
            matches.addAll(matchesForResource(resourceUri, method, resolvedKind));
        }

        JsonArray array = new JsonArray();
        for (SchemaMatch match : matches) {
            array.add(matchJson(match));
        }
        JsonObject payload = new JsonObject();
        payload.add("matches", array);
        Provenance provenance = matches.isEmpty()
            ? Provenance.derived(isSet(resourceUri) ? resourceUri : schemaFile)
            : Provenance.ofFile(FactsFiles.relativePath(project, matches.get(0).file()), FactsFiles.isUnsaved(matches.get(0).file()));

        return Envelope.ok(provenance, payload).toJson();
    }

    private List<Method> targetMethods(PhpClass phpClass, @Nullable String method) {
        if (isSet(method)) {
            Method found = phpClass.findMethodByName(onMethodName(method.trim()));

            return found == null ? List.of() : List.of(found);
        }
        List<Method> methods = new ArrayList<>();
        for (Method candidate : phpClass.getOwnMethods()) {
            String name = candidate.getName();
            if (name.length() > 2 && name.startsWith("on") && Character.isUpperCase(name.charAt(2)) && candidate.getAccess().isPublic()) {
                methods.add(candidate);
            }
        }

        return methods;
    }

    private static String onMethodName(String method) {
        if (method.length() > 2 && method.startsWith("on") && Character.isUpperCase(method.charAt(2))) {
            return method;
        }

        return "on" + method.substring(0, 1).toUpperCase(Locale.ROOT) + method.substring(1).toLowerCase(Locale.ROOT);
    }

    /** The {@code schema} (response) or {@code params} (request) file names a method declares. */
    private static List<String> declaredSchemaFiles(Method method, String kind) {
        boolean request = KIND_REQUEST.equals(kind);
        String argumentName = request ? "params" : "schema";
        // #[JsonSchema] constructor order is (schema, key, params, target): params sits at index 2.
        int argumentIndex = request ? 2 : 0;
        List<String> fileNames = new ArrayList<>();
        for (PhpAttribute attribute : method.getAttributes()) {
            if (!JSON_SCHEMA.equals(attributeShortName(attribute))) {
                continue;
            }
            PsiElement parameter = attribute.getParameter(argumentName, argumentIndex);
            if (parameter instanceof StringLiteralExpression literal && !literal.getContents().isBlank()) {
                fileNames.add(literal.getContents());
            }
        }

        return fileNames;
    }

    @Nullable
    private static String attributeShortName(PhpAttribute attribute) {
        String fqn = attribute.getFQN();
        if (fqn != null && !fqn.isBlank()) {
            int index = fqn.lastIndexOf('\\');

            return index >= 0 ? fqn.substring(index + 1) : fqn;
        }
        ClassReference classReference = attribute.getClassReference();

        return classReference == null ? null : classReference.getName();
    }

    /** Every configured schema directory that holds a file of this name. */
    List<SchemaMatch> byFileName(String fileName, String kind, String source) {
        // The answer carries the file contents, so a name may never leave the schema directories.
        if (fileName.contains("..") || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0) {
            return List.of();
        }
        List<SchemaMatch> matches = new ArrayList<>();
        for (String directory : schemaDirectories(kind)) {
            String relativePath = directory.endsWith("/") ? directory + fileName : directory + "/" + fileName;
            VirtualFile file = FactsFiles.find(project, relativePath);
            if (file != null && !file.isDirectory()) {
                matches.add(parse(file, source, kind));
            }
        }

        return matches;
    }

    private Collection<String> schemaDirectories(String kind) {
        Settings settings = Settings.getInstance(project);

        return KIND_REQUEST.equals(kind) ? settings.jsonValidatePath : settings.jsonSchemaPath;
    }

    @Nullable
    private SchemaMatch conventionMatch(PhpClass phpClass) {
        Path path = BodyJsonSchemaPath.fromClass(project, phpClass);
        if (path == null) {
            return null;
        }
        VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(path);

        return file == null ? null : parse(file, SOURCE_CONVENTION, KIND_RESPONSE);
    }

    private SchemaMatch parse(VirtualFile file, String source, String kind) {
        String relativePath = FactsFiles.relativePath(project, file);
        String text = FactsFiles.contentOf(file);
        if (text == null) {
            return new SchemaMatch(file, relativePath, source, kind, null, "Cannot read " + relativePath);
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                return new SchemaMatch(file, relativePath, source, kind, null, "Not a JSON object: " + relativePath);
            }

            return new SchemaMatch(file, relativePath, source, kind, parsed.getAsJsonObject(), null);
        } catch (JsonParseException exception) {
            return new SchemaMatch(file, relativePath, source, kind, null, exception.getMessage());
        }
    }

    private static JsonObject matchJson(SchemaMatch match) {
        JsonObject json = new JsonObject();
        json.addProperty("path", match.path());
        if (match.raw() == null) {
            json.addProperty("error", match.error());

            return json;
        }
        json.addProperty("source", match.source());
        json.addProperty("kind", match.kind());
        json.add("properties", stringArray(propertyNames(match.raw())));
        json.add("required", stringArray(requiredNames(match.raw())));
        json.add("raw", match.raw());

        return json;
    }

    static List<String> propertyNames(JsonObject raw) {
        JsonElement properties = raw.get("properties");
        if (properties == null || !properties.isJsonObject()) {
            return List.of();
        }

        return List.copyOf(properties.getAsJsonObject().keySet());
    }

    private static List<String> requiredNames(JsonObject raw) {
        JsonElement required = raw.get("required");
        if (required == null || !required.isJsonArray()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (JsonElement element : required.getAsJsonArray()) {
            if (element.isJsonPrimitive()) {
                names.add(element.getAsString());
            }
        }

        return names;
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);

        return array;
    }

    @Nullable
    private static String normalizeKind(@Nullable String kind) {
        if (!isSet(kind)) {
            return KIND_RESPONSE;
        }
        String normalized = kind.trim().toLowerCase(Locale.ROOT);

        return KIND_RESPONSE.equals(normalized) || KIND_REQUEST.equals(normalized) ? normalized : null;
    }

    private static boolean isSet(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    /** One schema file found for a resource; {@code raw} is {@code null} when it cannot be parsed. */
    record SchemaMatch(
        VirtualFile file,
        String path,
        String source,
        String kind,
        @Nullable JsonObject raw,
        @Nullable String error
    ) {
    }
}
