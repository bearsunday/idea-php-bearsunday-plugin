package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import idea.bear.sunday.body.BodyType;
import idea.bear.sunday.body.BodyTypeCollector;
import idea.bear.sunday.body.BodyTypeDeclaration;
import idea.bear.sunday.body.BodyTypes;
import idea.bear.sunday.body.ShapeField;
import idea.bear.sunday.resource.ResourceClassResolver;
import idea.bear.sunday.util.UriUtil;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Describes the shape of the body a resource method assigns to {@code $this->body}, as inferred
 * from the resource class itself. The shape is what the code builds, not what the resource
 * promises: a method whose body cannot be read statically simply has no shape here.
 */
@Service(Service.Level.PROJECT)
public final class BodyShapeFactsService {

    private static final String DEFAULT_METHOD = "get";

    private final Project project;

    public BodyShapeFactsService(Project project) {
        this.project = project;
    }

    public static BodyShapeFactsService getInstance(Project project) {
        return project.getService(BodyShapeFactsService.class);
    }

    public String shape(@Nullable String uri, @Nullable String method) {
        // Non-blocking so a pending write action is not made to wait out the read; cancelled and
        // retried instead. See DiBindingLookupService#lookup.
        return ReadAction.nonBlocking(() -> describeShape(uri, method))
            .executeSynchronously();
    }

    private String describeShape(@Nullable String uri, @Nullable String method) {
        if (uri == null || uri.isBlank()) {
            return Envelope.notFound("uri is required.").toJson();
        }
        String normalizedUri = UriUtil.normalizeSupportedResourceUri(uri.trim(), false);
        if (normalizedUri == null) {
            return Envelope.notFound("Unsupported resource URI: " + uri).toJson();
        }
        String resourceMethod = resourceMethodName(method);
        BodyLookup lookup = lookUp(project, normalizedUri, resourceMethod);
        BodyType bodyType = lookup.bodyType();
        if (bodyType == null) {
            return Envelope.notFound(lookup.reason()).toJson();
        }

        JsonObject bodyShape = new JsonObject();
        bodyShape.addProperty("uri", normalizedUri);
        bodyShape.addProperty("method", resourceMethod);
        bodyShape.addProperty("rendered", bodyType.render());
        bodyShape.addProperty("formatted", BodyTypes.renderFormatted(bodyType));
        addFields(bodyShape, bodyType);

        JsonObject payload = new JsonObject();
        payload.add("bodyShape", bodyShape);

        return Envelope.ok(provenanceOf(lookup.phpClass(), normalizedUri), payload).toJson();
    }

    /**
     * The body type one resource method declares, or the reason there is none. Shared with
     * {@link ContractCompareService} so both tools read the body the same way.
     */
    static BodyLookup lookUp(Project project, String normalizedUri, @Nullable String method) {
        Optional<PhpClass> resolved = ResourceClassResolver.resolveCached(project, normalizedUri);
        if (resolved.isEmpty()) {
            return new BodyLookup(null, null, "Resource class not found for " + normalizedUri);
        }
        PhpClass phpClass = resolved.get();
        String resourceMethod = resourceMethodName(method);
        Optional<BodyTypeDeclaration> declaration = new BodyTypeCollector().collect(phpClass)
            .flatMap(collection -> collection.declarationForResourceMethod(resourceMethod));
        if (declaration.isEmpty()) {
            return new BodyLookup(
                phpClass,
                null,
                "No body declaration for method " + resourceMethod + " in " + phpClass.getFQN()
            );
        }

        return new BodyLookup(phpClass, declaration.get().bodyType(), null);
    }

    /** The keys a body names, taking the union of the branches when the body is a union. */
    static List<String> fieldNames(BodyType bodyType) {
        Set<String> names = new LinkedHashSet<>();
        addFieldNames(bodyType, names);

        return List.copyOf(names);
    }

    /** Both {@code get} and {@code onGet} name the same resource method. */
    static String resourceMethodName(@Nullable String method) {
        if (method == null || method.isBlank()) {
            return DEFAULT_METHOD;
        }
        String trimmed = method.trim();
        if (trimmed.length() > 2 && trimmed.startsWith("on") && Character.isUpperCase(trimmed.charAt(2))) {
            return trimmed.substring(2).toLowerCase(Locale.ROOT);
        }

        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static void addFieldNames(BodyType bodyType, Set<String> names) {
        List<ShapeField> fields = BodyTypes.shapeFields(bodyType);
        if (fields != null) {
            fields.forEach(field -> names.add(field.key()));

            return;
        }
        List<BodyType> branches = BodyTypes.unionTypes(bodyType);
        if (branches != null) {
            branches.forEach(branch -> addFieldNames(branch, names));
        }
    }

    /**
     * A shape answers with its fields, a union with one entry per branch. Anything else (a list,
     * a plain array) names no fields, and neither key is reported.
     */
    private static void addFields(JsonObject bodyShape, BodyType bodyType) {
        List<ShapeField> fields = BodyTypes.shapeFields(bodyType);
        if (fields != null) {
            bodyShape.add("fields", fieldsJson(fields));

            return;
        }
        List<BodyType> branches = BodyTypes.unionTypes(bodyType);
        if (branches == null) {
            return;
        }

        JsonArray branchesJson = new JsonArray();
        for (BodyType branch : branches) {
            JsonObject json = new JsonObject();
            json.addProperty("rendered", branch.render());
            List<ShapeField> branchFields = BodyTypes.shapeFields(branch);
            if (branchFields != null) {
                json.add("fields", fieldsJson(branchFields));
            }
            branchesJson.add(json);
        }
        bodyShape.add("branches", branchesJson);
    }

    private static JsonArray fieldsJson(List<ShapeField> fields) {
        JsonArray json = new JsonArray();
        for (ShapeField field : fields) {
            JsonObject fieldJson = new JsonObject();
            fieldJson.addProperty("key", field.key());
            fieldJson.addProperty("type", field.type().render());
            json.add(fieldJson);
        }

        return json;
    }

    private Provenance provenanceOf(@Nullable PhpClass phpClass, String normalizedUri) {
        PsiFile psiFile = phpClass == null ? null : phpClass.getContainingFile();
        VirtualFile file = psiFile == null ? null : psiFile.getVirtualFile();

        return file == null
            ? Provenance.derived(normalizedUri)
            : Provenance.ofPsi(FactsFiles.relativePath(project, file), FactsFiles.isUnsaved(file));
    }

    /** Either a body type or the reason the resource does not have one. */
    record BodyLookup(@Nullable PhpClass phpClass, @Nullable BodyType bodyType, @Nullable String reason) {
    }
}
