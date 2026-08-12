package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import idea.bear.sunday.alps.AlpsDescriptor;
import idea.bear.sunday.alps.AlpsLink;
import idea.bear.sunday.alps.AlpsLinkResolver;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    // Non-blocking so a pending write action is not made to wait out the read; cancelled and
    // retried instead. See DiBindingLookupService#lookup.

    public String profileRead(@Nullable String profilePath) {
        return ReadAction.nonBlocking(() -> readProfile(profilePath)).executeSynchronously();
    }

    public String descriptorLookup(@Nullable String id, @Nullable String href, @Nullable String profilePath) {
        return ReadAction.nonBlocking(() -> lookupDescriptor(id, href, profilePath)).executeSynchronously();
    }

    public String transitionLookup(@Nullable String from, @Nullable String rel, @Nullable String rt, @Nullable String profilePath) {
        return ReadAction.nonBlocking(() -> lookupTransitions(from, rel, rt, profilePath)).executeSynchronously();
    }

    public String linksResolve(@Nullable String profilePath, @Nullable String rel) {
        return ReadAction.nonBlocking(() -> resolveLinks(profilePath, rel)).executeSynchronously();
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
        // A broken profile only decides the answer when no profile could be read at all, the
        // same order of precedence firstMatching() applies.
        boolean anyReadable = false;
        for (VirtualFile file : files) {
            AlpsProfile profile;
            try {
                profile = parse(file);
            } catch (AlpsParseException exception) {
                parseError = parseError == null ? exception.getMessage() : parseError;
                continue;
            }
            anyReadable = true;
            AlpsDescriptor descriptor = AlpsLinkResolver.findById(profile.descriptors(), wanted);
            if (descriptor != null) {
                JsonObject payload = new JsonObject();
                payload.add("descriptor", descriptorJson(descriptor));

                return Envelope.ok(provenanceOf(file), payload).toJson();
            }
        }

        return anyReadable
            ? Envelope.notFound("Descriptor not found: " + wanted).toJson()
            : Envelope.parseError(parseError).toJson();
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
        Set<String> referenced = new HashSet<>();
        collectReferencedIds(profile, profile.descriptors(), referenced);
        collectTransitions(profile, profile.descriptors(), null, from, rel, rt, referenced, transitions);

        return transitions;
    }

    /**
     * A state names the transitions it offers by reference ({@code {"href": "#goUser"}}) far more
     * often than by defining them inside itself, so a reference is followed to the transition it
     * points at and reported under the state that holds it. The bare top-level definition of a
     * transition reached this way is left out: what the reference adds is the state it is
     * available from, and reporting the definition again would double every such transition.
     */
    private static void collectReferencedIds(AlpsProfile profile, List<AlpsDescriptor> descriptors, Set<String> referenced) {
        for (AlpsDescriptor descriptor : descriptors) {
            AlpsDescriptor target = referencedTransition(profile, descriptor);
            if (target != null) {
                referenced.add(target.id());
            }
            collectReferencedIds(profile, descriptor.children(), referenced);
        }
    }

    /** The transition a child reference points at, or {@code null} when it points elsewhere. */
    @Nullable
    private static AlpsDescriptor referencedTransition(AlpsProfile profile, AlpsDescriptor descriptor) {
        if (!descriptor.isReference()) {
            return null;
        }
        String href = descriptor.href();
        if (href == null || !href.startsWith("#")) {
            return null;
        }
        AlpsDescriptor target = AlpsLinkResolver.findById(profile.descriptors(), href.substring(1));

        return target != null && target.isTransition() && target.id() != null ? target : null;
    }

    private void collectTransitions(
        AlpsProfile profile,
        List<AlpsDescriptor> descriptors,
        @Nullable String parentId,
        @Nullable String from,
        @Nullable String rel,
        @Nullable String rt,
        Set<String> referenced,
        JsonArray transitions
    ) {
        for (AlpsDescriptor descriptor : descriptors) {
            AlpsDescriptor target = referencedTransition(profile, descriptor);
            if (target != null) {
                if (matchesFilters(target, parentId, from, rel, rt)) {
                    transitions.add(transitionJson(target, parentId, true));
                }
                continue;
            }
            boolean supersededByReference = parentId == null && referenced.contains(descriptor.id());
            if (descriptor.isTransition() && !supersededByReference && matchesFilters(descriptor, parentId, from, rel, rt)) {
                transitions.add(transitionJson(descriptor, parentId, false));
            }
            collectTransitions(profile, descriptor.children(), descriptor.id(), from, rel, rt, referenced, transitions);
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

    private JsonObject transitionJson(AlpsDescriptor descriptor, @Nullable String parentId, boolean viaHref) {
        JsonObject json = new JsonObject();
        addIfPresent(json, "from", parentId);
        for (var entry : descriptorJson(descriptor).entrySet()) {
            json.add(entry.getKey(), entry.getValue());
        }
        if (viaHref) {
            json.addProperty("via", "href");
        }
        JsonArray implementations = implementationsJson(descriptor, parentId);
        if (implementations.size() > 0) {
            json.add("implementations", implementations);
        }

        return json;
    }

    /**
     * Matches an ALPS transition against BEAR.Resource {@code #[Link]} / {@code #[Embed]}
     * declarations by two conventions: the target descriptor id names the resource
     * ({@code BlogPosting} -> {@code app://self/blog-posting}), and a relation's {@code rel}
     * spells the transition's id ({@code #[Link(rel: 'goUser')]} -> {@code id="goUser"}). A
     * transition id is an opaque identifier, so it is compared exactly -- no case folding, no
     * hyphenation, and no stripping of the {@code go}/{@code do} prefix, which is a habit rather
     * than a rule. The ALPS {@code rel} attribute is not a join key: it is absent from real
     * profiles, and it does not identify a transition. A nested transition only matches relations
     * declared by its containing state, so another resource pointing at the same target with the
     * same rel is not misattributed.
     */
    private JsonArray implementationsJson(AlpsDescriptor descriptor, @Nullable String parentId) {
        JsonArray implementations = new JsonArray();
        String targetId = localId(descriptor.rt());
        if (targetId == null || descriptor.id() == null) {
            return implementations;
        }
        String resourceName = Names.kebab(targetId);
        try {
            for (String uri : List.of("app://self/" + resourceName, "page://self/" + resourceName)) {
                String resourcePath = UriUtil.toSupportedResourceRelativePath(uri, false);
                if (resourcePath == null) {
                    continue;
                }
                for (ResourceRelation relation : ResourceRelationIndex.findIncoming(resourcePath, project)) {
                    if (descriptor.id().equals(relation.rel()) && sourceMatches(relation, parentId)) {
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

    private static boolean sourceMatches(ResourceRelation relation, @Nullable String parentId) {
        if (parentId == null) {
            return true;
        }
        String sourceUri = relation.sourceUri();
        if (sourceUri == null) {
            return false;
        }

        // The relation index writes its source URIs lowercased without hyphens ("blogposting")
        // while a descriptor id kebabs to "blog-posting", so the two spellings of a multi-word
        // name never compare equal as written. Hyphen-free lowercase is the one spelling both
        // sides reach.
        return flat(lastSegment(sourceUri)).equals(flat(parentId));
    }

    private static String flat(String name) {
        return name.replace("-", "").toLowerCase(Locale.ROOT);
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

    /**
     * The answer carries the profile contents and its path, so a caller-given path may never
     * leave the project: whatever it resolves to has to sit under the project base directory,
     * mirroring the schema directory guard in {@link SchemaFactsService#byFileName}.
     */
    @Nullable
    private VirtualFile resolveProfileFile(String profilePath) {
        VirtualFile baseDir = FactsFiles.baseDir(project);
        if (baseDir == null) {
            return null;
        }
        VirtualFile file;
        try {
            Path path = Path.of(profilePath);
            file = path.isAbsolute()
                ? LocalFileSystem.getInstance().findFileByNioFile(path)
                : baseDir.findFileByRelativePath(profilePath);
        } catch (InvalidPathException exception) {
            return null;
        }

        return file != null && VfsUtilCore.isAncestor(baseDir, file, false) ? file : null;
    }

    private AlpsProfile parse(VirtualFile file) {
        return AlpsProfileDetector.getInstance(project).parse(file);
    }

    private Provenance provenanceOf(VirtualFile file) {
        return Provenance.ofFile(FactsFiles.relativePath(project, file), AlpsProfileDetector.getInstance(project).isUnsaved(file));
    }

    private static String missingProfileDetail(@Nullable String profilePath) {
        return isSet(profilePath) ? "ALPS profile not found: " + profilePath : NO_PROFILE;
    }

    private List<String> paths(List<VirtualFile> files) {
        List<String> paths = new ArrayList<>();
        for (VirtualFile file : files) {
            paths.add(FactsFiles.relativePath(project, file));
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

        // A remaining "#" or "/" means a reference into another file (Foo.json#Foo), not a local id.
        return rt.contains("/") || rt.contains("#") ? null : rt;
    }

    @Nullable
    private static String stripHash(@Nullable String value) {
        if (!isSet(value)) {
            return null;
        }
        String trimmed = value.trim();

        return trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
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
