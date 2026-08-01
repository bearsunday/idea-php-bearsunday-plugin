package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import idea.bear.sunday.alps.AlpsDescriptor;
import idea.bear.sunday.alps.AlpsLink;
import idea.bear.sunday.alps.AlpsLinkResolver;
import idea.bear.sunday.alps.AlpsNormalizer;
import idea.bear.sunday.alps.AlpsParseException;
import idea.bear.sunday.alps.AlpsProfile;
import idea.bear.sunday.alps.AlpsProfileDetector;
import idea.bear.sunday.alps.ResolvedLink;
import idea.bear.sunday.relation.ResourceRelation;
import idea.bear.sunday.relation.ResourceRelationIndex;
import idea.bear.sunday.util.UriUtil;
import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Answers ALPS questions as JSON envelopes for the MCP tools. Every answer is read-only and
 * carries the profile it came from.
 */
@Service(Service.Level.PROJECT)
public final class AlpsFactsService {

    private static final String NO_PROFILE = "No ALPS profile found in this project.";

    private final Project project;

    public AlpsFactsService(Project project) {
        this.project = project;
    }

    public static AlpsFactsService getInstance(Project project) {
        return project.getService(AlpsFactsService.class);
    }

    public String profileRead(@Nullable String profilePath) {
        return ReadAction.compute(() -> readProfile(profilePath));
    }

    public String descriptorLookup(@Nullable String id, @Nullable String href, @Nullable String profilePath) {
        return ReadAction.compute(() -> lookupDescriptor(id, href, profilePath));
    }

    public String transitionLookup(@Nullable String from, @Nullable String rel, @Nullable String rt, @Nullable String profilePath) {
        return ReadAction.compute(() -> lookupTransitions(from, rel, rt, profilePath));
    }

    public String linksResolve(@Nullable String profilePath, @Nullable String rel) {
        return ReadAction.compute(() -> resolveLinks(profilePath, rel));
    }

    private String readProfile(@Nullable String profilePath) {
        List<VirtualFile> files = targetFiles(profilePath);
        if (files.isEmpty()) {
            return Envelope.notFound(missingProfileDetail(profilePath)).toJson();
        }
        if (files.size() > 1) {
            return Envelope.ambiguous(paths(files)).toJson();
        }
        VirtualFile file = files.get(0);
        AlpsProfile profile;
        try {
            profile = parse(file);
        } catch (AlpsParseException exception) {
            return Envelope.parseError(exception.getMessage()).toJson();
        }
        JsonObject payload = new JsonObject();
        payload.add("profile", profileJson(profile));

        return Envelope.ok(provenanceOf(file), payload).toJson();
    }

    private String lookupDescriptor(@Nullable String id, @Nullable String href, @Nullable String profilePath) {
        String wanted = stripHash(isSet(id) ? id : href);
        if (wanted == null) {
            return Envelope.notFound("Either id or href is required.").toJson();
        }
        List<VirtualFile> files = targetFiles(profilePath);
        if (files.isEmpty()) {
            return Envelope.notFound(missingProfileDetail(profilePath)).toJson();
        }
        String parseError = null;
        for (VirtualFile file : files) {
            AlpsProfile profile;
            try {
                profile = parse(file);
            } catch (AlpsParseException exception) {
                parseError = parseError == null ? exception.getMessage() : parseError;
                continue;
            }
            AlpsDescriptor descriptor = AlpsLinkResolver.findById(profile.descriptors(), wanted);
            if (descriptor != null) {
                JsonObject payload = new JsonObject();
                payload.add("descriptor", descriptorJson(descriptor));

                return Envelope.ok(provenanceOf(file), payload).toJson();
            }
        }

        return parseError != null
            ? Envelope.parseError(parseError).toJson()
            : Envelope.notFound("Descriptor not found: " + wanted).toJson();
    }

    private String lookupTransitions(@Nullable String from, @Nullable String rel, @Nullable String rt, @Nullable String profilePath) {
        return firstMatching(profilePath, "transitions", (file, profile) -> transitionsJson(profile, from, rel, rt));
    }

    private String resolveLinks(@Nullable String profilePath, @Nullable String rel) {
        return firstMatching(profilePath, "links", (file, profile) -> linksJson(file, profile, rel));
    }

    /**
     * Runs a list-producing query over the target profiles and answers from the first profile
     * that yields a match, so the provenance always names a single source.
     */
    private String firstMatching(@Nullable String profilePath, String key, ProfileQuery query) {
        List<VirtualFile> files = targetFiles(profilePath);
        if (files.isEmpty()) {
            return Envelope.notFound(missingProfileDetail(profilePath)).toJson();
        }
        String parseError = null;
        VirtualFile firstReadable = null;
        for (VirtualFile file : files) {
            AlpsProfile profile;
            try {
                profile = parse(file);
            } catch (AlpsParseException exception) {
                parseError = parseError == null ? exception.getMessage() : parseError;
                continue;
            }
            firstReadable = firstReadable == null ? file : firstReadable;
            JsonArray results = query.run(file, profile);
            if (results.size() > 0) {
                JsonObject payload = new JsonObject();
                payload.add(key, results);

                return Envelope.ok(provenanceOf(file), payload).toJson();
            }
        }
        if (firstReadable == null) {
            return Envelope.parseError(parseError).toJson();
        }
        JsonObject payload = new JsonObject();
        payload.add(key, new JsonArray());

        return Envelope.ok(provenanceOf(firstReadable), payload).toJson();
    }

    private JsonArray transitionsJson(AlpsProfile profile, @Nullable String from, @Nullable String rel, @Nullable String rt) {
        JsonArray transitions = new JsonArray();
        collectTransitions(profile.descriptors(), null, from, rel, rt, transitions);

        return transitions;
    }

    private void collectTransitions(
        List<AlpsDescriptor> descriptors,
        @Nullable String parentId,
        @Nullable String from,
        @Nullable String rel,
        @Nullable String rt,
        JsonArray transitions
    ) {
        for (AlpsDescriptor descriptor : descriptors) {
            if (descriptor.isTransition() && matchesFilters(descriptor, parentId, from, rel, rt)) {
                transitions.add(transitionJson(descriptor, parentId));
            }
            collectTransitions(descriptor.children(), descriptor.id(), from, rel, rt, transitions);
        }
    }

    private static boolean matchesFilters(
        AlpsDescriptor descriptor,
        @Nullable String parentId,
        @Nullable String from,
        @Nullable String rel,
        @Nullable String rt
    ) {
        if (isSet(from) && !from.equals(parentId)) {
            return false;
        }
        if (isSet(rel) && !relMatches(descriptor.rel(), rel)) {
            return false;
        }

        return !isSet(rt) || rtMatches(descriptor.rt(), rt);
    }

    private JsonObject transitionJson(AlpsDescriptor descriptor, @Nullable String parentId) {
        JsonObject json = new JsonObject();
        addIfPresent(json, "from", parentId);
        for (var entry : descriptorJson(descriptor).entrySet()) {
            json.add(entry.getKey(), entry.getValue());
        }
        JsonArray implementations = implementationsJson(descriptor);
        if (implementations.size() > 0) {
            json.add("implementations", implementations);
        }

        return json;
    }

    /**
     * Matches an ALPS transition against BEAR.Resource {@code #[Link]} / {@code #[Embed]}
     * declarations by convention: the target descriptor id names the resource
     * ({@code BlogPosting} -> {@code app://self/blog-posting}).
     */
    private JsonArray implementationsJson(AlpsDescriptor descriptor) {
        JsonArray implementations = new JsonArray();
        String targetId = localId(descriptor.rt());
        if (targetId == null || descriptor.rel() == null) {
            return implementations;
        }
        String resourceName = toKebabCase(targetId);
        try {
            for (String uri : List.of("app://self/" + resourceName, "page://self/" + resourceName)) {
                String resourcePath = UriUtil.toSupportedResourceRelativePath(uri, false);
                if (resourcePath == null) {
                    continue;
                }
                for (ResourceRelation relation : ResourceRelationIndex.findIncoming(resourcePath, project)) {
                    if (relMatches(descriptor.rel(), relation.rel())) {
                        implementations.add(relationJson(relation));
                    }
                }
            }
        } catch (IndexNotReadyException exception) {
            // Profile facts stay available in dumb mode; only the implementation match is dropped.
            return new JsonArray();
        }

        return implementations;
    }

    private static JsonObject relationJson(ResourceRelation relation) {
        JsonObject json = new JsonObject();
        json.addProperty("kind", relation.kind());
        json.addProperty("rel", relation.rel());
        json.addProperty("sourceUri", relation.sourceUri());
        json.addProperty("sourceFqn", relation.sourceFqn());
        json.addProperty("targetUri", relation.targetUri());
        json.addProperty("targetMethod", relation.targetMethod());

        return json;
    }

    private JsonArray linksJson(VirtualFile file, AlpsProfile profile, @Nullable String rel) {
        JsonArray links = new JsonArray();
        for (AlpsLink link : profile.links()) {
            addLink(links, link, file, profile, "profile", rel);
        }
        collectDescriptorLinks(profile.descriptors(), links, file, profile, rel);

        return links;
    }

    private void collectDescriptorLinks(
        List<AlpsDescriptor> descriptors,
        JsonArray links,
        VirtualFile file,
        AlpsProfile profile,
        @Nullable String rel
    ) {
        for (AlpsDescriptor descriptor : descriptors) {
            String owner = descriptor.id() != null ? descriptor.id() : descriptor.href();
            for (AlpsLink link : descriptor.links()) {
                addLink(links, link, file, profile, owner, rel);
            }
            collectDescriptorLinks(descriptor.children(), links, file, profile, rel);
        }
    }

    private void addLink(
        JsonArray links,
        AlpsLink link,
        VirtualFile file,
        AlpsProfile profile,
        @Nullable String owner,
        @Nullable String rel
    ) {
        if (isSet(rel) && !relMatches(link.rel(), rel)) {
            return;
        }
        ResolvedLink resolved = AlpsLinkResolver.resolve(link, file, profile);
        JsonObject json = new JsonObject();
        addIfPresent(json, "rel", resolved.rel());
        addIfPresent(json, "href", resolved.href());
        addIfPresent(json, "resolvedPath", resolved.resolvedPath());
        json.addProperty("exists", resolved.exists());
        json.addProperty("external", resolved.external());
        addIfPresent(json, "owner", owner);
        links.add(json);
    }

    private static JsonObject profileJson(AlpsProfile profile) {
        JsonObject json = new JsonObject();
        addIfPresent(json, "title", profile.title());
        addIfPresent(json, "doc", profile.doc());
        json.add("links", linkArray(profile.links()));
        json.add("descriptors", descriptorArray(profile.descriptors()));

        return json;
    }

    private static JsonObject descriptorJson(AlpsDescriptor descriptor) {
        JsonObject json = new JsonObject();
        addIfPresent(json, "id", descriptor.id());
        addIfPresent(json, "type", descriptor.type());
        addIfPresent(json, "rt", descriptor.rt());
        addIfPresent(json, "href", descriptor.href());
        addIfPresent(json, "rel", descriptor.rel());
        addIfPresent(json, "doc", descriptor.doc());
        addIfPresent(json, "def", descriptor.def());
        addIfPresent(json, "tag", descriptor.tag());
        addIfPresent(json, "title", descriptor.title());
        if (descriptor.textOffset() >= 0) {
            json.addProperty("offset", descriptor.textOffset());
        }
        if (!descriptor.links().isEmpty()) {
            json.add("links", linkArray(descriptor.links()));
        }
        if (!descriptor.children().isEmpty()) {
            json.add("descriptors", descriptorArray(descriptor.children()));
        }

        return json;
    }

    private static JsonArray descriptorArray(List<AlpsDescriptor> descriptors) {
        JsonArray array = new JsonArray();
        for (AlpsDescriptor descriptor : descriptors) {
            array.add(descriptorJson(descriptor));
        }

        return array;
    }

    private static JsonArray linkArray(List<AlpsLink> links) {
        JsonArray array = new JsonArray();
        for (AlpsLink link : links) {
            JsonObject json = new JsonObject();
            addIfPresent(json, "rel", link.rel());
            addIfPresent(json, "href", link.href());
            array.add(json);
        }

        return array;
    }

    private List<VirtualFile> targetFiles(@Nullable String profilePath) {
        if (!isSet(profilePath)) {
            return AlpsProfileDetector.getInstance(project).findProfiles();
        }
        VirtualFile file = resolveProfileFile(profilePath.trim());

        return file == null ? List.of() : List.of(file);
    }

    @Nullable
    private VirtualFile resolveProfileFile(String profilePath) {
        try {
            Path path = Path.of(profilePath);
            if (path.isAbsolute()) {
                return LocalFileSystem.getInstance().findFileByNioFile(path);
            }
        } catch (InvalidPathException exception) {
            return null;
        }
        VirtualFile root = ProjectUtil.guessProjectDir(project);

        return root == null ? null : root.findFileByRelativePath(profilePath);
    }

    private AlpsProfile parse(VirtualFile file) {
        String text = AlpsProfileDetector.getInstance(project).contentOf(file);

        return file.getName().toLowerCase(Locale.ROOT).endsWith(".xml")
            ? AlpsNormalizer.fromXml(text, file.getPath())
            : AlpsNormalizer.fromJson(text, file.getPath());
    }

    private Provenance provenanceOf(VirtualFile file) {
        return Provenance.ofFile(file.getPath(), AlpsProfileDetector.getInstance(project).isUnsaved(file));
    }

    private static String missingProfileDetail(@Nullable String profilePath) {
        return isSet(profilePath) ? "ALPS profile not found: " + profilePath : NO_PROFILE;
    }

    private static List<String> paths(List<VirtualFile> files) {
        List<String> paths = new ArrayList<>();
        for (VirtualFile file : files) {
            paths.add(file.getPath());
        }

        return paths;
    }

    /**
     * An ALPS {@code rel} is either an IANA registered name or a URI, while a BEAR.Resource
     * relation always uses a bare token, so a URI matches on its last segment.
     */
    private static boolean relMatches(@Nullable String alpsRel, String bareRel) {
        if (alpsRel == null) {
            return false;
        }

        return alpsRel.equals(bareRel) || lastSegment(alpsRel).equals(bareRel);
    }

    private static boolean rtMatches(@Nullable String actual, String wanted) {
        String actualId = stripHash(actual);

        return actualId != null && actualId.equals(stripHash(wanted));
    }

    private static String lastSegment(String value) {
        int index = Math.max(value.lastIndexOf('/'), value.lastIndexOf('#'));

        return index < 0 ? value : value.substring(index + 1);
    }

    /** Returns the descriptor id a {@code rt} points at, or {@code null} when it is external. */
    @Nullable
    private static String localId(@Nullable String rt) {
        if (!isSet(rt)) {
            return null;
        }
        if (rt.startsWith("#")) {
            return rt.substring(1);
        }

        return rt.contains("/") ? null : rt;
    }

    @Nullable
    private static String stripHash(@Nullable String value) {
        if (!isSet(value)) {
            return null;
        }
        String trimmed = value.trim();

        return trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
    }

    private static String toKebabCase(String id) {
        StringBuilder kebab = new StringBuilder();
        for (int i = 0; i < id.length(); i++) {
            char character = id.charAt(i);
            if (Character.isUpperCase(character)) {
                if (i > 0) {
                    kebab.append('-');
                }
                kebab.append(Character.toLowerCase(character));
            } else {
                kebab.append(character);
            }
        }

        return kebab.toString();
    }

    private static void addIfPresent(JsonObject json, String key, @Nullable String value) {
        if (value != null) {
            json.addProperty(key, value);
        }
    }

    private static boolean isSet(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    @FunctionalInterface
    private interface ProfileQuery {
        JsonArray run(VirtualFile file, AlpsProfile profile);
    }
}
