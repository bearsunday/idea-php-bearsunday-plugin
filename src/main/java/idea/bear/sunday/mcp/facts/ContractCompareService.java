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
import idea.bear.sunday.mcp.facts.BodyShapeFactsService.BodyLookup;
import idea.bear.sunday.mcp.facts.SchemaFactsService.SchemaMatch;
import idea.bear.sunday.util.UriUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Puts the field names a resource declares in its JSON Schema next to the ones its ALPS profile
 * describes and the ones its code actually assigns to {@code $this->body}. The comparison is
 * presence-only: it reports which side names a field, never whether the sides agree on its
 * meaning. A field is reported as "only in" a side when no other available side names it.
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
        BodyLookup bodyLookup = BodyShapeFactsService.lookUp(project, normalizedUri, resolvedMethod);
        List<String> schemaSide = schemaMatch == null ? null : SchemaFactsService.propertyNames(schemaMatch.raw());
        List<String> alpsSide = alpsFields == null ? null : alpsFields.fields();
        List<String> bodySide = bodyLookup.bodyType() == null
            ? null
            : BodyShapeFactsService.fieldNames(bodyLookup.bodyType());

        JsonObject payload = new JsonObject();
        payload.addProperty("kind", "presence-only");
        payload.addProperty("uri", normalizedUri);
        payload.addProperty("method", resolvedMethod);
        payload.add("schema", schemaJson(schemaMatch, schemaSide));
        payload.add("alps", alpsJson(alpsFields));
        payload.add("body", bodyJson(bodyLookup, bodySide));
        addOnlyIn(payload, schemaSide, alpsSide, bodySide);

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

    private static JsonObject bodyJson(BodyLookup lookup, @Nullable List<String> fields) {
        JsonObject json = new JsonObject();
        if (fields == null) {
            json.addProperty("available", false);
            json.addProperty("reason", lookup.reason());

            return json;
        }
        json.addProperty("available", true);
        json.add("fields", stringArray(fields));

        return json;
    }

    /**
     * Reported only once at least two sides exist: with a single side every field would trivially
     * be "only" there.
     */
    private static void addOnlyIn(
        JsonObject payload,
        @Nullable List<String> schemaSide,
        @Nullable List<String> alpsSide,
        @Nullable List<String> bodySide
    ) {
        long available = Stream.of(schemaSide, alpsSide, bodySide).filter(Objects::nonNull).count();
        if (available < 2) {
            return;
        }
        addOnlyIn(payload, "onlyInSchema", schemaSide, alpsSide, bodySide);
        addOnlyIn(payload, "onlyInAlps", alpsSide, schemaSide, bodySide);
        addOnlyIn(payload, "onlyInBody", bodySide, schemaSide, alpsSide);
    }

    private static void addOnlyIn(
        JsonObject payload,
        String key,
        @Nullable List<String> side,
        @Nullable List<String> otherA,
        @Nullable List<String> otherB
    ) {
        if (side == null) {
            return;
        }
        Set<String> others = new LinkedHashSet<>();
        if (otherA != null) {
            others.addAll(otherA);
        }
        if (otherB != null) {
            others.addAll(otherB);
        }
        List<String> only = new ArrayList<>();
        for (String field : side) {
            if (!others.contains(field)) {
                only.add(field);
            }
        }
        payload.add(key, stringArray(only));
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
