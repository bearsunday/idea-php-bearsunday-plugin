package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import idea.bear.sunday.alps.AlpsDescriptor;
import idea.bear.sunday.alps.AlpsLinkResolver;
import idea.bear.sunday.alps.AlpsParseException;
import idea.bear.sunday.alps.AlpsProfile;
import idea.bear.sunday.alps.AlpsProfileDetector;
import idea.bear.sunday.mcp.facts.SchemaFactsService.SchemaMatch;
import idea.bear.sunday.util.UriUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Puts the field names a resource declares in its JSON Schema next to the ones its ALPS profile
 * describes. The comparison is presence-only: it reports which side names a field, never whether
 * the two agree on its meaning.
 */
@Service(Service.Level.PROJECT)
public final class ContractCompareService {

    private static final String DEFAULT_METHOD = "get";

    private final Project project;

    public ContractCompareService(Project project) {
        this.project = project;
    }

    public static ContractCompareService getInstance(Project project) {
        return project.getService(ContractCompareService.class);
    }

    public String compare(@Nullable String uri, @Nullable String method) {
        return ReadAction.compute(() -> compareContract(uri, method));
    }

    private String compareContract(@Nullable String uri, @Nullable String method) {
        if (uri == null || uri.isBlank()) {
            return Envelope.notFound("uri is required.").toJson();
        }
        String normalizedUri = UriUtil.normalizeSupportedResourceUri(uri.trim(), false);
        if (normalizedUri == null) {
            return Envelope.notFound("Unsupported resource URI: " + uri).toJson();
        }
        String resolvedMethod = method == null || method.isBlank() ? DEFAULT_METHOD : method.trim();

        SchemaMatch schemaMatch = firstReadableSchema(normalizedUri, resolvedMethod);
        AlpsFields alpsFields = alpsFields(normalizedUri);
        List<String> schemaSide = schemaMatch == null ? null : SchemaFactsService.propertyNames(schemaMatch.raw());
        List<String> alpsSide = alpsFields == null ? null : alpsFields.fields();

        JsonObject payload = new JsonObject();
        payload.addProperty("kind", "presence-only");
        payload.addProperty("uri", normalizedUri);
        payload.addProperty("method", resolvedMethod);
        payload.add("schema", schemaJson(schemaMatch, schemaSide));
        payload.add("alps", alpsJson(alpsFields));
        payload.add("body", bodyJson());
        if (schemaSide != null && alpsSide != null) {
            payload.add("onlyInSchema", stringArray(missing(schemaSide, alpsSide)));
            payload.add("onlyInAlps", stringArray(missing(alpsSide, schemaSide)));
        }

        return Envelope.ok(Provenance.derived(normalizedUri), payload).toJson();
    }

    @Nullable
    private SchemaMatch firstReadableSchema(String normalizedUri, String method) {
        List<SchemaMatch> matches = SchemaFactsService.getInstance(project)
            .matchesForResource(normalizedUri, method, SchemaFactsService.KIND_RESPONSE);
        for (SchemaMatch match : matches) {
            if (match.raw() != null) {
                return match;
            }
        }

        return null;
    }

    /**
     * Finds the semantic descriptor named after the resource ({@code app://self/blog-posting} ->
     * {@code BlogPosting}) and takes its non-transition children as the described fields.
     */
    @Nullable
    private AlpsFields alpsFields(String normalizedUri) {
        String descriptorId = descriptorId(normalizedUri);
        if (descriptorId == null) {
            return null;
        }
        for (VirtualFile file : AlpsProfileDetector.getInstance(project).findProfiles()) {
            AlpsProfile profile;
            try {
                profile = AlpsProfileDetector.getInstance(project).parse(file);
            } catch (AlpsParseException exception) {
                continue;
            }
            AlpsDescriptor descriptor = AlpsLinkResolver.findById(profile.descriptors(), descriptorId);
            if (descriptor == null || descriptor.isTransition()) {
                continue;
            }

            return new AlpsFields(descriptorId, FactsFiles.relativePath(project, file), fieldsOf(descriptor));
        }

        return null;
    }

    private static List<String> fieldsOf(AlpsDescriptor descriptor) {
        List<String> fields = new ArrayList<>();
        for (AlpsDescriptor child : descriptor.children()) {
            if (child.isTransition()) {
                continue;
            }
            String name = child.id() != null ? child.id() : stripHash(child.href());
            if (name != null && !name.isBlank()) {
                fields.add(name);
            }
        }

        return fields;
    }

    private static JsonObject schemaJson(@Nullable SchemaMatch match, @Nullable List<String> fields) {
        JsonObject json = new JsonObject();
        if (match == null || fields == null) {
            json.addProperty("available", false);

            return json;
        }
        json.addProperty("path", match.path());
        json.add("fields", stringArray(fields));

        return json;
    }

    private static JsonObject alpsJson(@Nullable AlpsFields alpsFields) {
        JsonObject json = new JsonObject();
        if (alpsFields == null) {
            json.addProperty("available", false);

            return json;
        }
        json.addProperty("descriptorId", alpsFields.descriptorId());
        json.addProperty("profilePath", alpsFields.profilePath());
        json.add("fields", stringArray(alpsFields.fields()));

        return json;
    }

    private static JsonObject bodyJson() {
        JsonObject json = new JsonObject();
        json.addProperty("available", false);
        json.addProperty("reason", "M3");

        return json;
    }

    private static List<String> missing(List<String> fields, List<String> other) {
        Set<String> known = new LinkedHashSet<>(other);
        List<String> only = new ArrayList<>();
        for (String field : fields) {
            if (!known.contains(field)) {
                only.add(field);
            }
        }

        return only;
    }

    /** {@code app://self/blog-posting} names the {@code BlogPosting} descriptor. */
    @Nullable
    private static String descriptorId(String normalizedUri) {
        int slash = normalizedUri.lastIndexOf('/');
        String segment = slash < 0 ? normalizedUri : normalizedUri.substring(slash + 1);
        if (segment.isBlank()) {
            return null;
        }
        String id = Names.pascal(segment);

        return id.isEmpty() ? null : id;
    }

    @Nullable
    private static String stripHash(@Nullable String href) {
        if (href == null) {
            return null;
        }

        return href.startsWith("#") ? href.substring(1) : href;
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);

        return array;
    }

    private record AlpsFields(String descriptorId, String profilePath, List<String> fields) {
    }
}
