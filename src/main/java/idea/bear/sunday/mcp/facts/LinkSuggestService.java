package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import idea.bear.sunday.alps.AlpsDescriptor;
import idea.bear.sunday.alps.AlpsLink;
import idea.bear.sunday.alps.AlpsLinkResolver;
import idea.bear.sunday.alps.AlpsParseException;
import idea.bear.sunday.alps.AlpsProfile;
import idea.bear.sunday.alps.AlpsProfileDetector;
import idea.bear.sunday.mcp.facts.SchemaFactsService.SchemaMatch;
import idea.bear.sunday.util.UriUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Suggests ALPS links that the project's conventions imply but the profile does not declare: a
 * descriptor whose JSON Schema or OpenAPI operation exists on disk without being linked to.
 * Suggestions are inferences, never facts about the profile as written.
 */
@Service(Service.Level.PROJECT)
public final class LinkSuggestService {

    private static final String NO_PROFILE = "No ALPS profile found in this project.";
    private static final String REL_DESCRIBED_BY = "describedby";
    private static final String REL_RELATED = "related";

    private final Project project;

    public LinkSuggestService(Project project) {
        this.project = project;
    }

    public static LinkSuggestService getInstance(Project project) {
        return project.getService(LinkSuggestService.class);
    }

    public String suggest(@Nullable String descriptorId, @Nullable String resourceUri) {
        // Non-blocking so a pending write action is not made to wait out the read; cancelled and
        // retried instead. See DiBindingLookupService#lookup.
        return ReadAction.nonBlocking(() -> suggestLinks(descriptorId, resourceUri))
            .executeSynchronously();
    }

    private String suggestLinks(@Nullable String descriptorId, @Nullable String resourceUri) {
        String wantedId = wantedId(descriptorId, resourceUri);
        AlpsProfileDetector detector = AlpsProfileDetector.getInstance(project);
        List<VirtualFile> profiles = detector.findProfiles();
        if (profiles.isEmpty()) {
            return Envelope.notFound(NO_PROFILE).toJson();
        }
        String parseError = null;
        VirtualFile firstReadable = null;
        for (VirtualFile file : profiles) {
            AlpsProfile profile;
            try {
                profile = detector.parse(file);
            } catch (AlpsParseException exception) {
                parseError = parseError == null ? exception.getMessage() : parseError;
                continue;
            }
            firstReadable = firstReadable == null ? file : firstReadable;
            List<AlpsDescriptor> targets = targets(profile, wantedId);
            if (targets.isEmpty()) {
                continue;
            }
            ApiDocFactsService apiDoc = ApiDocFactsService.getInstance(project);
            Set<String> openApiPaths = apiDoc.pathKeys();
            VirtualFile openApiFile = apiDoc.openApiFile();
            JsonArray suggestions = new JsonArray();
            for (AlpsDescriptor target : targets) {
                suggestions.addAll(suggestionsFor(target, profile, file, openApiPaths, openApiFile));
            }

            return Envelope.ok(provenanceOf(file), payload(suggestions)).toJson();
        }
        if (firstReadable == null) {
            return Envelope.parseError(parseError).toJson();
        }
        if (wantedId != null) {
            return Envelope.notFound("Descriptor not found: " + wantedId).toJson();
        }

        return Envelope.ok(provenanceOf(firstReadable), payload(new JsonArray())).toJson();
    }

    private JsonArray suggestionsFor(AlpsDescriptor descriptor, AlpsProfile profile, VirtualFile profileFile,
                                     Set<String> openApiPaths, @Nullable VirtualFile openApiFile) {
        JsonArray suggestions = new JsonArray();
        String id = descriptor.id();
        if (id == null) {
            return suggestions;
        }
        Set<String> declared = declaredLinks(descriptor, profile, profileFile);
        String name = Names.kebab(id);
        addSchemaSuggestion(suggestions, declared, id, name);
        addOpenApiSuggestion(suggestions, declared, id, name, openApiPaths, openApiFile);

        return suggestions;
    }

    private void addSchemaSuggestion(JsonArray suggestions, Set<String> declared, String id, String name) {
        List<SchemaMatch> matches = SchemaFactsService.getInstance(project)
            .byFileName(name + ".json", SchemaFactsService.KIND_RESPONSE, "convention");
        if (matches.isEmpty()) {
            return;
        }
        SchemaMatch match = matches.get(0);
        if (declared.contains(key(REL_DESCRIBED_BY, match.file().getPath()))) {
            return;
        }
        suggestions.add(suggestion(
            REL_DESCRIBED_BY,
            match.path(),
            "The response schema of " + id + " is at " + match.path() + ", but no link points at it.",
            "high"
        ));
    }

    private void addOpenApiSuggestion(JsonArray suggestions, Set<String> declared, String id, String name,
                                      Set<String> openApiPaths, @Nullable VirtualFile file) {
        String path = "/" + name;
        if (!openApiPaths.contains(path)) {
            return;
        }
        if (file == null || declared.contains(key(REL_RELATED, file.getPath()))) {
            return;
        }
        String href = FactsFiles.relativePath(project, file) + "#" + jsonPointer(path);
        suggestions.add(suggestion(
            REL_RELATED,
            href,
            "The generated OpenAPI document describes " + path + " for " + id + ".",
            "medium"
        ));
    }

    private static JsonObject suggestion(String rel, String href, String reason, String confidence) {
        JsonObject json = new JsonObject();
        json.addProperty("rel", rel);
        json.addProperty("href", href);
        json.addProperty("reason", reason);
        json.addProperty("confidence", confidence);
        json.addProperty("exists", true);

        return json;
    }

    /** Links already declared, keyed by rel and by the file they resolve to. */
    private static Set<String> declaredLinks(AlpsDescriptor descriptor, AlpsProfile profile, VirtualFile profileFile) {
        Set<String> declared = new HashSet<>();
        List<AlpsLink> links = new ArrayList<>(descriptor.links());
        links.addAll(profile.links());
        for (AlpsLink link : links) {
            if (link.rel() == null) {
                continue;
            }
            String resolvedPath = AlpsLinkResolver.resolve(link, profileFile, profile).resolvedPath();
            if (resolvedPath == null) {
                continue;
            }
            int fragment = resolvedPath.indexOf('#');
            declared.add(key(link.rel(), fragment < 0 ? resolvedPath : resolvedPath.substring(0, fragment)));
        }

        return declared;
    }

    /** An ALPS rel may be a URI, while the suggested rels are bare IANA names. */
    private static String key(String rel, String path) {
        int index = Math.max(rel.lastIndexOf('/'), rel.lastIndexOf('#'));

        return (index < 0 ? rel : rel.substring(index + 1)) + "|" + path;
    }

    private static List<AlpsDescriptor> targets(AlpsProfile profile, @Nullable String wantedId) {
        if (wantedId != null) {
            AlpsDescriptor descriptor = AlpsLinkResolver.findById(profile.descriptors(), wantedId);

            return descriptor == null || descriptor.isTransition() ? List.of() : List.of(descriptor);
        }
        List<AlpsDescriptor> targets = new ArrayList<>();
        for (AlpsDescriptor descriptor : profile.descriptors()) {
            if (descriptor.id() != null && !descriptor.isTransition()) {
                targets.add(descriptor);
            }
        }

        return targets;
    }

    @Nullable
    private static String wantedId(@Nullable String descriptorId, @Nullable String resourceUri) {
        if (descriptorId != null && !descriptorId.isBlank()) {
            String trimmed = descriptorId.trim();

            return trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
        }
        if (resourceUri == null || resourceUri.isBlank()) {
            return null;
        }
        String normalizedUri = UriUtil.normalizeSupportedResourceUri(resourceUri.trim(), false);
        if (normalizedUri == null) {
            return null;
        }
        int slash = normalizedUri.lastIndexOf('/');
        String segment = slash < 0 ? normalizedUri : normalizedUri.substring(slash + 1);
        String id = Names.pascal(segment);

        return id.isEmpty() ? null : id;
    }

    private static JsonObject payload(JsonArray suggestions) {
        JsonObject payload = new JsonObject();
        payload.addProperty("kind", "inference");
        payload.add("suggestions", suggestions);

        return payload;
    }

    private Provenance provenanceOf(VirtualFile file) {
        return Provenance.ofFile(FactsFiles.relativePath(project, file), FactsFiles.isUnsaved(file));
    }

    /**
     * RFC 6901: inside a pointer segment "~" is written "~0" and "/" is written "~1", in that
     * order. The descriptor id reaches here as written, so a segment carrying either character
     * would otherwise be handed over as a pointer that names something else.
     */
    private static String jsonPointer(String path) {
        return "/paths/" + path.replace("~", "~0").replace("/", "~1");
    }
}
