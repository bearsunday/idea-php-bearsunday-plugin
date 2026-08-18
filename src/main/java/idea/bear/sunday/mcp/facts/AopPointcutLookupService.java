package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.PhpIndex;
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression;
import com.jetbrains.php.lang.psi.elements.ClassReference;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.MethodReference;
import com.jetbrains.php.lang.psi.elements.PhpAttribute;
import com.jetbrains.php.lang.psi.elements.PhpAttributesOwner;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.Variable;
import idea.bear.sunday.aop.InterceptorBindingIndexUtil;
import idea.bear.sunday.resource.ResourceClassResolver;
import idea.bear.sunday.util.UriUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Answers which Ray.Aop interceptors wrap a class's methods, and which pointcut puts each one
 * there. This is the question neither a text search nor the attribute gutter can answer: a
 * pointcut such as {@code bindInterceptor(annotatedWith(Cacheable::class),
 * startsWith('onPut'), [CommandInterceptor::class])} names the method it wraps by the spelling of
 * its name, so nothing at {@code onPut()} says that an interceptor runs around it.
 *
 * <p>Unlike the tools that report what a file states, this one evaluates: it decides whether a
 * matcher matches, which is a claim the source does not make in so many words. So the matchers it
 * evaluates are only the ones {@code Ray\Aop\Matcher} declares, evaluated as Ray.Aop's own matcher
 * classes evaluate them, and every other outcome -- an expression outside that vocabulary, a class
 * hierarchy the index cannot resolve, {@code subclassesOf} on the method side, which Ray.Aop
 * refuses -- is reported as undecided under {@code unevaluated} rather than settled by a guess.
 *
 * <p>What is NOT decided here: whether Ray.Di ever instantiates the class, which is what makes
 * weaving happen at all, and the order interceptors run in beyond Ray.Aop's own rule that priority
 * pointcuts come first.
 */
@Service(Service.Level.PROJECT)
public final class AopPointcutLookupService {

    private static final String BIND_INTERCEPTOR = "bindInterceptor";
    private static final String BIND_PRIORITY_INTERCEPTOR = "bindPriorityInterceptor";

    /**
     * The method names {@code any()} does not match, read from {@code AnyMatcher}: it excludes
     * every magic method (a {@code __} prefix) and every method of {@code ArrayObject}, which it
     * takes as the built-in ones. Harvested from PHP 8.5.7 with
     * {@code get_class_methods('ArrayObject')}; the magic ones in that list are covered by the
     * prefix rule already.
     */
    private static final Set<String> ARRAY_OBJECT_METHODS = Set.of(
        "offsetExists", "offsetGet", "offsetSet", "offsetUnset", "append", "getArrayCopy", "count",
        "getFlags", "setFlags", "asort", "ksort", "uasort", "uksort", "natsort", "natcasesort",
        "unserialize", "serialize", "getIterator", "exchangeArray", "setIteratorClass",
        "getIteratorClass"
    );

    private static final String REASON_UNREADABLE = "matcher-unreadable";
    private static final String REASON_HIERARCHY = "hierarchy-unresolved";
    private static final String REASON_INVALID = "matcher-invalid";
    private static final String REASON_BINDING = "binding-unreadable";

    /** Well past any real class hierarchy; guards a PSI cycle a half-typed file can produce. */
    private static final int MAX_HIERARCHY = 30;

    /** Well past any project's src; reached only by a root such as "vendor", and then reported. */
    private static final int MAX_FILES = 2000;

    private final Project project;

    public AopPointcutLookupService(Project project) {
        this.project = project;
    }

    public static AopPointcutLookupService getInstance(Project project) {
        return project.getService(AopPointcutLookupService.class);
    }

    public String lookup(
        @Nullable String className,
        @Nullable String uri,
        @Nullable String method,
        @Nullable String context,
        @Nullable String moduleRoot
    ) {
        // Non-blocking so a pending write action is not made to wait out the scan; cancelled and
        // retried instead. See DiBindingLookupService#lookup.
        return ReadAction.nonBlocking(() -> lookUpPointcuts(className, uri, method, context, moduleRoot))
            .executeSynchronously();
    }

    private String lookUpPointcuts(
        @Nullable String className,
        @Nullable String uri,
        @Nullable String method,
        @Nullable String context,
        @Nullable String moduleRoot
    ) {
        boolean hasContext = context != null && !context.isBlank();
        boolean hasRoot = moduleRoot != null && !moduleRoot.isBlank();
        if (hasContext && hasRoot) {
            return Envelope.notFound(
                "Pass either context or moduleRoot: a context names the modules to read, a root names the files."
            ).toJson();
        }
        // Neither is refused rather than defaulted, because almost every pointcut in a BEAR app is
        // declared in vendor -- bear/query-repository, bear/resource, ray/media-query -- so a scan
        // of the app's own src would answer "nothing wraps this method" for a method wrapped by
        // three interceptors, and answer it confidently.
        if (!hasContext && !hasRoot) {
            return Envelope.notFound(
                "Pass context (\"prod-hal-app\", recommended: it reads the modules that context installs) "
                    + "or moduleRoot (\"vendor/bear/query-repository/src\"). Most pointcuts are declared in "
                    + "vendor, so there is no default worth scanning."
            ).toJson();
        }

        try {
            Target target = resolveTarget(className, uri);
            if (target.error() != null) {
                return target.error();
            }
            String named = method == null || method.isBlank() ? null : method.trim();
            List<Method> methods = methodsOf(target.phpClass(), named);
            // A method that is not there is said so, rather than answered with an empty scan: the
            // two look alike in the answer, and only one of them means "nothing wraps it".
            if (named != null && methods.isEmpty()) {
                return Envelope.notFound(
                    "No public method " + named + " on " + target.phpClass().getFQN()
                        + "; Ray.Aop weaves the public methods a class has, __construct aside."
                ).toJson();
            }

            return hasContext
                ? inContext(target.phpClass(), methods, named, context.trim())
                : underRoot(target.phpClass(), methods, named, moduleRoot);
        } catch (IndexNotReadyException exception) {
            return Envelope.indexNotReady(
                "The project index is still building; the target class and its hierarchy cannot be resolved yet."
            ).toJson();
        }
    }

    /** The class asked about, by resource URI or by class name. */
    private Target resolveTarget(@Nullable String className, @Nullable String uri) {
        if (uri != null && !uri.isBlank()) {
            String normalized = UriUtil.normalizeSupportedResourceUri(uri.trim(), false);
            if (normalized == null) {
                return Target.of(Envelope.notFound("Unsupported resource URI: " + uri).toJson());
            }
            Optional<PhpClass> resolved = ResourceClassResolver.resolveCached(project, normalized);

            return resolved.map(Target::of)
                .orElseGet(() -> Target.of(Envelope.notFound("Resource class not found for " + normalized).toJson()));
        }
        if (className == null || className.isBlank()) {
            return Target.of(Envelope.notFound("Give className or uri: this answers for one class.").toJson());
        }

        String name = className.trim();
        PhpIndex index = PhpIndex.getInstance(project);
        List<PhpClass> candidates = name.indexOf('\\') >= 0
            ? new ArrayList<>(index.getClassesByFQN(InterceptorBindingIndexUtil.normalizeFqn(name)))
            : new ArrayList<>(index.getClassesByName(name));
        candidates.removeIf(candidate -> candidate.isInterface() || candidate.isTrait() || candidate.isEnum());
        if (candidates.isEmpty()) {
            return Target.of(Envelope.notFound("Class not found: " + name).toJson());
        }
        if (candidates.size() > 1) {
            List<String> names = new ArrayList<>();
            candidates.forEach(candidate -> names.add(candidate.getFQN()));

            return Target.of(Envelope.ambiguous(names).toJson());
        }

        return Target.of(candidates.get(0));
    }

    /** The pointcuts of the modules a context installs, in the tree's own priority order. */
    private String inContext(PhpClass target, List<Method> methods, @Nullable String method, String context) {
        DiModuleTreeService.Walk walk = DiModuleTreeService.getInstance(project).walk(context);
        Map<VirtualFile, Reach> reaches = new HashMap<>();
        Set<VirtualFile> files = new LinkedHashSet<>();
        for (DiModuleTreeService.WalkedModule module : walk.modules()) {
            for (VirtualFile file : module.files()) {
                // Walk order is priority order, so the first module to reach a file is the
                // strongest one that does, and that is the reach the file is read under.
                if (files.add(file)) {
                    reaches.put(file, new Reach(module.segment(), module.priority()));
                }
            }
        }

        Answer answer = read(files, reaches, target, methods, method);
        JsonObject scan = answer.scan();
        scan.addProperty("context", walk.context());
        scan.addProperty("modules", walk.modules().size());
        scan.addProperty("files", files.size());
        if (!walk.unresolvedJson().isEmpty()) {
            JsonArray segments = new JsonArray();
            walk.unresolvedJson().forEach(element -> segments.add(element.getAsJsonObject().get("segment").getAsString()));
            scan.add("unresolvedSegments", segments);
        }
        if (walk.classesUnresolved() > 0) {
            scan.addProperty("classesUnresolved", walk.classesUnresolved());
        }
        if (walk.installsUnreadable() > 0) {
            scan.addProperty("installsUnreadable", walk.installsUnreadable());
        }
        if (walk.modulesSkipped() > 0) {
            scan.addProperty("modulesSkipped", walk.modulesSkipped());
        }
        if (walk.appNamespace() == null) {
            scan.addProperty("appNamespaceUnknown", true);
        }

        return answer.toJson(project, target, Provenance.derived(walk.context(), walk.unsaved() || answer.unsaved));
    }

    /** The pointcuts declared under a directory, whichever context installs the modules. */
    private String underRoot(PhpClass target, List<Method> methods, @Nullable String method, String moduleRoot) {
        String root = FactsFiles.normalizeRoot(moduleRoot, DiBindingLookupService.DEFAULT_MODULE_ROOT);
        if (root == null) {
            return Envelope.notFound("Unsupported module root: " + moduleRoot).toJson();
        }
        VirtualFile rootDir = FactsFiles.find(project, root);
        if (rootDir == null || !rootDir.isDirectory()) {
            return Envelope.notFound("Module root not found: " + root).toJson();
        }

        List<VirtualFile> found = FactsFiles.phpFilesUnder(rootDir);
        List<VirtualFile> files = found.size() <= MAX_FILES ? found : found.subList(0, MAX_FILES);
        Answer answer = read(new LinkedHashSet<>(files), Map.of(), target, methods, method);
        JsonObject scan = answer.scan();
        scan.addProperty("moduleRoot", root);
        scan.addProperty("files", files.size());
        if (found.size() > files.size()) {
            scan.addProperty("filesSkipped", found.size() - files.size());
        }

        return answer.toJson(project, target, Provenance.derived(root, answer.unsaved));
    }

    /**
     * Reads every pointcut in the given files and decides it against the target. The class side is
     * decided once per pointcut, because the class is fixed; only a pointcut whose class side
     * matches is then decided against each method.
     */
    private Answer read(
        Set<VirtualFile> files,
        Map<VirtualFile, Reach> reaches,
        PhpClass target,
        List<Method> methods,
        @Nullable String method
    ) {
        Answer answer = new Answer();
        answer.methodsExamined = methods.size();
        // The answer is read from the target's own file as much as from the modules': its
        // attributes decide every annotatedWith, its name every startsWith, and its methods are
        // the list. An unsaved edit there changes the answer, so it counts towards freshness --
        // the modules alone would report an answer read from a buffer as one read from disk.
        answer.unsaved |= isUnsaved(target.getContainingFile());
        for (Method candidate : methods) {
            answer.unsaved |= isUnsaved(candidate.getContainingFile());
        }
        // A method the question names is in the answer whether or not anything wraps it: asked
        // about one method, an empty list of methods reads as "the scan found nothing at all".
        if (method != null) {
            methods.forEach(answer::examines);
        }

        List<Pointcut> pointcuts = new ArrayList<>();
        for (VirtualFile file : files) {
            answer.unsaved |= FactsFiles.isUnsaved(file);
            pointcuts.addAll(readPointcuts(file, reaches.get(file)));
        }
        answer.pointcuts = pointcuts.size();
        // Ray.Aop binds every priority pointcut before any other (MethodMatch), so they are the
        // interceptors that run outermost, and the answer lists them in that order.
        pointcuts.sort((left, right) -> Boolean.compare(right.priority(), left.priority()));

        Hierarchy hierarchy = new Hierarchy(project);
        for (Pointcut pointcut : pointcuts) {
            ProgressManager.checkCanceled();
            // A bind call whose own shape this could not read says nothing about what it wraps,
            // and there is no matcher to evaluate; it is reported rather than dropped.
            if (pointcut.unreadable() != null) {
                answer.undecided(pointcut, null, pointcut.unreadable());

                continue;
            }
            // An expression this could not read is left to the evaluator rather than refused ahead
            // of it: logicalOr(startsWith('onGet'), <unreadable>) matches onGet() whatever the
            // unreadable half means, and logicalAnd with a half that does not match cannot match
            // either. Refusing the whole pointcut would hide answers its readable half settles.
            Verdict onClass = matchesClass(pointcut.classMatcher(), target, hierarchy);
            if (onClass.match() == Match.NO) {
                continue;
            }
            if (onClass.match() == Match.UNKNOWN) {
                answer.undecided(pointcut, null, onClass.reason());

                continue;
            }
            for (Method candidate : methods) {
                Verdict onMethod = matchesMethod(pointcut.methodMatcher(), candidate, hierarchy);
                if (onMethod.match() == Match.YES) {
                    answer.wraps(candidate, pointcut);
                } else if (onMethod.match() == Match.UNKNOWN) {
                    answer.undecided(pointcut, candidate.getName(), onMethod.reason());
                }
            }
        }

        return answer;
    }

    /** Whether the file an element was read from carries an edit the disk does not have yet. */
    private static boolean isUnsaved(@Nullable PsiFile file) {
        VirtualFile virtualFile = file == null ? null : file.getVirtualFile();

        return virtualFile != null && FactsFiles.isUnsaved(virtualFile);
    }

    /**
     * The methods a pointcut can reach: the public ones, {@code __construct} excluded, exactly as
     * {@code Ray\Aop\Bind} picks them. {@code Ray\Aop\ReflectionClass::getMethods()} lists them
     * with {@code get_class_methods()}, so inherited methods are there -- and so are the ones a
     * trait brings in, which PHP treats as declared by the class that uses it. A method named in
     * the question is answered for on its own, so that "nothing wraps it" is an answer about the
     * method asked about.
     */
    private static List<Method> methodsOf(PhpClass target, @Nullable String named) {
        List<Method> methods = new ArrayList<>();
        // PHP compares method names case-insensitively, so a question about "onget" is a question
        // about onGet().
        for (Method method : PhpMembers.publicMethods(target)) {
            if (named == null || named.equalsIgnoreCase(method.getName())) {
                methods.add(method);
            }
        }

        return methods;
    }

    private static List<PhpClass> traitsOf(PhpClass phpClass) {
        return PhpMembers.traitsOf(phpClass);
    }

    /** Every {@code bindInterceptor()}/{@code bindPriorityInterceptor()} call written in a file. */
    private List<Pointcut> readPointcuts(VirtualFile file, @Nullable Reach reach) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile == null) {
            return List.of();
        }
        String path = FactsFiles.relativePath(project, file);
        List<Pointcut> pointcuts = new ArrayList<>();
        for (MethodReference call : PsiTreeUtil.findChildrenOfType(psiFile, MethodReference.class)) {
            ProgressManager.checkCanceled();
            String name = call.getName();
            boolean priority = BIND_PRIORITY_INTERCEPTOR.equalsIgnoreCase(name);
            if (!priority && !BIND_INTERCEPTOR.equalsIgnoreCase(name)) {
                continue;
            }
            Integer line = FactsFiles.lineOf(psiFile, call.getTextOffset());
            PsiElement[] arguments = new PsiElementArguments(call).rayDiArguments();
            // A call written in a shape this cannot read is carried as undecided rather than
            // dropped: whatever it binds may be what wraps the method, and a scan that drops it
            // answers "nothing wraps this" without having read the pointcut that does.
            if (arguments == null) {
                PointcutMatcher whole = new PointcutMatcher.Unreadable(
                    PhpSource.oneLine(call, PointcutMatcher.MAX_TEXT)
                );
                pointcuts.add(new Pointcut(
                    whole, whole, Interceptors.NONE, priority, REASON_BINDING,
                    PhpSource.enclosingClassFqn(call), path, line, reach
                ));

                continue;
            }
            pointcuts.add(new Pointcut(
                PointcutMatcher.parse(arguments[0]),
                PointcutMatcher.parse(arguments[1]),
                interceptors(arguments[2]),
                priority,
                null,
                PhpSource.enclosingClassFqn(call),
                path,
                line,
                reach
            ));
        }

        return pointcuts;
    }

    /**
     * The interceptor classes an array argument names, in the order they are written, and how many
     * of its elements this could not read. Each element is read on its own rather than searched
     * through: {@code Foo::class} inside {@code new Wrapper(Foo::class)} names an argument, not an
     * interceptor. An element that names no class is counted, because a list reported without it
     * says the method is wrapped by fewer interceptors than it is.
     */
    private static Interceptors interceptors(@Nullable PsiElement argument) {
        if (!(argument instanceof ArrayCreationExpression array)) {
            // A list this cannot see into -- a variable, a call -- still binds what it holds.
            return new Interceptors(List.of(), 1);
        }
        List<String> classes = new ArrayList<>();
        int unreadable = 0;
        for (PsiElement element : array.getChildren()) {
            String fqn = PhpSource.classConstFqn(element.getFirstChild());
            if (fqn == null) {
                unreadable++;
            } else if (!classes.contains(fqn)) {
                classes.add(fqn);
            }
        }

        return new Interceptors(classes, unreadable);
    }

    // ---- evaluation ------------------------------------------------------------------------

    /** Ray.Aop's {@code matchesClass}, with a third answer for what it could not decide. */
    private static Verdict matchesClass(PointcutMatcher matcher, PhpClass target, Hierarchy hierarchy) {
        return switch (matcher) {
            case PointcutMatcher.Any ignored -> Verdict.YES;
            case PointcutMatcher.StartsWith(String prefix) -> Verdict.of(startsWith(target.getFQN(), prefix));
            case PointcutMatcher.SubclassesOf(String superClass) -> hierarchy.isSubclassOf(target, superClass);
            case PointcutMatcher.AnnotatedWith(String attribute) -> hierarchy.hasAttribute(target, attribute);
            case PointcutMatcher.Or(PointcutMatcher left, PointcutMatcher right) ->
                Verdict.or(matchesClass(left, target, hierarchy), matchesClass(right, target, hierarchy));
            case PointcutMatcher.And(PointcutMatcher left, PointcutMatcher right) ->
                Verdict.and(matchesClass(left, target, hierarchy), matchesClass(right, target, hierarchy));
            case PointcutMatcher.Not(PointcutMatcher inner) -> matchesClass(inner, target, hierarchy).negated();
            case PointcutMatcher.Unreadable ignored -> Verdict.unknown(REASON_UNREADABLE);
        };
    }

    /** Ray.Aop's {@code matchesMethod}, with the same third answer. */
    private static Verdict matchesMethod(PointcutMatcher matcher, Method method, Hierarchy hierarchy) {
        return switch (matcher) {
            // AnyMatcher excludes magic methods and the ones it takes as built-in, so any() is not
            // quite "every method" -- and a caller asking about offsetGet() would be told it is.
            case PointcutMatcher.Any ignored -> Verdict.of(!isExcludedFromAny(method.getName()));
            case PointcutMatcher.StartsWith(String prefix) -> Verdict.of(method.getName().startsWith(prefix));
            case PointcutMatcher.AnnotatedWith(String attribute) -> hierarchy.hasAttribute(method, attribute);
            // Ray.Aop throws InvalidAnnotationException here rather than answering, so the pointcut
            // is not one this can decide -- and saying so names a fault in the module.
            case PointcutMatcher.SubclassesOf ignored -> Verdict.unknown(REASON_INVALID);
            case PointcutMatcher.Or(PointcutMatcher left, PointcutMatcher right) ->
                Verdict.or(matchesMethod(left, method, hierarchy), matchesMethod(right, method, hierarchy));
            case PointcutMatcher.And(PointcutMatcher left, PointcutMatcher right) ->
                Verdict.and(matchesMethod(left, method, hierarchy), matchesMethod(right, method, hierarchy));
            case PointcutMatcher.Not(PointcutMatcher inner) -> matchesMethod(inner, method, hierarchy).negated();
            case PointcutMatcher.Unreadable ignored -> Verdict.unknown(REASON_UNREADABLE);
        };
    }

    /**
     * {@code str_starts_with($class->name, $prefix)}. Reflection's name carries no leading
     * backslash where the PSI's FQN does, so the name is compared in reflection's spelling -- and
     * the prefix exactly as the module writes it, because that is the string Ray.Aop compares.
     * A prefix written {@code '\Foo'} matches nothing at runtime, and so matches nothing here.
     */
    private static boolean startsWith(@Nullable String name, String prefix) {
        return name != null && unqualified(name).startsWith(prefix);
    }

    private static String unqualified(String fqn) {
        return fqn.startsWith("\\") ? fqn.substring(1) : fqn;
    }

    private static boolean isExcludedFromAny(String method) {
        return method.startsWith("__") || ARRAY_OBJECT_METHODS.contains(method);
    }

    /**
     * The hierarchy questions one scan asks, answered once each. Every {@code annotatedWith} on the
     * method side asks whether an attribute class extends another, once per pointcut per method
     * examined, and the pairs are few and unchanging while a read action runs. One of these belongs
     * to one scan: it holds PSI, which must not outlive the read action that produced it.
     */
    private static final class Hierarchy {

        private final PhpIndex index;
        private final Map<String, Verdict> attributeVerdicts = new HashMap<>();

        Hierarchy(Project project) {
            this.index = PhpIndex.getInstance(project);
        }

        /**
         * {@code $class->isSubclassOf($superClass) || $class->name === $superClass}, walked over
         * the classes and interfaces the source names. Answering no is a claim about a whole
         * hierarchy, so a link the index cannot resolve, or one larger than this walks, answers
         * undecided instead: the part left unwalked may be the one that extends it.
         *
         * <p>Walked here rather than with {@code PhpClassHierarchyUtils} for two reasons the
         * platform cannot give: its processors do not report that a reference went unresolved,
         * which is the whole difference between "no" and "cannot tell" here, and
         * {@code processSupers} counts traits and mixins among the supers, which
         * {@code ReflectionClass::isSubclassOf} does not.
         */
        Verdict isSubclassOf(PhpClass phpClass, String superClass) {
            Set<String> seen = new HashSet<>();
            Deque<PhpClass> queue = new ArrayDeque<>(List.of(phpClass));
            boolean whole = true;
            while (!queue.isEmpty()) {
                // Stopped with classes still to walk: one of them may be the one that extends it.
                if (seen.size() >= MAX_HIERARCHY) {
                    return Verdict.unknown(REASON_HIERARCHY);
                }
                PhpClass current = queue.remove();
                String fqn = current.getFQN();
                if (fqn == null || !seen.add(fqn.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (superClass.equalsIgnoreCase(fqn)) {
                    return Verdict.YES;
                }
                for (ClassReference reference : supertypes(current)) {
                    Collection<PhpClass> resolved = resolve(reference);
                    if (resolved.isEmpty()) {
                        whole = false;

                        continue;
                    }
                    queue.addAll(resolved);
                }
            }

            return whole ? Verdict.NO : Verdict.unknown(REASON_HIERARCHY);
        }

        /**
         * The classes and interfaces a declaration names above itself. Both lists are read, and
         * read as written rather than through {@code getSuperClass()}: PHP answers
         * {@code isSubclassOf} for an implemented interface as surely as for a base class, and an
         * interface names its parents in its extends list, where {@code getSuperClass()} has
         * nothing to return.
         */
        private static List<ClassReference> supertypes(PhpClass phpClass) {
            List<ClassReference> references = new ArrayList<>(phpClass.getExtendsList().getReferenceElements());
            references.addAll(phpClass.getImplementsList().getReferenceElements());

            return references;
        }

        private Collection<PhpClass> resolve(ClassReference reference) {
            String fqn = reference.getFQN();

            return fqn == null || fqn.isBlank() ? List.of() : index.getAnyByFQN(fqn);
        }

        /**
         * Ray.Aop reads attributes with {@code ReflectionAttribute::IS_INSTANCEOF}, so
         * {@code annotatedWith(AbstractCacheControl::class)} matches an attribute that EXTENDS the
         * named class as surely as the class itself -- which is how {@code bear/query-repository}
         * binds its cache-control interceptor. Reading only exact names would miss those silently.
         *
         * <p>Attributes are read as declared: PHP reflection does not inherit a class attribute
         * from a parent class, so neither does this.
         */
        Verdict hasAttribute(PhpAttributesOwner owner, String attribute) {
            boolean whole = true;
            for (PhpAttribute declared : owner.getAttributes()) {
                String fqn = Attributes.fqn(declared);
                if (fqn == null) {
                    whole = false;

                    continue;
                }
                if (attribute.equalsIgnoreCase(fqn)) {
                    return Verdict.YES;
                }
                Verdict isA = attributeIsA(fqn, attribute);
                if (isA.match() == Match.YES) {
                    return Verdict.YES;
                }
                whole &= isA.match() == Match.NO;
            }

            return whole ? Verdict.NO : Verdict.unknown(REASON_HIERARCHY);
        }

        /** Whether an attribute class extends the one a matcher names. */
        private Verdict attributeIsA(String declared, String attribute) {
            return attributeVerdicts.computeIfAbsent(declared + '\u0000' + attribute, ignored -> {
                boolean resolved = false;
                for (PhpClass phpClass : index.getClassesByFQN(declared)) {
                    resolved = true;
                    Verdict verdict = isSubclassOf(phpClass, attribute);
                    if (verdict.match() != Match.NO) {
                        return verdict;
                    }
                }

                // An attribute class the index cannot resolve may or may not extend the named one,
                // and answering no would drop a pointcut that really does wrap the method.
                return resolved ? Verdict.NO : Verdict.unknown(REASON_HIERARCHY);
            });
        }
    }

    // ---- model -----------------------------------------------------------------------------

    /** Whether a matcher matches, or that this could not decide -- Ray.Aop only knows the first two. */
    private enum Match {
        YES,
        NO,
        UNKNOWN
    }

    /** A match with, when it is undecided, the reason it could not be decided. */
    private record Verdict(Match match, @Nullable String reason) {

        private static final Verdict YES = new Verdict(Match.YES, null);
        private static final Verdict NO = new Verdict(Match.NO, null);

        static Verdict of(boolean matched) {
            return matched ? YES : NO;
        }

        static Verdict unknown(String reason) {
            return new Verdict(Match.UNKNOWN, reason);
        }

        /** True when either side is true, even if the other could not be decided. */
        static Verdict or(Verdict left, Verdict right) {
            if (left.match == Match.YES || right.match == Match.YES) {
                return YES;
            }

            return left.match == Match.NO && right.match == Match.NO ? NO : undecidedOf(left, right);
        }

        /** False when either side is false, even if the other could not be decided. */
        static Verdict and(Verdict left, Verdict right) {
            if (left.match == Match.NO || right.match == Match.NO) {
                return NO;
            }

            return left.match == Match.YES && right.match == Match.YES ? YES : undecidedOf(left, right);
        }

        private static Verdict undecidedOf(Verdict left, Verdict right) {
            return unknown(left.reason != null ? left.reason : String.valueOf(right.reason));
        }

        Verdict negated() {
            return switch (match) {
                case YES -> NO;
                case NO -> YES;
                case UNKNOWN -> this;
            };
        }
    }

    /** Which context segment reached the module a pointcut is declared in. */
    private record Reach(@Nullable String segment, int priority) {
    }

    /**
     * One {@code bindInterceptor()} call, read whole -- or, when {@code unreadable} names a reason,
     * one this could not read as Ray.Di's three arguments at all.
     */
    private record Pointcut(
        PointcutMatcher classMatcher,
        PointcutMatcher methodMatcher,
        Interceptors interceptors,
        boolean priority,
        @Nullable String unreadable,
        @Nullable String moduleClass,
        String filePath,
        @Nullable Integer line,
        @Nullable Reach reach
    ) {
    }

    /** The interceptors a pointcut names, and how many of the array's elements were not one. */
    private record Interceptors(List<String> classes, int unreadable) {

        private static final Interceptors NONE = new Interceptors(List.of(), 0);
    }

    /** The class the question is about, or the envelope that says why there is none. */
    private record Target(@Nullable PhpClass phpClass, @Nullable String error) {

        static Target of(PhpClass phpClass) {
            return new Target(phpClass, null);
        }

        static Target of(String error) {
            return new Target(null, error);
        }
    }

    /** The three parameters Ray.Di declares, read off a call that may or may not be Ray.Di's. */
    private record PsiElementArguments(MethodReference call) {

        /** The parameters {@code AbstractModule} declares, in the order it declares them. */
        private static final List<String> PARAMETERS = List.of("classMatcher", "methodMatcher", "interceptors");

        /** A module reaches an inherited method through one of these, and through nothing else. */
        private static final Set<String> RECEIVERS = Set.of("parent", "self", "static");

        private static final String SPREAD = "...";

        /**
         * Ray.Di's three arguments in Ray.Di's own order, or {@code null} when the call does not
         * state them in a shape this can read. PHP 8 lets them be written by name -- and
         * {@code bindInterceptor(methodMatcher: ..., classMatcher: ...)} binds the same pointcut as
         * the positional form -- so they are put in order by the names they are written under.
         * Reading those by position would apply each matcher to the wrong side of the pointcut and
         * report the result with full confidence.
         */
        @Nullable
        PsiElement[] rayDiArguments() {
            if (!isRayDiCall()) {
                return null;
            }
            PsiElement[] parameters = call.getParameters();
            if (parameters.length != PARAMETERS.size()) {
                return null;
            }
            PsiElement[] ordered = new PsiElement[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                String name = argumentName(parameters[i]);
                int slot = name == null ? i : PARAMETERS.indexOf(name);
                if (slot < 0 || ordered[slot] != null) {
                    return null;
                }
                ordered[slot] = parameters[i];
            }

            return ordered;
        }

        /**
         * Whether the call is Ray.Di's own. Both methods are declared on {@code AbstractModule},
         * which a module reaches through {@code $this} -- or through parent/self/static where it
         * overrides one.
         */
        private boolean isRayDiCall() {
            PsiElement receiver = call.getClassReference();
            if (receiver instanceof Variable variable) {
                return "this".equals(variable.getName());
            }

            return receiver instanceof ClassReference reference
                && reference.getName() != null
                && RECEIVERS.contains(reference.getName().toLowerCase(Locale.ROOT));
        }

        /**
         * The name a PHP 8 named argument is written under, or {@code null} for a positional one.
         * The name and its colon are the parameter list's own children, sitting just before the
         * argument. A spread is answered with its own spelling, which names no parameter Ray.Di
         * declares: the array it unpacks holds arguments this cannot count, let alone order.
         */
        @Nullable
        private static String argumentName(PsiElement parameter) {
            PsiElement previous = PsiTreeUtil.skipWhitespacesBackward(parameter);
            if (previous == null) {
                return null;
            }
            String text = previous.getText();
            if (SPREAD.equals(text)) {
                return SPREAD;
            }
            if (!":".equals(text)) {
                return null;
            }
            PsiElement identifier = PsiTreeUtil.skipWhitespacesBackward(previous);

            return identifier == null ? null : identifier.getText();
        }
    }

    /** The answer being built: which interceptors wrap which method, and what stayed undecided. */
    private static final class Answer {

        private final Map<String, JsonArray> byMethod = new LinkedHashMap<>();
        private final Map<String, Method> methods = new LinkedHashMap<>();
        private final Map<String, Undecided> unevaluated = new LinkedHashMap<>();
        private final JsonObject scan = new JsonObject();
        private int pointcuts;
        private int methodsExamined;
        private boolean unsaved;

        JsonObject scan() {
            return scan;
        }

        /** Puts a method in the answer before anything is known to wrap it. */
        void examines(Method method) {
            methods.putIfAbsent(method.getName(), method);
            byMethod.computeIfAbsent(method.getName(), key -> new JsonArray());
        }

        void wraps(Method method, Pointcut pointcut) {
            String name = method.getName();
            methods.putIfAbsent(name, method);
            JsonArray interceptors = byMethod.computeIfAbsent(name, key -> new JsonArray());
            for (String interceptor : pointcut.interceptors().classes()) {
                interceptors.add(interceptorJson(interceptor, pointcut));
            }
            // An interceptor this could not read still wraps the method, and listing only the ones
            // it could read would report the method as wrapped by fewer than it is. The count is
            // the claim: an array of three with one unreadable is not an array of two.
            if (pointcut.interceptors().unreadable() > 0) {
                JsonObject json = new JsonObject();
                json.addProperty("interceptorsUnreadable", pointcut.interceptors().unreadable());
                addPointcut(json, pointcut);
                interceptors.add(json);
            }
        }

        /**
         * Keeps one entry per pointcut and reason. A matcher this cannot read cannot be read for
         * any method either, so the same pointcut would otherwise be reported once per method
         * examined -- and {@code Ray\Di\AssistedInjectModule}, which every context installs, binds
         * a hand-written matcher, so a twenty-method resource would carry twenty copies of it and
         * bury the entries about the caller's own code.
         */
        void undecided(Pointcut pointcut, @Nullable String method, @Nullable String reason) {
            String key = reason + '\u0000' + pointcut.filePath() + '\u0000' + pointcut.line()
                + '\u0000' + pointcut.classMatcher().text() + '\u0000' + pointcut.methodMatcher().text();
            unevaluated.computeIfAbsent(key, ignored -> new Undecided(pointcut, reason, method)).methods++;
        }

        private static JsonObject interceptorJson(String interceptor, Pointcut pointcut) {
            JsonObject json = new JsonObject();
            json.addProperty("interceptor", interceptor);
            json.addProperty("priority", pointcut.priority());
            addPointcut(json, pointcut);

            return json;
        }

        private static void addPointcut(JsonObject json, Pointcut pointcut) {
            json.addProperty("classMatcher", pointcut.classMatcher().text());
            json.addProperty("methodMatcher", pointcut.methodMatcher().text());
            if (pointcut.moduleClass() != null) {
                json.addProperty("moduleClass", pointcut.moduleClass());
            }
            json.addProperty("filePath", pointcut.filePath());
            if (pointcut.line() != null) {
                json.addProperty("line", pointcut.line());
            }
            Reach reach = pointcut.reach();
            if (reach == null) {
                return;
            }
            if (reach.segment() != null) {
                json.addProperty("segment", reach.segment());
            }
            json.addProperty("modulePriority", reach.priority());
        }

        String toJson(Project project, PhpClass target, Provenance provenance) {
            scan.addProperty("pointcuts", pointcuts);
            scan.addProperty("methodsExamined", methodsExamined);

            JsonObject targetJson = new JsonObject();
            targetJson.addProperty("class", target.getFQN());
            PsiFile file = target.getContainingFile();
            VirtualFile virtualFile = file == null ? null : file.getVirtualFile();
            if (virtualFile != null) {
                targetJson.addProperty("filePath", FactsFiles.relativePath(project, virtualFile));
            }
            JsonArray attributes = new JsonArray();
            for (PhpAttribute attribute : target.getAttributes()) {
                String fqn = Attributes.fqn(attribute);
                if (fqn != null) {
                    attributes.add(fqn);
                }
            }
            if (!attributes.isEmpty()) {
                targetJson.add("attributes", attributes);
            }

            JsonArray methodsJson = new JsonArray();
            for (Map.Entry<String, JsonArray> entry : byMethod.entrySet()) {
                JsonObject json = new JsonObject();
                json.addProperty("method", entry.getKey());
                Method method = methods.get(entry.getKey());
                PsiFile methodFile = method.getContainingFile();
                Integer line = FactsFiles.lineOf(methodFile, method.getTextOffset());
                PhpClass declaring = method.getContainingClass();
                String declaringFqn = declaring == null ? null : declaring.getFQN();
                // A method a base class declares is wrapped where it is declared, not where it was
                // asked about, and pairing the target's file with this line would point elsewhere.
                if (declaringFqn != null && !declaringFqn.equalsIgnoreCase(target.getFQN())) {
                    json.addProperty("declaredIn", declaringFqn);
                }
                if (line != null) {
                    json.addProperty("line", line);
                }
                json.add("interceptors", entry.getValue());
                methodsJson.add(json);
            }

            JsonArray unevaluatedJson = new JsonArray();
            for (Undecided entry : unevaluated.values()) {
                unevaluatedJson.add(entry.toJson());
            }

            JsonObject payload = new JsonObject();
            payload.add("scan", scan);
            payload.add("target", targetJson);
            payload.add("methods", methodsJson);
            payload.add("unevaluated", unevaluatedJson);

            return Envelope.ok(provenance, payload).toJson();
        }
    }

    /** One pointcut that could not be decided, and how many methods it could not be decided for. */
    private static final class Undecided {

        private final Pointcut pointcut;
        private final String reason;
        @Nullable private final String method;
        private int methods;

        Undecided(Pointcut pointcut, @Nullable String reason, @Nullable String method) {
            this.pointcut = pointcut;
            this.reason = reason == null ? REASON_UNREADABLE : reason;
            this.method = method;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("reason", reason);
            if (method != null) {
                // One method by name; more than one by count alone, because they are the methods
                // the answer already lists -- the pointcut could be read for none of them.
                if (methods == 1) {
                    json.addProperty("method", method);
                } else {
                    json.addProperty("methodsAffected", methods);
                }
            }
            Answer.addPointcut(json, pointcut);

            return json;
        }
    }
}
