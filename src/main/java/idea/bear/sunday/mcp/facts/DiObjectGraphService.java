package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.jetbrains.php.PhpIndex;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.Parameter;
import com.jetbrains.php.lang.psi.elements.ParameterList;
import com.jetbrains.php.lang.psi.elements.PhpAttribute;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.resolve.types.PhpType;
import idea.bear.sunday.aop.InterceptorBindingIndexUtil;
import idea.bear.sunday.resource.ResourceClassResolver;
import idea.bear.sunday.util.UriUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Answers what a class is actually built out of in a given context: the object graph Ray.Di would
 * assemble, resolved from the source rather than from a running application.
 *
 * <p>This is the question {@code print_o} answers at runtime, and it needs a booted application and
 * an assembled object to answer it. The same question asked of the source has no such cost -- and
 * no way to see what a running object holds either. The two do not see the same thing: {@code
 * print_o} walks the PROPERTIES of a live instance, so a property assigned in {@code onGet()} is in
 * its picture; this walks the INJECTION POINTS, so only what Ray.Di puts there is in this one. The
 * contract followed here is Ray.Di's own {@code VisitorInterface} -- constructor arguments and
 * {@code #[Inject]} setters -- not {@code print_o}'s.
 *
 * <p>A node is a container key, spelled the way Ray.Di spells it: {@code "{type}-{name}"}, the very
 * string {@code Container::getDependency()} is given. An edge is one injection point that leads to
 * another key. Which binding of a key wins is decided here, by Ray.Di's own merge rules, and the
 * ones that lost are named on the node rather than dropped -- a graph that shows one implementation
 * and stays silent about the module that nearly supplied another has answered half the question.
 *
 * <p>A context is required. Nearly every binding a BEAR application relies on lives in a framework
 * package, so a graph read from a default directory would confidently report an unbound dependency
 * for wiring that is plainly there.
 */
@Service(Service.Level.PROJECT)
public final class DiObjectGraphService {

    /**
     * What a BEAR application is built from when nothing else is asked for: the interface its
     * bootstrap resolves. Following it reaches the router, the transfer, the resource client and
     * the error handler -- the whole application in one answer.
     */
    private static final String DEFAULT_ENTRY = "\\BEAR\\Sunday\\Extension\\Application\\AppInterface";

    /** What {@code AppMetaModule} appends to the app namespace to name the application class. */
    private static final String APP_CLASS_SUFFIX = "\\Module\\App";

    /**
     * The two keys the container answers for without any module binding them. Ray.Di binds them in
     * PHP rather than in a module -- {@code Injector::__construct()} does
     * {@code (new Bind($container, InjectorInterface::class))->toInstance($this)} AFTER the modules
     * have built the container, and {@code Arguments::bindInjectionPoint()} rebinds the injection
     * point for every argument it resolves -- so both beat whatever a module said, and calling
     * either unbound would report a failure the application never has.
     */
    private static final Set<String> BUILT_IN =
        Set.of("\\Ray\\Di\\InjectorInterface-", "\\Ray\\Di\\InjectionPointInterface-");

    private static final String NAMED = "\\Ray\\Di\\Di\\Named";
    private static final String QUALIFIER = "\\Ray\\Di\\Di\\Qualifier";
    private static final String INJECT_INTERFACE = "\\Ray\\Di\\Di\\InjectInterface";

    /**
     * The parameter types Ray.Di keys under an EMPTY type, from {@code Argument::UNBOUND_TYPE}. A
     * scalar names no class to bind, so {@code #[Named('dsn')] string $dsn} is filed under
     * {@code "-dsn"} and not under {@code "string-dsn"}.
     */
    private static final Set<String> UNBOUND_TYPE =
        Set.of("bool", "int", "float", "string", "array", "resource", "callable", "iterable");

    private static final String EDGE_CONSTRUCTOR = "constructor-param";
    private static final String EDGE_SETTER = "setter-param";

    private static final String RESOLUTION_STATIC = "static";
    private static final String RESOLUTION_PROVIDER = "provider";
    private static final String RESOLUTION_INSTANCE = "instance";
    private static final String RESOLUTION_NULL_OBJECT = "null-object";
    private static final String RESOLUTION_DYNAMIC = "dynamic-unresolved";
    private static final String RESOLUTION_UNBOUND = "unbound";
    private static final String RESOLUTION_BUILT_IN = "builtin";
    private static final String RESOLUTION_ENTRY_UNTARGETED = "entry-untargeted";
    private static final String RESOLUTION_CLASS_UNRESOLVED = "class-unresolved";

    /** Far past any real graph; a stop for one that a cap has to cut rather than follow forever. */
    private static final int MAX_NODES = 400;

    /** Deep enough for the whole of a BEAR application, and a stop for a chain that does not end. */
    private static final int MAX_DEPTH = 30;

    private static final int MAX_HIERARCHY = 30;

    private final Project project;

    public DiObjectGraphService(Project project) {
        this.project = project;
    }

    public static DiObjectGraphService getInstance(Project project) {
        return project.getService(DiObjectGraphService.class);
    }

    /**
     * The graph one class is built from, as JSON.
     *
     * @param className the class or interface to start from, or {@code null}
     * @param uri       a resource URI to start from instead, or {@code null}
     * @param context   the application context whose modules do the binding; required
     * @param diagram   whether to render the answer as a Mermaid flowchart as well
     */
    public String graph(
        @Nullable String className,
        @Nullable String uri,
        @Nullable String context,
        boolean diagram
    ) {
        // Not ReadAction.compute: that holds the read lock until the whole walk is done, and a
        // pending write action -- every keystroke -- waits behind it.
        return ReadAction.nonBlocking(() -> build(className, uri, context, diagram)).executeSynchronously();
    }

    private String build(
        @Nullable String className,
        @Nullable String uri,
        @Nullable String context,
        boolean diagram
    ) {
        if (context == null || context.isBlank()) {
            return Envelope.notFound(
                "Pass context (\"prod-hal-app\"): nearly every binding a BEAR application relies on is "
                    + "declared in a framework package, so a graph read without one would report a "
                    + "dependency as unbound when a module plainly binds it."
            ).toJson();
        }

        try {
            // Walked first because the default entry is named out of what it found: the app
            // namespace is the front half of the application class.
            DiBindingLookupService.ContextBindings bindings =
                DiBindingLookupService.getInstance(project).bindingsOf(context.trim());
            Entry entry = resolveEntry(className, uri, bindings.walk().appNamespace());
            if (entry.error() != null) {
                return entry.error();
            }
            Container container = Container.of(bindings.modules());

            Walk walk = new Walk(container);
            walk.run(entry);

            JsonObject payload = new JsonObject();
            payload.add("entry", entry.json());
            payload.add("scan", scanJson(bindings, walk, context.trim()));
            payload.add("nodes", walk.nodesJson());
            payload.add("edges", walk.edges);
            if (diagram) {
                ObjectGraphDiagram.Drawing drawing = ObjectGraphDiagram.draw(payload);
                payload.addProperty("diagram", drawing.mermaid());
                payload.add("diagramNodes", drawing.nodes());
            }

            return Envelope.ok(Provenance.derived(context.trim(), bindings.walk().unsaved()), payload).toJson();
        } catch (IndexNotReadyException exception) {
            return Envelope.indexNotReady(
                "The project index is still building; neither the modules a context installs nor the "
                    + "classes a graph is walked through can be resolved yet."
            ).toJson();
        }
    }

    // ---------------------------------------------------------------- entry

    /** Where the walk starts: a class, or the error that says why there is none. */
    private record Entry(@Nullable PhpClass phpClass, @Nullable String key, String via, @Nullable String error) {

        static Entry of(PhpClass phpClass, String via) {
            String fqn = phpClass.getFQN();

            return new Entry(phpClass, fqn + "-", via, null);
        }

        static Entry failed(String error) {
            return new Entry(null, null, "", error);
        }

        JsonObject json() {
            JsonObject json = new JsonObject();
            json.addProperty("key", key);
            json.addProperty("class", phpClass == null ? null : phpClass.getFQN());
            json.addProperty("via", via);

            return json;
        }
    }

    /**
     * The class a BEAR application is built from when nothing else is asked for.
     *
     * <p>Not {@code AppInterface}, though that is what the bootstrap resolves: {@code AppMetaModule}
     * binds it with {@code ->to($this->appMeta->name . '\\Module\\App')}, a class name built while
     * the application runs, so the binding names no class any reader of the source can follow, and a
     * graph started there is one node long. The class it names is knowable all the same -- the app
     * namespace and that suffix are the whole of it -- so the walk starts at the class instead of at
     * the interface that leads to it. Without an app namespace there is nothing to build the name
     * from, and the interface is the honest second best.
     */
    private static String defaultEntry(@Nullable String appNamespace) {
        return appNamespace == null ? DEFAULT_ENTRY : appNamespace + APP_CLASS_SUFFIX;
    }

    private Entry resolveEntry(@Nullable String className, @Nullable String uri, @Nullable String appNamespace) {
        if (uri != null && !uri.isBlank()) {
            String normalized = UriUtil.normalizeSupportedResourceUri(uri.trim(), false);
            if (normalized == null) {
                return Entry.failed(Envelope.notFound("Unsupported resource URI: " + uri).toJson());
            }
            Optional<PhpClass> resolved = ResourceClassResolver.resolveCached(project, normalized);

            return resolved.map(phpClass -> Entry.of(phpClass, "uri"))
                .orElseGet(() -> Entry.failed(
                    Envelope.notFound("Resource class not found for " + normalized).toJson()
                ));
        }
        // An interface is kept here, unlike in the tools that answer about a class's own methods:
        // the whole point of an entry is that a binding decides what it becomes, and a caller may
        // well name one.
        boolean byDefault = className == null || className.isBlank();
        String name = byDefault ? defaultEntry(appNamespace) : className.trim();
        String via = byDefault ? "default" : "className";
        PhpIndex index = PhpIndex.getInstance(project);
        List<PhpClass> candidates = new ArrayList<>();
        if (name.indexOf('\\') >= 0) {
            candidates.addAll(index.getAnyByFQN(InterceptorBindingIndexUtil.normalizeFqn(name)));
        } else {
            // Both, because an entry is as likely to be the interface a module binds as the class
            // it binds to, and getClassesByName() answers for classes alone.
            candidates.addAll(index.getClassesByName(name));
            candidates.addAll(index.getInterfacesByName(name));
        }
        candidates.removeIf(candidate -> candidate.isTrait() || candidate.isEnum());
        if (candidates.isEmpty()) {
            return Entry.failed(Envelope.notFound(
                "default".equals(via)
                    ? "Not found: " + name + ", the class this context's application is built from. "
                        + "Name a className or uri to start from instead."
                    : "Class not found: " + name
            ).toJson());
        }
        if (candidates.size() > 1) {
            List<String> names = new ArrayList<>();
            candidates.forEach(candidate -> names.add(candidate.getFQN()));

            return Entry.failed(Envelope.ambiguous(names).toJson());
        }

        return Entry.of(candidates.get(0), via);
    }

    // ------------------------------------------------------------ container

    /**
     * The container as Ray.Di would have merged it: one binding per key, and the ones it beat.
     *
     * <p>Three rules decide it, all of them {@code Container::merge()} doing {@code $this->container
     * += $other} -- the receiving container keeps what it already has -- and {@code register()}
     * doing {@code $container[$index] = $bound}, a plain overwrite:
     *
     * <ul>
     *   <li>Two binds in one module: the LATER statement wins, because it overwrites.
     *   <li>A module's own bind against one from a module it installs: the module's OWN wins,
     *       whether the bind is written before the {@code install()} or after it. Before, the merge
     *       leaves it alone; after, it overwrites what the merge brought in.
     *   <li>Two installed modules: the one installed FIRST wins, because the second merge finds the
     *       key taken. Two installs of ONE module that binds an array it was handed are two of
     *       these, not the first case: each install is its own container.
     * </ul>
     *
     * <p>What is not modelled, in the same terms as the {@code override()} limit below: inside one
     * install of an array-binding module, a {@code bind()} chain written in the class body and an
     * entry of the array are one container in Ray.Di, resolved by which runs later in
     * {@code configure()}, while here the entries are held apart and the chain keeps the key. No
     * module in {@code bear/*} or {@code ray/*} writes both -- the one that binds an array binds
     * nothing else -- so this is reachable only by a module of one's own written that way.
     */
    private static final class Container {

        private final Map<String, Held> winners = new HashMap<>();
        private final Map<String, List<DiBindingLookupService.Bound>> shadowed = new HashMap<>();
        private int keysUndecidable;

        private record Held(DiBindingLookupService.Bound bound, String moduleFqn) {
        }

        static Container of(List<DiBindingLookupService.ModuleBindings> modules) {
            Container container = new Container();
            for (DiBindingLookupService.ModuleBindings module : inMergeOrder(modules)) {
                container.countUnreadableKeys(module.module().constants());
                for (DiBindingLookupService.Bound bound : module.bound()) {
                    container.add(bound, module.module().fqn());
                }
            }

            return container;
        }

        /**
         * What an install of an array-binding module binds that this could not file. Expanding the
         * array turns most of these into real keys, and what is left has to go on being counted:
         * an entry keyed by something other than a literal, and an install whose array the source
         * does not state at all, each bind a name that may be the very one a caller is asking
         * about. Left uncounted, they would make an "unbound" answer surer than the reading is.
         */
        private void countUnreadableKeys(@Nullable DiModuleTreeService.Constants constants) {
            if (constants == null) {
                return;
            }
            keysUndecidable += constants.keysUnreadable();
            if (constants.argumentUnreadable()) {
                keysUndecidable++;
            }
        }

        /**
         * The modules in the order their containers were merged, which is the order they were
         * walked with one correction. {@code override()} reverses the merge -- Ray.Di merges the
         * RECEIVER into the named module and then keeps the named module's container -- so the
         * module named in an {@code override()} call beats the one that named it, while the walk
         * reaches the receiver first.
         *
         * <p>What is not modelled: where the receiver's own binds sit relative to its
         * {@code override()} line. A bind written after it would beat the overriding module, and
         * here it does not. {@code demo-app/vendor} holds one {@code override()} inside a module --
         * {@code PackageModule}, which declares no bindings of its own.
         */
        private static List<DiBindingLookupService.ModuleBindings> inMergeOrder(
            List<DiBindingLookupService.ModuleBindings> modules
        ) {
            List<DiBindingLookupService.ModuleBindings> ordered = new ArrayList<>(modules);
            for (DiBindingLookupService.ModuleBindings module : modules) {
                String receiver = module.module().overriddenReceiver();
                if (receiver == null) {
                    continue;
                }
                int at = indexOf(ordered, module.module().fqn());
                int before = indexOf(ordered, receiver);
                if (at < 0 || before < 0 || before >= at) {
                    continue;
                }
                ordered.add(before, ordered.remove(at));
            }

            return ordered;
        }

        private static int indexOf(List<DiBindingLookupService.ModuleBindings> modules, String fqn) {
            for (int i = 0; i < modules.size(); i++) {
                if (fqn.equalsIgnoreCase(modules.get(i).module().fqn())) {
                    return i;
                }
            }

            return -1;
        }

        private void add(DiBindingLookupService.Bound bound, String moduleFqn) {
            String key = DiBindingLookupService.keyOf(bound.binding());
            if (key == null) {
                // A binding whose key the source does not state cannot be filed, and cannot be
                // said to bind nothing either. Counted so an unbound node says how sure that is.
                keysUndecidable++;

                return;
            }
            Held held = winners.get(key);
            if (held == null) {
                winners.put(key, new Held(bound, moduleFqn));

                return;
            }
            // The later of two binds written in one class of one module replaces the earlier: that
            // is the overwrite. Across classes or across modules the first one keeps the key,
            // because a merge only fills what is empty. A module installed with an argument it
            // binds from registers in a container of its own, so two install sites of one class are
            // two containers and merge rather than overwrite -- which is why the site is compared
            // and not only the class. Both are null for a bind() chain, leaving those unchanged.
            if (held.moduleFqn().equalsIgnoreCase(moduleFqn)
                && Objects.equals(held.bound().moduleClass(), bound.moduleClass())
                && Objects.equals(held.bound().fromInstall(), bound.fromInstall())) {
                winners.put(key, new Held(bound, moduleFqn));
                shadowed.computeIfAbsent(key, ignored -> new ArrayList<>()).add(held.bound());

                return;
            }
            shadowed.computeIfAbsent(key, ignored -> new ArrayList<>()).add(bound);
        }

        @Nullable
        DiBindingLookupService.Bound winner(String key) {
            Held held = winners.get(key);

            return held == null ? null : held.bound();
        }

        List<DiBindingLookupService.Bound> shadowed(String key) {
            return shadowed.getOrDefault(key, List.of());
        }
    }

    // ----------------------------------------------------------------- walk

    /** One injection point: where it is written, and the key it asks the container for. */
    private record Injection(
        String edge,
        @Nullable String method,
        String parameter,
        String key,
        String type,
        String name,
        boolean optional,
        boolean defaultAvailable,
        boolean qualifierUnreadable
    ) {
    }

    private final class Walk {

        private final Container container;
        private final Map<String, JsonObject> nodes = new LinkedHashMap<>();
        private final JsonArray edges = new JsonArray();
        private final Deque<String> path = new ArrayDeque<>();
        private boolean capped;
        private boolean deepened;
        private int qualifiersUnreadable;

        Walk(Container container) {
            this.container = container;
        }

        void run(Entry entry) {
            visit(entry.key(), entry.phpClass(), true, 0);
        }

        /**
         * @param key      the container key this node stands for
         * @param declared the class the key names, when the caller already resolved it
         * @param isEntry  whether this is the class the question was asked about, which is the only
         *                 place Ray.Di binds an unbound concrete class on the spot
         */
        private void visit(String key, @Nullable PhpClass declared, boolean isEntry, int depth) {
            ProgressManager.checkCanceled();
            if (nodes.containsKey(key)) {
                return;
            }
            if (nodes.size() >= MAX_NODES) {
                capped = true;

                return;
            }
            if (depth > MAX_DEPTH) {
                deepened = true;

                return;
            }

            JsonObject node = new JsonObject();
            nodes.put(key, node);
            String type = typeOf(key);
            String name = nameOf(key);
            node.addProperty("key", key);
            node.addProperty("type", type);
            if (!name.isEmpty()) {
                node.addProperty("name", name);
            }

            Resolved resolved = resolve(key, type, declared, isEntry, node);
            node.addProperty("resolution", resolved.resolution());
            if (resolved.implementation() != null) {
                node.addProperty("implementation", resolved.implementation());
            }
            if (resolved.phpClass() == null) {
                return;
            }

            path.push(key);
            for (Injection injection : injectionsOf(resolved.phpClass())) {
                JsonObject edge = new JsonObject();
                edge.addProperty("from", key);
                edge.addProperty("to", injection.key());
                edge.addProperty("kind", injection.edge());
                if (injection.method() != null) {
                    edge.addProperty("method", injection.method());
                }
                edge.addProperty("parameter", injection.parameter());
                if (injection.optional()) {
                    edge.addProperty("optional", true);
                }
                if (injection.defaultAvailable()) {
                    edge.addProperty("defaultAvailable", true);
                }
                if (injection.qualifierUnreadable()) {
                    qualifiersUnreadable++;
                    // The name half of the key is whatever a property held, so the key this edge
                    // leads to is a guess -- said so rather than followed as if it were read.
                    edge.addProperty("qualifierUnreadable", true);
                }
                if (path.contains(injection.key())) {
                    edge.addProperty("cycle", true);
                }
                edges.add(edge);
                visit(injection.key(), null, false, depth + 1);
            }
            path.pop();
        }

        /** What a key resolves to, and the class to walk on from, when there is one. */
        private record Resolved(String resolution, @Nullable String implementation, @Nullable PhpClass phpClass) {
        }

        private Resolved resolve(
            String key,
            String type,
            @Nullable PhpClass declared,
            boolean isEntry,
            JsonObject node
        ) {
            if (BUILT_IN.contains(key)) {
                return new Resolved(RESOLUTION_BUILT_IN, null, null);
            }
            DiBindingLookupService.Bound bound = container.winner(key);
            if (bound == null) {
                Resolved resolved = unbound(type, declared, isEntry);
                // "Nothing binds this" is only certain when every binding could be filed. A module
                // that binds in a loop -- NamedModule(['dsn' => ...]) is the common one -- states
                // its qualifier in a variable, and one of those may be this very key.
                if (RESOLUTION_UNBOUND.equals(resolved.resolution()) && container.keysUndecidable > 0) {
                    node.addProperty("keysUnreadable", container.keysUndecidable);
                }

                return resolved;
            }
            addBindingSite(node, bound);
            List<DiBindingLookupService.Bound> shadowed = container.shadowed(key);
            if (!shadowed.isEmpty()) {
                JsonArray json = new JsonArray();
                for (DiBindingLookupService.Bound loser : shadowed) {
                    json.add(shadowedJson(loser));
                }
                node.add("shadowedBy", json);
            }

            DiBindingLookupService.Binding binding = bound.binding();
            String boundBy = binding.boundBy();
            if (DiBindingLookupService.TO_INSTANCE.equals(boundBy)) {
                return new Resolved(RESOLUTION_INSTANCE, null, null);
            }
            if (DiBindingLookupService.TO_NULL.equals(boundBy)) {
                return new Resolved(RESOLUTION_NULL_OBJECT, null, null);
            }
            if (DiBindingLookupService.TO_PROVIDER.equals(boundBy)) {
                // The provider is built by the container like anything else, so its own
                // dependencies are part of this graph. What get() returns is not: only a running
                // provider knows that, which is the whole reason this binding form exists.
                PhpClass provider = classOf(binding.targetClass());

                return new Resolved(RESOLUTION_PROVIDER, binding.targetClass(), provider);
            }
            String implementation = binding.implementation();
            if (implementation == null) {
                return new Resolved(RESOLUTION_DYNAMIC, null, null);
            }
            PhpClass phpClass = classOf(implementation);

            return phpClass == null
                ? new Resolved(RESOLUTION_CLASS_UNRESOLVED, implementation, null)
                : new Resolved(RESOLUTION_STATIC, implementation, phpClass);
        }

        /**
         * A key nothing binds. Ray.Di answers this differently depending on where the key is asked
         * for: {@code Injector::getInstance()} catches {@code Untargeted} and binds a concrete class
         * on the spot, while {@code Arguments::getParameter()} -- every key below the entry -- lets
         * {@code Unbound} out. So an unbound dependency in the middle of a graph is not a gap in
         * this answer; it is what the application would throw.
         */
        private Resolved unbound(String type, @Nullable PhpClass declared, boolean isEntry) {
            if (type.isEmpty()) {
                // A key with no type names no class to build, so there is no class to fall back to
                // either: what Ray.Di does with it is throw, which is what unbound says. That the
                // key wants a value rather than an object is what its empty "type" already says.
                return new Resolved(RESOLUTION_UNBOUND, null, null);
            }
            PhpClass phpClass = declared != null ? declared : classOf(type);
            if (phpClass == null) {
                return new Resolved(RESOLUTION_CLASS_UNRESOLVED, null, null);
            }
            if (isEntry && !phpClass.isInterface() && !phpClass.isAbstract()) {
                return new Resolved(RESOLUTION_ENTRY_UNTARGETED, type, phpClass);
            }

            return new Resolved(RESOLUTION_UNBOUND, null, null);
        }

        private JsonArray nodesJson() {
            JsonArray json = new JsonArray();
            nodes.values().forEach(json::add);

            return json;
        }
    }

    // --------------------------------------------------------- injection points

    /**
     * Everything Ray.Di injects into a class: its constructor arguments, and the arguments of every
     * public method marked with an attribute that implements {@code InjectInterface}.
     *
     * <p>{@code AnnotatedClass::getNewInstance()} finds those methods through {@code
     * Ray\Aop\ReflectionClass::getMethods()}, which is {@code get_class_methods()}, so a setter a
     * TRAIT brings in counts -- and in BEAR that is the common case: {@code
     * BEAR\Sunday\Inject\ResourceInject} is a trait, and {@code ResourceObject} itself declares
     * {@code setRenderer()}, which puts a setter injection on the base class of every resource.
     */
    private List<Injection> injectionsOf(PhpClass phpClass) {
        List<Injection> injections = new ArrayList<>();
        Method constructor = PhpMembers.constructorOf(phpClass);
        if (constructor != null) {
            addParameters(injections, constructor, EDGE_CONSTRUCTOR, null, false);
        }
        for (Method method : PhpMembers.publicMethods(phpClass)) {
            PhpAttribute inject = injectAttribute(method);
            if (inject == null) {
                continue;
            }
            addParameters(injections, method, EDGE_SETTER, method.getName(), isOptional(inject));
        }

        return injections;
    }

    private void addParameters(
        List<Injection> injections,
        Method method,
        String edge,
        @Nullable String methodName,
        boolean optional
    ) {
        Parameter[] parameters = method.getParameters();
        // Ray.Di's own fallback for a method whose parameters carry no attribute of their own:
        // a single-parameter method may take its qualifier from an attribute on the METHOD.
        String fromMethod = methodQualifier(method, parameters);
        for (Parameter parameter : parameters) {
            String type = typeOf(parameter);
            Qualified qualified = qualifierOf(parameter);
            String name = qualified.name() != null ? qualified.name() : (fromMethod == null ? "" : fromMethod);
            injections.add(new Injection(
                edge,
                methodName,
                parameter.getName(),
                type + "-" + name,
                type,
                name,
                optional,
                parameter.isOptional(),
                qualified.unreadable()
            ));
        }
    }

    /**
     * The type half of the key, by {@code Argument::getType()}: a single named class type, and an
     * EMPTY string for everything else. A scalar names no class to bind; so does a union type,
     * which is no {@code ReflectionNamedType} at all. A nullable class type is still that class.
     */
    private static String typeOf(Parameter parameter) {
        PhpType declared = parameter.getDeclaredType();
        String only = null;
        for (String written : declared.getTypes()) {
            // A nullable type is still that type -- PHP's own reflection reports ?Foo as the named
            // type Foo -- so the mark is taken off before the type is read.
            String type = written.startsWith("?") ? written.substring(1) : written;
            if (type.isEmpty() || "null".equalsIgnoreCase(shortName(type))) {
                continue;
            }
            if (only != null) {
                // Two types left is a union, which is no ReflectionNamedType, which Ray.Di keys
                // under an empty type exactly as it keys a scalar.
                return "";
            }
            only = type;
        }
        // Compared on the last segment: PSI reports a scalar written inside a namespace as a class
        // OF that namespace ("\\MyVendor\\MyProject\\string"), and PHP reserves these words, so no
        // class can answer to one of them.
        if (only == null || UNBOUND_TYPE.contains(shortName(only).toLowerCase(Locale.ROOT))) {
            return "";
        }

        return only.startsWith("\\") ? only : "";
    }

    private static String shortName(String fqn) {
        int at = fqn.lastIndexOf('\\');

        return at < 0 ? fqn : fqn.substring(at + 1);
    }

    /** The qualifier a parameter carries, and whether the source states it at all. */
    private record Qualified(@Nullable String name, boolean unreadable) {
    }

    /**
     * The name half of the key, by {@code Name::withAttributes()}: the FIRST attribute a parameter
     * carries and no other. If that one is {@code #[Named]} the name is its value; if its class is
     * itself marked {@code #[Qualifier]} the name is that class; anything else names nothing --
     * which is why {@code #[SomeOther] #[Named('x')] $foo} is bound under no name at all.
     */
    private Qualified qualifierOf(Parameter parameter) {
        Collection<PhpAttribute> attributes = parameter.getAttributes();
        if (attributes.isEmpty()) {
            return new Qualified(null, false);
        }
        PhpAttribute first = attributes.iterator().next();
        String fqn = Attributes.fqn(first);
        if (fqn == null) {
            return new Qualified("", true);
        }
        if (NAMED.equalsIgnoreCase(fqn)) {
            String value = namedValue(first);

            return value == null ? new Qualified("", true) : new Qualified(value, false);
        }

        return isQualifier(fqn) ? new Qualified(fqn, false) : new Qualified("", false);
    }

    /**
     * {@code BcParameterQualifier}: a method with exactly one parameter, that parameter carrying no
     * qualifier of its own, may name the qualifier on the method instead. The attribute has to be
     * marked {@code #[Qualifier]}, and on a setter it has to be an {@code #[Inject]} as well --
     * {@code #[Named]} on a method is NOT one of these, because Ray.Di's {@code Named} carries no
     * {@code #[Qualifier]} mark.
     */
    @Nullable
    private String methodQualifier(Method method, Parameter[] parameters) {
        if (parameters.length != 1 || !parameters[0].getAttributes().isEmpty()) {
            return null;
        }
        boolean isConstructor = "__construct".equalsIgnoreCase(method.getName());
        for (PhpAttribute attribute : method.getAttributes()) {
            String fqn = Attributes.fqn(attribute);
            if (fqn == null || !isQualifier(fqn)) {
                continue;
            }
            if (isConstructor || isInject(fqn)) {
                return fqn;
            }
        }

        return null;
    }

    /**
     * The name {@code #[Named]} carries. Ray.Di reads the attribute's own {@code value}, and
     * {@code #[Named(ImportAppConfig::class)]} -- which is how {@code bear/package} names its
     * imported-app config -- puts a class name in that string as surely as a quoted literal does.
     * Reading only the literal would leave the name unread and the key half-guessed.
     */
    @Nullable
    private static String namedValue(PhpAttribute attribute) {
        ParameterList arguments = attribute.getParameterList();
        if (arguments == null || arguments.getParameters().length == 0) {
            return null;
        }
        PsiElement first = arguments.getParameters()[0];
        String literal = PhpSource.stringValue(first);

        return literal != null ? literal : PhpSource.classConstFqn(first);
    }

    /** The attribute that makes a method a setter Ray.Di injects through, read as it reads it. */
    @Nullable
    private PhpAttribute injectAttribute(Method method) {
        for (PhpAttribute attribute : method.getAttributes()) {
            String fqn = Attributes.fqn(attribute);
            if (fqn != null && isInject(fqn)) {
                return attribute;
            }
        }

        return null;
    }

    /** {@code #[Inject(optional: true)]}: a missing binding is skipped rather than thrown for. */
    private static boolean isOptional(PhpAttribute attribute) {
        String args = Attributes.argsText(attribute);

        return args != null && args.toLowerCase(Locale.ROOT).contains("true");
    }

    /**
     * Whether an attribute class is one Ray.Di reads as an injection point. Ray.Di asks with
     * {@code ReflectionAttribute::IS_INSTANCEOF}, so an attribute class that IMPLEMENTS
     * {@code InjectInterface} counts as surely as {@code #[Inject]} itself.
     */
    private boolean isInject(String fqn) {
        return implementsInterface(fqn, INJECT_INTERFACE);
    }

    /** Whether an attribute class is marked {@code #[Qualifier]}, which makes its name a name. */
    private boolean isQualifier(String fqn) {
        for (PhpClass phpClass : PhpIndex.getInstance(project).getAnyByFQN(fqn)) {
            for (PhpAttribute attribute : phpClass.getAttributes()) {
                if (QUALIFIER.equalsIgnoreCase(Attributes.fqn(attribute))) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean implementsInterface(String fqn, String wanted) {
        if (wanted.equalsIgnoreCase(fqn)) {
            return true;
        }
        PhpIndex index = PhpIndex.getInstance(project);
        List<String> queue = new ArrayList<>(List.of(fqn));
        List<String> seen = new ArrayList<>();
        while (!queue.isEmpty() && seen.size() < MAX_HIERARCHY) {
            String current = queue.remove(0);
            if (seen.contains(current.toLowerCase(Locale.ROOT))) {
                continue;
            }
            seen.add(current.toLowerCase(Locale.ROOT));
            for (PhpClass phpClass : index.getAnyByFQN(current)) {
                for (String supertype : phpClass.getInterfaceNames()) {
                    String normalized = InterceptorBindingIndexUtil.normalizeFqn(supertype);
                    if (normalized == null) {
                        continue;
                    }
                    if (wanted.equalsIgnoreCase(normalized)) {
                        return true;
                    }
                    queue.add(normalized);
                }
                PhpClass parent = phpClass.getSuperClass();
                if (parent != null && parent.getFQN() != null) {
                    queue.add(parent.getFQN());
                }
            }
        }

        return false;
    }

    @Nullable
    private PhpClass classOf(@Nullable String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return null;
        }
        for (PhpClass phpClass : PhpIndex.getInstance(project).getAnyByFQN(fqn)) {
            if (!phpClass.isTrait() && !phpClass.isEnum()) {
                return phpClass;
            }
        }

        return null;
    }

    // ------------------------------------------------------------------ json

    /** A key is {@code "{type}-{name}"}, and a type may hold no dash but a name may. */
    private static String typeOf(String key) {
        int at = key.indexOf('-');

        return at < 0 ? key : key.substring(0, at);
    }

    private static String nameOf(String key) {
        int at = key.indexOf('-');

        return at < 0 ? "" : key.substring(at + 1);
    }

    private static void addBindingSite(JsonObject node, DiBindingLookupService.Bound bound) {
        node.addProperty("boundBy", bound.binding().boundBy());
        if (bound.binding().scope() != null) {
            node.addProperty("scope", bound.binding().scope());
        }
        if (bound.moduleClass() != null) {
            node.addProperty("moduleClass", bound.moduleClass());
        }
        // The class the bind is written in and the file the line is counted in are the same file
        // for a chain, and two different files for an entry of an array a module was installed
        // with. Naming the installing module is what tells the reader which of the two this is.
        if (bound.installedBy() != null) {
            node.addProperty("installedBy", bound.installedBy());
        }
        node.addProperty("filePath", bound.filePath());
        if (bound.line() != null) {
            node.addProperty("line", bound.line());
        }
    }

    private static JsonObject shadowedJson(DiBindingLookupService.Bound loser) {
        JsonObject json = new JsonObject();
        if (loser.binding().implementation() != null) {
            json.addProperty("implementation", loser.binding().implementation());
        }
        json.addProperty("boundBy", loser.binding().boundBy());
        if (loser.moduleClass() != null) {
            json.addProperty("moduleClass", loser.moduleClass());
        }
        if (loser.installedBy() != null) {
            json.addProperty("installedBy", loser.installedBy());
        }
        json.addProperty("filePath", loser.filePath());
        if (loser.line() != null) {
            json.addProperty("line", loser.line());
        }

        return json;
    }

    private JsonObject scanJson(
        DiBindingLookupService.ContextBindings bindings,
        Walk walk,
        String context
    ) {
        JsonObject scan = new JsonObject();
        scan.addProperty("context", context);
        scan.addProperty("modules", bindings.walk().modules().size());
        scan.addProperty("files", bindings.filesRead());
        int bound = 0;
        for (DiBindingLookupService.ModuleBindings module : bindings.modules()) {
            bound += module.bound().size();
        }
        scan.addProperty("bindings", bound);
        scan.addProperty("nodes", walk.nodes.size());
        scan.addProperty("edges", walk.edges.size());
        // A key whose name half could not be read is a key this looked the wrong binding up under,
        // and the node it led to may be nobody's. Counted, because the edges carrying the mark are
        // easy to miss in a graph of this size.
        if (walk.qualifiersUnreadable > 0) {
            scan.addProperty("qualifiersUnreadable", walk.qualifiersUnreadable);
        }
        if (walk.capped) {
            scan.addProperty("nodesCapped", MAX_NODES);
        }
        if (walk.deepened) {
            scan.addProperty("depthCapped", MAX_DEPTH);
        }
        // A rename moves a binding to another key, and this version does not apply it: a graph that
        // said nothing about one would be confidently wrong about the key it moved.
        if (bindings.renames() > 0) {
            scan.addProperty("renamesNotApplied", bindings.renames());
        }
        if (walk.container.keysUndecidable > 0) {
            scan.addProperty("bindingsWithNoReadableKey", walk.container.keysUndecidable);
        }
        JsonArray segments = new JsonArray();
        for (JsonElement element : bindings.walk().unresolvedJson()) {
            segments.add(element.getAsJsonObject().get("segment").getAsString());
        }
        if (!segments.isEmpty()) {
            scan.add("unresolvedSegments", segments);
        }
        if (bindings.walk().classesUnresolved() > 0) {
            scan.addProperty("classesUnresolved", bindings.walk().classesUnresolved());
        }
        if (bindings.walk().installsUnreadable() > 0) {
            scan.addProperty("installsUnreadable", bindings.walk().installsUnreadable());
        }
        // A module that binds an array, installed with an array this could not read. Separate from
        // installsUnreadable, which is an install that names no module at all.
        if (bindings.walk().installArgumentsUnreadable() > 0) {
            scan.addProperty("installArgumentsUnreadable", bindings.walk().installArgumentsUnreadable());
        }
        if (bindings.walk().modulesSkipped() > 0) {
            scan.addProperty("modulesSkipped", bindings.walk().modulesSkipped());
        }
        if (bindings.walk().appNamespace() == null) {
            scan.addProperty("appNamespaceUnknown", true);
        }

        return scan;
    }
}
