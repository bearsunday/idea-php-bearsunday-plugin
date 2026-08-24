package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.PhpIndex;
import com.jetbrains.php.lang.psi.elements.ClassReference;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.MethodReference;
import com.jetbrains.php.lang.psi.elements.NewExpression;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.Variable;
import idea.bear.sunday.aop.InterceptorBindingIndexUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves a BEAR.Sunday context string ({@code "prod-hal-api-app"}) to the module tree it
 * installs. The convention is BEAR.Package's loader ({@code BEAR\Package\Module}): each
 * hyphen-separated segment names {@code {AppName}\Module\{Segment}Module} when the app declares
 * one, else {@code BEAR\Package\Context\{Segment}Module}, and the loader wraps them right to
 * left, so the leftmost segment's bindings win (Ray.Di's {@code Container::merge} keeps the
 * receiving container's bindings on conflict).
 *
 * <p>Unlike {@link DiBindingLookupService}, which walks files under a root, this resolves class
 * names through the project index, so it answers {@code index_not_ready} while the index builds.
 */
@Service(Service.Level.PROJECT)
public final class DiModuleTreeService {

    static final String APP_MODULE_PATH = "src/Module/AppModule.php";

    private static final String FRAMEWORK_CONTEXT_NAMESPACE = "\\BEAR\\Package\\Context\\";
    private static final String APP_META_MODULE = "\\BEAR\\Package\\Module\\AppMetaModule";
    private static final String ASSISTED_MODULE = "\\Ray\\Di\\AssistedModule";
    private static final String RAY_DI_ABSTRACT_MODULE = "\\Ray\\Di\\AbstractModule";
    private static final String MODULE_SUFFIX = "Module";
    private static final String INSTALL = "install";
    private static final String OVERRIDE = "override";

    private static final String KIND_INSTALL = "install";
    private static final String KIND_OVERRIDE = "override";

    /** Well past any real module graph; reached only by a runaway one, and then reported. */
    private static final int MAX_MODULES = 300;

    /** Well past any real module hierarchy; guards a PSI cycle a half-typed file can produce. */
    private static final int MAX_WIRING_CLASSES = 20;

    /** Ahead of every segment, because the loader's own last override outranks all of them. */
    private static final int FRAMEWORK_PRIORITY = 0;

    private final Project project;

    public DiModuleTreeService(Project project) {
        this.project = project;
    }

    public static DiModuleTreeService getInstance(Project project) {
        return project.getService(DiModuleTreeService.class);
    }

    public String read(@Nullable String context, boolean diagram) {
        return readDrawn(context, diagram).envelope();
    }

    /**
     * The same answer, with what each node of its drawing stands for. A picture drawn in a tool
     * window can be clicked and a picture sent to an AI cannot, so the node map is answered beside
     * the envelope rather than inside it: the envelope stays the one the tool returns, byte for
     * byte, and the ids -- which are the renderer's own -- stay out of an answer no client can use
     * them in.
     */
    public Drawn readDrawn(@Nullable String context, boolean diagram) {
        // Non-blocking so a pending write action is not made to wait out the read; cancelled and
        // retried instead. See DiBindingLookupService#lookup.
        return ReadAction.nonBlocking(() -> readTree(context, diagram)).executeSynchronously();
    }

    /** An answer and, when one was drawn, the node map of its drawing. */
    public record Drawn(String envelope, @Nullable JsonObject nodes) {
    }

    private Drawn readTree(@Nullable String context, boolean diagram) {
        if (context == null || context.isBlank()) {
            return new Drawn(Envelope.notFound("context is required, e.g. \"prod-api-app\"").toJson(), null);
        }

        try {
            Walk walk = walk(context.trim());

            JsonObject scan = new JsonObject();
            scan.addProperty("context", walk.context());
            scan.addProperty("segments", walk.segmentsJson().size() + walk.unresolvedJson().size());
            scan.addProperty("modules", walk.modules().size());
            if (walk.modulesSkipped() > 0) {
                scan.addProperty("modulesSkipped", walk.modulesSkipped());
            }

            JsonObject payload = new JsonObject();
            payload.add("scan", scan);
            if (walk.appNamespace() != null) {
                payload.addProperty("appNamespace", walk.appNamespace());
            } else {
                // Without src/Module/AppModule.php the app-side candidate cannot even be named,
                // so a segment resolved to the framework module may still be shadowed by an app
                // module this could not look for.
                payload.addProperty("appNamespaceUnknown", true);
            }
            payload.add("segments", walk.segmentsJson());
            if (!walk.unresolvedJson().isEmpty()) {
                payload.add("unresolvedSegments", walk.unresolvedJson());
            }
            payload.add("frameworkOverride", walk.frameworkOverride());
            payload.add("assistedModule", walk.assistedModule());
            JsonObject nodes = null;
            if (diagram) {
                ModuleTreeDiagram.Drawing drawing = ModuleTreeDiagram.draw(payload);
                payload.addProperty("diagram", drawing.mermaid());
                nodes = drawing.nodes();
            }

            String envelope = Envelope.ok(Provenance.derived(walk.context(), walk.unsaved()), payload).toJson();

            return new Drawn(envelope, nodes);
        } catch (IndexNotReadyException exception) {
            return new Drawn(
                Envelope.indexNotReady("The project index is still building; module classes cannot be resolved yet.").toJson(),
                null
            );
        }
    }

    /**
     * Resolves the context and walks every module tree it installs. The walked modules are carried
     * out whole, in priority order, for {@link DiBindingLookupService} to scan when it is asked for
     * the bindings of a context rather than of a directory.
     *
     * @throws IndexNotReadyException while the project index is building
     */
    Walk walk(String context) {
        String appNamespace = appNamespace();
        JsonArray segmentsJson = new JsonArray();
        JsonArray unresolvedJson = new JsonArray();
        List<WalkedModule> modules = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        WalkState state = new WalkState();
        boolean unsaved = false;

        // Walked before the segments, because "expanded once, then visited" is only safe if the
        // strongest node is the one expanded -- and priority 0 outranks every segment. Walked
        // last, a segment that installs AppMetaModule itself would expand it, leaving the node the
        // answer calls the strongest reported as an empty "visited" leaf.
        JsonObject frameworkOverride = frameworkOverrideJson(visited, modules, state);

        // -1 keeps the trailing empty segments Java would otherwise drop. The loader's own
        // explode('-', $context) keeps them, and a context ending in a hyphen is one no app can
        // boot on -- dropping the segment here would answer for a context the caller did not ask
        // about, and answer it ok.
        String[] segments = context.split("-", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            int priority = i + 1;
            Resolution resolution = resolveSegment(segment, appNamespace);
            if (resolution == null) {
                JsonObject json = new JsonObject();
                json.addProperty("segment", segment);
                json.addProperty("priority", priority);
                JsonArray candidates = new JsonArray();
                for (String candidate : segmentCandidates(segment, appNamespace)) {
                    candidates.add(candidate);
                }
                json.add("candidates", candidates);
                unresolvedJson.add(json);
                continue;
            }

            JsonObject json = moduleJson(resolution.phpClass(), segment, priority, visited, modules, state, null, null);
            json.addProperty("segment", segment);
            json.addProperty("priority", priority);
            json.addProperty("origin", resolution.origin());
            segmentsJson.add(json);
        }

        // Walked after the segments because every one of them wraps it, which makes it the weakest
        // node in the tree -- and the visited set expands whichever node is walked first, so a
        // segment that installs it too keeps the expansion where the stronger reach is.
        JsonObject assistedModule = loaderModuleJson(ASSISTED_MODULE, segments.length + 1, visited, modules, state);

        for (WalkedModule module : modules) {
            for (VirtualFile file : module.files()) {
                unsaved |= FactsFiles.isUnsaved(file);
            }
        }
        // AppModule.php is read for the app's namespace without ever becoming a walked module, and
        // an unsaved edit to it moves every segment between the app and the framework candidate --
        // the one file whose freshness the whole answer turns on.
        VirtualFile appModuleFile = FactsFiles.find(project, APP_MODULE_PATH);
        unsaved |= appModuleFile != null && FactsFiles.isUnsaved(appModuleFile);
        unsaved |= state.unsaved;

        return new Walk(
            context,
            appNamespace,
            segmentsJson,
            unresolvedJson,
            frameworkOverride,
            assistedModule,
            modules,
            state.skipped,
            state.classesUnresolved,
            state.installsUnreadable,
            state.installArgumentsUnreadable,
            unsaved
        );
    }

    /**
     * The loader's own last step, which no context segment names:
     * {@code $module->override(new AppMetaModule($appMeta))} ({@code BEAR\Package\Module}). Ray.Di's
     * {@code override()} merges the receiver into the argument's container, and {@code Container::merge}
     * keeps the receiving container's bindings, so what {@code AppMetaModule} binds beats every
     * segment. A tree that left it out would omit the strongest bindings in the graph.
     */
    private JsonObject frameworkOverrideJson(Set<String> visited, List<WalkedModule> modules, WalkState state) {
        JsonObject json = loaderModuleJson(APP_META_MODULE, FRAMEWORK_PRIORITY, visited, modules, state);
        json.addProperty("kind", KIND_OVERRIDE);

        return json;
    }

    /**
     * A module the loader adds itself, which no context segment names: {@code AppMetaModule} at one
     * end and {@code Ray\Di\AssistedModule} at the other. Both are part of the tree whatever the
     * context says, so both are reported -- as {@code classUnresolved} when the package is not
     * installed, which is an answer rather than a silence.
     */
    private JsonObject loaderModuleJson(String fqn, int priority, Set<String> visited, List<WalkedModule> modules, WalkState state) {
        PhpClass phpClass = classByFqn(fqn);
        JsonObject json;
        if (phpClass == null) {
            json = new JsonObject();
            json.addProperty("moduleClass", fqn);
            json.addProperty("classUnresolved", true);
            state.classesUnresolved++;
        } else {
            json = moduleJson(phpClass, null, priority, visited, modules, state, null, null);
        }
        json.addProperty("priority", priority);

        return json;
    }

    /**
     * One module node: its site, then the modules it installs, walked with a shared visited set,
     * so a module two segments both install is expanded once and marked {@code "visited"} after.
     */
    private JsonObject moduleJson(
        PhpClass phpClass,
        @Nullable String segment,
        int priority,
        Set<String> visited,
        List<WalkedModule> modules,
        WalkState state,
        @Nullable String overriddenReceiver,
        @Nullable Constants constants
    ) {
        JsonObject json = new JsonObject();
        String fqn = phpClass.getFQN();
        json.addProperty("moduleClass", fqn);
        VirtualFile file = fileOf(phpClass);
        if (file != null) {
            json.addProperty("filePath", FactsFiles.relativePath(project, file));
        }
        // PHP compares class names case-insensitively, and Locale.ROOT so that a Turkish locale
        // does not fold "I" into a dotless one and make two names of a class that has one.
        // A module that binds an array it was handed is one container per INSTALL, not per class:
        // two installs of it bind two different sets of names, and folding them into one node
        // would drop every name the second one binds.
        String key = constants == null || constants.site() == null
            ? fqn.toLowerCase(Locale.ROOT)
            : fqn.toLowerCase(Locale.ROOT) + "@" + constants.site();
        // Already expanded, so its subtree really is elsewhere in this answer -- asked before the
        // cap, because a module expanded before the cap was reached is not one the cap cut.
        if (visited.contains(key)) {
            json.addProperty("visited", true);

            return json;
        }
        // Cut by the cap, and marked as cut: the mark is not deferred to visited, which would
        // promise a subtree that is nowhere in the answer. Nothing loops on this path either,
        // because a cut node returns without recursing.
        if (modules.size() >= MAX_MODULES) {
            state.skipped++;
            json.addProperty("skipped", true);

            return json;
        }
        visited.add(key);

        JsonArray installs = new JsonArray();
        // install()/override() are read wherever the class calls them, not only in configure():
        // a module may split its wiring over helper methods, and Ray.Di runs whatever configure()
        // reaches. PHP dispatches $this->install() to the runtime class's method whichever body
        // the call is written in, so both marks are judged once, against the module itself.
        boolean ownInstall = declaresEdgeMethod(phpClass, INSTALL);
        boolean ownOverride = declaresEdgeMethod(phpClass, OVERRIDE);
        Wiring wiring = wiringClasses(phpClass);
        // Recorded before the edges are walked, so the modules stay in walk order -- which is
        // priority order, strongest first, and what a scan reading these files goes by.
        List<VirtualFile> wiringFiles = wiringFiles(wiring);
        if (!wiringFiles.isEmpty() || constants != null) {
            modules.add(new WalkedModule(fqn, wiringFiles, segment, priority, overriddenReceiver, constants));
        }
        if (constants != null) {
            // The names this install binds, said on the node: without it an install that expanded
            // to twenty bindings looks exactly like one that expanded to none.
            json.addProperty("boundFromInstall", constants.entries().size());
            if (constants.site() == null) {
                // The module binds an array, and this install does not state which array. Marked
                // rather than expanded, because the names it binds are the ones a caller would
                // otherwise be told nobody binds.
                json.addProperty("argumentUnreadable", true);
            }
            if (constants.keysUnreadable() > 0) {
                json.addProperty("keysUnreadable", constants.keysUnreadable());
            }
        }
        // A base module the index cannot resolve is the one thing this walk could not read, and
        // saying nothing would make the node identical to a module that installs nothing -- the
        // very silence reading the base classes was added to end, one level further up.
        if (wiring.unresolvedBase() != null) {
            json.addProperty("baseClassUnresolved", wiring.unresolvedBase());
        }
        for (PhpClass source : wiring.classes()) {
            String inheritedFrom = source == phpClass ? null : source.getFQN();
            // Every body the edges are read from counts towards freshness: a base module is read
            // through PSI, so an unsaved edit to it changes this answer as surely as one to the
            // module's own file, which the walked-module sweep already covers.
            VirtualFile sourceFile = fileOf(source);
            state.unsaved |= sourceFile != null && FactsFiles.isUnsaved(sourceFile);
            for (MethodReference call : PsiTreeUtil.findChildrenOfType(source, MethodReference.class)) {
                ProgressManager.checkCanceled();
                String kind = edgeKind(call);
                // An anonymous module class written inside this one -- install(new class extends
                // AbstractModule {...}) -- carries its own $this, so its installs are its own.
                // Reading them here would report the inner module's edges as this module's direct
                // ones. This is the enclosing-class test the binding lookup makes for the same
                // reason.
                if (kind == null || PsiTreeUtil.getParentOfType(call, PhpClass.class) != source) {
                    continue;
                }
                JsonObject edge = installJson(call, kind, segment, priority, visited, modules, state, fqn);
                // A class declaring install() of its own is calling that method rather than
                // Ray.Di's, and what it does with the module is its own business. Saying so is the
                // whole correction: dropping the edge would report the module as installing less
                // than its source plainly states, and each name is judged on its own because
                // declaring one says nothing about the other.
                if (KIND_INSTALL.equals(kind) ? ownInstall : ownOverride) {
                    edge.addProperty("ownMethod", true);
                }
                if (inheritedFrom != null) {
                    edge.addProperty("inheritedFrom", inheritedFrom);
                }
                installs.add(edge);
            }
        }
        if (!installs.isEmpty()) {
            json.add("installs", installs);
        }

        return json;
    }

    /**
     * The classes whose bodies wire this module: the module itself, then the classes it extends.
     * A module that leaves its wiring to a base module -- {@code final class ProdModule extends
     * AbstractProdModule {}}, or a {@code configure()} that calls {@code parent::configure()} --
     * states its installs in that base and nowhere else, so reading the class's own body alone
     * would report it as installing nothing. Ray.Di's own {@code AbstractModule} ends the chain
     * and installs nothing itself.
     *
     * <p>Whether a subclass's {@code configure()} really chains to the one it inherits is not
     * decided here; the edge names the class it was read from instead, so the caller is told what
     * the base installs rather than promised that this module runs it.
     *
     * <p>Traits are not read: no module in {@code bear/*} or {@code ray/*} wires through one, and
     * a trait is not how Ray.Di modules are written.
     */
    private static Wiring wiringClasses(PhpClass phpClass) {
        List<PhpClass> classes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        PhpClass current = phpClass;
        // Well past any real module hierarchy; a bound at all only because a half-typed file can
        // put PSI in a state where a class is its own ancestor.
        while (classes.size() < MAX_WIRING_CLASSES) {
            String fqn = current.getFQN();
            if (fqn == null || !seen.add(fqn.toLowerCase(Locale.ROOT))) {
                break;
            }
            classes.add(current);
            PhpClass parent = current.getSuperClass();
            if (parent == null) {
                // Nothing left to read -- but a class that extends nothing and one whose base the
                // index cannot resolve both arrive here, and only the second leaves wiring unread.
                return new Wiring(classes, extendedName(current));
            }
            current = parent;
        }

        return new Wiring(classes, null);
    }

    /**
     * The files a module's wiring is written in: its own, then its base modules'. A scan given
     * these reads exactly the bodies this walk read the installs from, so a binding declared in a
     * base module is not missed by the one tool while the other reports what that base installs.
     *
     * <p>Ray.Di's own {@code AbstractModule} is left out. It ends every chain and declares
     * {@code bind()} itself rather than calling it, so its file holds no module's bindings, and
     * reading it would put the framework's own source in the scan of every project.
     */
    private static List<VirtualFile> wiringFiles(Wiring wiring) {
        List<VirtualFile> files = new ArrayList<>();
        for (PhpClass source : wiring.classes()) {
            String fqn = source.getFQN();
            VirtualFile file = fileOf(source);
            if (file != null && !RAY_DI_ABSTRACT_MODULE.equalsIgnoreCase(fqn)) {
                files.add(file);
            }
        }

        return files;
    }

    /**
     * The class a class's {@code extends} clause names, resolved through the file's own use
     * statements rather than the index, so it can be named even when it cannot be found. A class
     * that extends nothing names none -- and neither does one whose chain ends at Ray.Di's own
     * {@code AbstractModule}, the root every module reaches and the one class whose body installs
     * nothing, so failing to resolve it leaves no wiring unread and reporting it would put the
     * mark on every module in a project whose vendor directory is not indexed.
     */
    @Nullable
    private static String extendedName(PhpClass phpClass) {
        for (ClassReference reference : phpClass.getExtendsList().getReferenceElements()) {
            String fqn = reference.getFQN();
            if (fqn == null || fqn.isBlank()) {
                continue;
            }
            String normalized = InterceptorBindingIndexUtil.normalizeFqn(fqn);

            return RAY_DI_ABSTRACT_MODULE.equalsIgnoreCase(normalized) ? null : normalized;
        }

        return null;
    }

    /**
     * Whether a class declares an {@code install()}/{@code override()} of its own, anywhere below
     * {@code Ray\Di\AbstractModule} -- which declares both, so the one found there is Ray.Di's and
     * not the class's. A hierarchy PHP cannot resolve answers no, which keeps the edge unmarked
     * rather than marked on a guess.
     */
    private static boolean declaresEdgeMethod(PhpClass phpClass, String method) {
        Method found = phpClass.findMethodByName(method);
        PhpClass owner = found == null ? null : found.getContainingClass();
        String fqn = owner == null ? null : owner.getFQN();

        return fqn != null && !RAY_DI_ABSTRACT_MODULE.equalsIgnoreCase(fqn);
    }

    private JsonObject installJson(
        MethodReference call,
        String kind,
        @Nullable String segment,
        int priority,
        Set<String> visited,
        List<WalkedModule> modules,
        WalkState state,
        String receiver
    ) {
        String fqn = installedModuleFqn(call);
        JsonObject json;
        if (fqn == null) {
            // $this->install($module), install($this->maybe()), a conditional install -- an
            // edge whose module the source does not name is reported, never dropped: dropping it
            // would read as "this module installs nothing else". The verb is still the one the
            // source states, so an override that could not be read is not filed as an install.
            json = new JsonObject();
            json.addProperty("moduleUnreadable", true);
            state.installsUnreadable++;
        } else {
            PhpClass installed = classByFqn(fqn);
            if (installed == null) {
                json = new JsonObject();
                json.addProperty("moduleClass", fqn);
                json.addProperty("classUnresolved", true);
                state.classesUnresolved++;
            } else {
                // override() reverses the merge -- Ray.Di does $module->merge($this) and keeps
                // the MODULE's container -- so the module named here beats the one that named it.
                // The walk reaches it the other way round, receiver first, and the receiver is
                // carried along so a reader of these modules can put the two back in merge order.
                json = moduleJson(
                    installed,
                    segment,
                    priority,
                    visited,
                    modules,
                    state,
                    KIND_OVERRIDE.equals(kind) ? receiver : null,
                    constantsOf(installed, call, state)
                );
            }
        }
        json.addProperty("kind", kind);
        // Where the install is WRITTEN, which is not where the installed module lives: the node's
        // own filePath names the installed class's file, and pairing that path with this line
        // would point at a line of a file the call is not in. A trait or a base module puts the
        // two further apart still.
        json.add("installedAt", siteJson(call));
        json.addProperty("text", callText(call));

        return json;
    }

    /** The file and line an install is written at, read from the call's own file. */
    private JsonObject siteJson(MethodReference call) {
        JsonObject site = new JsonObject();
        PsiFile file = call.getContainingFile();
        VirtualFile virtualFile = file == null ? null : file.getVirtualFile();
        if (virtualFile != null) {
            site.addProperty("filePath", FactsFiles.relativePath(project, virtualFile));
        }
        Integer line = FactsFiles.lineOf(file, call.getTextOffset());
        if (line != null) {
            site.addProperty("line", line);
        }

        return site;
    }

    /** The Ray.Di edge a call makes, or {@code null} when the call is not one. */
    @Nullable
    private static String edgeKind(MethodReference call) {
        String name = call.getName();
        boolean install = INSTALL.equalsIgnoreCase(name);
        if (!install && !OVERRIDE.equalsIgnoreCase(name)) {
            return null;
        }
        if (!(call.getClassReference() instanceof Variable receiver) || !"this".equals(receiver.getName())) {
            return null;
        }
        if (call.getParameters().length != 1) {
            return null;
        }

        return install ? KIND_INSTALL : KIND_OVERRIDE;
    }

    /**
     * The class a literal {@code new FooModule(...)} argument names, or {@code null}. Only the
     * outer class is read: a module handed in through the constructor
     * ({@code new FooModule(new BarModule())}) wraps rather than installs, and following it here
     * would claim an install edge the source does not state.
     */
    @Nullable
    private static String installedModuleFqn(MethodReference call) {
        if (!(call.getParameters()[0] instanceof NewExpression newExpression)) {
            return null;
        }
        ClassReference reference = newExpression.getClassReference();
        String fqn = reference == null ? null : reference.getFQN();

        return fqn == null || fqn.isBlank() ? null : InterceptorBindingIndexUtil.normalizeFqn(fqn);
    }

    /**
     * The array entries an {@code install(new FooModule([...]))} binds, when {@code FooModule} is
     * written to bind the entries of an array it was handed. {@code null} when it is not one --
     * which is nearly every module, and the only case that costs nothing to ask about.
     *
     * <p>A shape that matched but an argument that could not be read is NOT null: the module still
     * binds names this walk cannot list, and saying so is the difference between "these twenty
     * names are bound here" and a silence that reads as "nobody binds them".
     */
    @Nullable
    private Constants constantsOf(PhpClass installed, MethodReference call, WalkState state) {
        ArrayBindings.Shape shape = ArrayBindings.shapeOf(installed);
        if (shape == null) {
            return null;
        }
        // The loop this expands was read from the module's own file, and once expanded that file
        // is no longer read for this binding -- so the sweep that notices an unsaved edit to it
        // would stop noticing. Counted here instead, where the body was actually read.
        VirtualFile file = fileOf(installed);
        state.unsaved |= file != null && FactsFiles.isUnsaved(file);
        if (!(call.getParameters()[0] instanceof NewExpression newExpression)) {
            return new Constants(shape.bindCall(), List.of(), null, 0);
        }
        ArrayBindings.Expansion expansion = ArrayBindings.expand(shape, newExpression);
        if (expansion == null) {
            state.installArgumentsUnreadable++;

            return new Constants(shape.bindCall(), List.of(), null, 0);
        }

        return new Constants(
            expansion.bindCall(),
            expansion.entries(),
            installSite(newExpression),
            expansion.keysUnreadable()
        );
    }

    /**
     * What tells one {@code install()} of a module from another one: the file it is written in and
     * where in that file it starts. Never shown -- it only has to be different for two calls and
     * the same for one call read twice.
     */
    @Nullable
    private String installSite(NewExpression call) {
        PsiFile file = call.getContainingFile();
        VirtualFile virtualFile = file == null ? null : file.getVirtualFile();

        return virtualFile == null
            ? null
            : FactsFiles.relativePath(project, virtualFile) + ":" + call.getTextOffset();
    }

    /**
     * The segment's module class under BEAR.Package's convention: the app's namespace first,
     * {@code BEAR\Package\Context} second, exactly as the loader's {@code class_exists} fallback
     * tries them.
     */
    @Nullable
    private Resolution resolveSegment(String segment, @Nullable String appNamespace) {
        List<String> candidates = segmentCandidates(segment, appNamespace);
        for (String candidate : candidates) {
            PhpClass phpClass = classByFqn(candidate);
            if (phpClass != null) {
                boolean app = appNamespace != null && candidate.startsWith(appNamespace + "\\");

                return new Resolution(phpClass, app ? "app" : "framework");
            }
        }

        return null;
    }

    private static List<String> segmentCandidates(String segment, @Nullable String appNamespace) {
        if (segment.isBlank()) {
            return List.of();
        }
        String className = Character.toUpperCase(segment.charAt(0)) + segment.substring(1) + MODULE_SUFFIX;
        List<String> candidates = new ArrayList<>(2);
        if (appNamespace != null) {
            candidates.add(appNamespace + "\\Module\\" + className);
        }
        candidates.add(FRAMEWORK_CONTEXT_NAMESPACE + className);

        return candidates;
    }

    @Nullable
    private PhpClass classByFqn(String fqn) {
        for (PhpClass phpClass : PhpIndex.getInstance(project).getClassesByFQN(fqn)) {
            if (!phpClass.isInterface() && !phpClass.isTrait() && !phpClass.isEnum()) {
                return phpClass;
            }
        }

        return null;
    }

    /**
     * The app's namespace ({@code \MyVendor\MyProject}), read from the namespace of
     * {@code src/Module/AppModule.php} minus the trailing {@code \Module} -- a file-local read,
     * matching how {@code AbstractAppMeta::$name} names the same namespace at runtime.
     */
    @Nullable
    private String appNamespace() {
        VirtualFile file = FactsFiles.find(project, APP_MODULE_PATH);
        PsiFile psiFile = file == null ? null : com.intellij.psi.PsiManager.getInstance(project).findFile(file);
        PhpClass phpClass = psiFile == null ? null : PsiTreeUtil.findChildOfType(psiFile, PhpClass.class);
        if (phpClass == null) {
            return null;
        }
        String namespace = phpClass.getNamespaceName();
        if (namespace.endsWith("\\")) {
            namespace = namespace.substring(0, namespace.length() - 1);
        }
        String suffix = "\\Module";
        if (!namespace.regionMatches(true, namespace.length() - suffix.length(), suffix, 0, suffix.length())) {
            return null;
        }
        String appNamespace = namespace.substring(0, namespace.length() - suffix.length());

        return appNamespace.isBlank() ? null : appNamespace;
    }

    @Nullable
    private static VirtualFile fileOf(PhpClass phpClass) {
        PsiFile psiFile = phpClass.getContainingFile();

        return psiFile == null ? null : psiFile.getVirtualFile();
    }

    /** Source text on one line, matching the binding lookup's own call texts. */
    private static String callText(MethodReference call) {
        return PhpSource.oneLine(call);
    }

    /** What the walk accumulates outside the tree it builds. */
    private static final class WalkState {
        private int skipped;
        private boolean unsaved;
        private int classesUnresolved;
        private int installsUnreadable;
        private int installArgumentsUnreadable;
    }

    /** The bodies a module's wiring is read from, and the base class that could not be read. */
    private record Wiring(List<PhpClass> classes, @Nullable String unresolvedBase) {
    }

    private record Resolution(PhpClass phpClass, String origin) {
    }

    /**
     * A module the walk reached: the files its wiring is written in, and which context segment
     * reaches it. The segment is {@code null} for the two modules the loader adds itself: the final
     * override, whose priority is {@link #FRAMEWORK_PRIORITY} ahead of every segment's, and the
     * assisted module, whose priority is one past the last segment's because every segment wraps it.
     */
    record WalkedModule(
        String fqn,
        List<VirtualFile> files,
        @Nullable String segment,
        int priority,
        @Nullable String overriddenReceiver,
        @Nullable Constants constants
    ) {
    }

    /**
     * A module that binds the entries of an array it was constructed with, as one {@code install()}
     * of it hands them over. Present whenever the module has that SHAPE, so a reader can tell an
     * install whose array could not be read from a module that binds no array at all: the entries
     * are empty and {@code site} is null for the first, and there is no {@code Constants} for the
     * second.
     *
     * <p>{@code site} is what tells two installs of one module apart when their bindings collide.
     * {@code bindCall} is the chain in the module's own body that these entries stand for, kept so
     * the scan reading that file does not count it a second time as the unreadable binding it is
     * when read alone.
     */
    record Constants(
        MethodReference bindCall,
        List<ArrayBindings.Entry> entries,
        @Nullable String site,
        int keysUnreadable
    ) {
    }

    /**
     * Everything one walk of a context produced, for the tree answer and the binding scan alike.
     * The two counts are what the walk could not read: a scan that reports neither would answer
     * "nothing binds this" from a tree with holes in it.
     */
    record Walk(
        String context,
        @Nullable String appNamespace,
        JsonArray segmentsJson,
        JsonArray unresolvedJson,
        JsonObject frameworkOverride,
        JsonObject assistedModule,
        List<WalkedModule> modules,
        int modulesSkipped,
        int classesUnresolved,
        int installsUnreadable,
        int installArgumentsUnreadable,
        boolean unsaved
    ) {
    }
}
