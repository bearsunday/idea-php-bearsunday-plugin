package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Answers questions about the generated OpenAPI document of a BEAR.Sunday application. The
 * document is produced by {@code bear/api-doc} into the {@code docDir} named by {@code apidoc.xml}.
 */
@Service(Service.Level.PROJECT)
public final class ApiDocFactsService {

    private static final String OPENAPI_FILE = "openapi.json";
    private static final String DEFAULT_DOC_DIR = "docs";
    private static final String APIDOC_FILE = "apidoc.xml";
    private static final Set<String> HTTP_METHODS =
        Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    private final Project project;

    public ApiDocFactsService(Project project) {
        this.project = project;
    }

    public static ApiDocFactsService getInstance(Project project) {
        return project.getService(ApiDocFactsService.class);
    }

    public String operationLookup(@Nullable String path, @Nullable String method, @Nullable String operationId) {
        // Non-blocking so a pending write action is not made to wait out the read; cancelled and
        // retried instead. See DiBindingLookupService#lookup.
        return ReadAction.nonBlocking(() -> lookupOperations(path, method, operationId))
            .executeSynchronously();
    }

    /** The OpenAPI document of the project, or {@code null} when it has not been generated. */
    @Nullable
    VirtualFile openApiFile() {
        for (String candidate : openApiCandidates()) {
            VirtualFile file = FactsFiles.find(project, candidate);
            if (file != null && !file.isDirectory()) {
                return file;
            }
        }

        return null;
    }

    /** Path templates the OpenAPI document declares; empty when it is missing or unreadable. */
    Set<String> pathKeys() {
        VirtualFile file = openApiFile();
        String text = file == null ? null : FactsFiles.contentOf(file);
        if (text == null) {
            return Set.of();
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            JsonElement paths = parsed.isJsonObject() ? parsed.getAsJsonObject().get("paths") : null;

            return paths != null && paths.isJsonObject() ? Set.copyOf(paths.getAsJsonObject().keySet()) : Set.of();
        } catch (JsonParseException exception) {
            return Set.of();
        }
    }

    private String lookupOperations(@Nullable String path, @Nullable String method, @Nullable String operationId) {
        VirtualFile file = openApiFile();
        if (file == null) {
            return Envelope.engineUnavailable(
                "OpenAPI document not found. Looked at: " + String.join(", ", openApiCandidates())
                    + ". Generate it with bear/api-doc."
            ).toJson();
        }
        String relativePath = FactsFiles.relativePath(project, file);
        String text = FactsFiles.contentOf(file);
        if (text == null) {
            return Envelope.engineUnavailable("Cannot read " + relativePath).toJson();
        }
        JsonObject document;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                return Envelope.parseError("Not a JSON object: " + relativePath).toJson();
            }
            document = parsed.getAsJsonObject();
        } catch (JsonParseException exception) {
            return Envelope.parseError(relativePath + ": " + exception.getMessage()).toJson();
        }

        JsonObject payload = new JsonObject();
        payload.add("operations", operationsJson(document, path, method, operationId));

        return Envelope.ok(Provenance.ofFile(relativePath, FactsFiles.isUnsaved(file)), payload).toJson();
    }

    private static JsonArray operationsJson(
        JsonObject document,
        @Nullable String path,
        @Nullable String method,
        @Nullable String operationId
    ) {
        boolean filtered = isSet(path) || isSet(method) || isSet(operationId);
        JsonArray operations = new JsonArray();
        JsonElement paths = document.get("paths");
        if (paths == null || !paths.isJsonObject()) {
            return operations;
        }
        for (Map.Entry<String, JsonElement> pathEntry : paths.getAsJsonObject().entrySet()) {
            if (!pathEntry.getValue().isJsonObject()) {
                continue;
            }
            if (isSet(path) && !path.trim().equals(pathEntry.getKey())) {
                continue;
            }
            for (Map.Entry<String, JsonElement> methodEntry : pathEntry.getValue().getAsJsonObject().entrySet()) {
                String methodKey = methodEntry.getKey().toLowerCase(Locale.ROOT);
                if (!HTTP_METHODS.contains(methodKey) || !methodEntry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject operation = methodEntry.getValue().getAsJsonObject();
                if (isSet(method) && !method.trim().equalsIgnoreCase(methodKey)) {
                    continue;
                }
                if (isSet(operationId) && !operationId.trim().equals(stringValue(operation, "operationId"))) {
                    continue;
                }
                operations.add(operationJson(pathEntry.getKey(), methodKey, operation, filtered));
            }
        }

        return operations;
    }

    private static JsonObject operationJson(String path, String method, JsonObject operation, boolean withBody) {
        JsonObject json = new JsonObject();
        json.addProperty("path", path);
        json.addProperty("method", method);
        json.addProperty("jsonPointer", jsonPointer(path, method));
        if (withBody) {
            json.add("operation", operation);
        }

        return json;
    }

    /** RFC 6901 pointer, so {@code /point} becomes {@code ~1point}. */
    private static String jsonPointer(String path, String method) {
        return "#/paths/" + path.replace("~", "~0").replace("/", "~1") + "/" + method;
    }

    private List<String> openApiCandidates() {
        Set<String> candidates = new LinkedHashSet<>();
        String docDir = docDir();
        if (docDir != null) {
            candidates.add(docDir + "/" + OPENAPI_FILE);
        }
        candidates.add(DEFAULT_DOC_DIR + "/" + OPENAPI_FILE);

        return List.copyOf(candidates);
    }

    /**
     * The {@code docDir} element of {@code apidoc.xml}, or {@code null} when it is unreadable or
     * names a directory this project does not hold. The answer carries the document contents and
     * names the paths it looked at, so only a directory inside the project reaches either.
     */
    @Nullable
    private String docDir() {
        VirtualFile apiDoc = FactsFiles.find(project, APIDOC_FILE);
        String text = apiDoc == null ? null : FactsFiles.contentOf(apiDoc);
        if (text == null) {
            return null;
        }
        Document document = parseXml(text);
        if (document == null) {
            return null;
        }
        NodeList elements = document.getElementsByTagName("docDir");
        Node node = elements.getLength() == 0 ? null : elements.item(0);
        String docDir = node == null || node.getTextContent() == null ? null : node.getTextContent().trim();
        if (docDir == null || docDir.isBlank()) {
            return null;
        }
        String normalized = docDir.startsWith("./") ? docDir.substring(2) : docDir;
        normalized = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
        if (normalized.isEmpty()) {
            return null;
        }

        // The directory has to be one this project holds: a docDir that escapes names a place
        // the answer may not read from, and one that does not exist cannot be holding a
        // generated document either, so neither belongs in the list of places looked at.
        return FactsFiles.find(project, normalized) == null ? null : normalized;
    }

    @Nullable
    private static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE hardening: apidoc.xml is untrusted project content, so no DOCTYPE and no
            // external entity resolution of any kind.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();

            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            return null;
        }
    }

    @Nullable
    private static String stringValue(JsonObject json, String key) {
        JsonElement element = json.get(key);

        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static boolean isSet(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}
