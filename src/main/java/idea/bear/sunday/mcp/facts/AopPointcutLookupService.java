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
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.PhpIndex;
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression;
import com.jetbrains.php.lang.psi.elements.ClassConstantReference;
import com.jetbrains.php.lang.psi.elements.ClassReference;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.MethodReference;
import com.jetbrains.php.lang.psi.elements.PhpAttribute;
import com.jetbrains.php.lang.psi.elements.PhpAttributesOwner;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.PhpModifier;
import com.jetbrains.php.lang.psi.elements.Variable;
import idea.bear.sunday.aop.InterceptorBindingIndexUtil;
import idea.bear.sunday.resource.ResourceClassResolver;
import idea.bear.sunday.util.UriUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
    private static final String CONSTRUCT = "__construct";

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
            // A method that is not there is said so, rather than answered with an empty scan: the
            // two look alike in the answer, and only one of them means "nothing wraps it".
            if (named != null && methodsOf(target.phpClass(), named).isEmpty()) {
                return Envelope.notFound(
                    "No public method " + named + " on " + target.phpClass().getFQN()
                        + "; Ray.Aop weaves the public methods a class has, __construct aside."
                ).toJson();
            }

            return hasContext
                ? inContext(target.phpClass(), named, context.trim())
                : underRoot(target.phpClass(), named, moduleRoot);
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
    private String inContext(PhpClass target, @Nullable String method, String context) {
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

        Answer answer = read(files, reaches, target, method);
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
    private String underRoot(PhpClass target, @Nullable String method, String moduleRoot) {
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
        Answer answer = read(new LinkedHashSet<>(files), Map.of(), target, method);
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
    private Answer read(Set<VirtualFile> files, Map<VirtualFile, Reach> reaches, PhpClass target, @Nullable String method) {
        Answer answer = new Answer();
        List<Method> methods = methodsOf(target, method);
        answer.methodsExamined = methods.size();
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

        for (Pointcut pointcut : pointcuts) {
            ProgressManager.checkCanceled();
            // An expression this could not read is left to the evaluator rather than refused ahead
            // of it: logicalOr(startsWith('onGet'), <unreadable>) matches onGet() whatever the
            // unreadable half means, and logicalAnd with a half that does not match cannot match
            // either. Refusing the whole pointcut would hide answers its readable half settles.
            Verdict onClass = matchesClass(pointcut.classMatcher(), target);
            if (onClass.match() == Match.NO) {
                continue;
            }
            if (onClass.match() == Match.UNKNOWN) {
                answer.undecided(pointcut, null, onClass.reason());

                continue;
            }
            for (Method candidate : methods) {
                Verdict onMethod = matchesMethod(pointcut.methodMatcher(), candidate);
                if (onMethod.match() == Match.YES) {
                    answer.wraps(candidate, pointcut);
                } else if (onMethod.match() == Match.UNKNOWN) {
                    answer.undecided(pointcut, candidate.getName(), onMethod.reason());
                }
            }
        }

        return answer;
    }

    /**
     * The methods a pointcut can reach: the public ones, {@code __construct} excluded, exactly as
     * {@code Ray\Aop\Bind} picks them, and inherited ones included because that is what
     * {@code ReflectionClass::getMethods()} returns. A method named in the question is answered for
     * on its own, so that "nothing wraps it" is an answer about the method asked about.
     */
    private static List<Method> methodsOf(PhpClass target, @Nullable String named) {
        List<Method> methods = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        PhpClass current = target;
        for (int depth = 0; current != null && depth < MAX_HIERARCHY; depth++) {
            for (Method method : current.getOwnMethods()) {
                String name = method.getName();
                if (method.getModifier().getAccess() != PhpModifier.Access.PUBLIC
                    || CONSTRUCT.equalsIgnoreCase(name)
                    || !seen.add(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                // PHP compares method names case-insensitively, so a question about "onget" is a
                // question about onGet().
                if (named == null || named.equalsIgnoreCase(name)) {
                    methods.add(method);
                }
            }
            current = current.getSuperClass();
        }

        return methods;
    }

    /** Every {@code bindInterceptor()}/{@code bindPriorityInterceptor()} call written in a file. */
    private List<Pointcut> readPointcuts(VirtualFile file, @Nullable Reach reach) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile == null) {
            return List.of();
        }
        List<Pointcut> pointcuts = new ArrayList<>();
        for (MethodReference call : PsiTreeUtil.findChildrenOfType(psiFile, MethodReference.class)) {
            ProgressManager.checkCanceled();
            String name = call.getName();
            boolean priority = BIND_PRIORITY_INTERCEPTOR.equalsIgnoreCase(name);
            if (!priority && !BIND_INTERCEPTOR.equalsIgnoreCase(name)) {
                continue;
            }
            // Ray.Di declares both with three required parameters on AbstractModule, reached
            // through $this; anything else is another bindInterceptor, of which a project may have
            // its own.
            PsiElementArguments arguments = new PsiElementArguments(call);
            if (!arguments.isRayDiCall()) {
                continue;
            }
            pointcuts.add(new Pointcut(
                PointcutMatcher.parse(arguments.classMatcher()),
                PointcutMatcher.parse(arguments.methodMatcher()),
                interceptors(arguments.interceptors()),
                priority,
                moduleClassOf(call),
                FactsFiles.relativePath(project, file),
                FactsFiles.lineOf(psiFile, call.getTextOffset()),
                reach
            ));
        }

        return pointcuts;
    }

    /** The interceptor classes an array argument names, in the order they are written. */
    private static List<String> interceptors(@Nullable com.intellij.psi.PsiElement argument) {
        if (!(argument instanceof ArrayCreationExpression array)) {
            return List.of();
        }
        List<String> classes = new ArrayList<>();
        for (ClassConstantReference reference : PsiTreeUtil.findChildrenOfType(array, ClassConstantReference.class)) {
            if (!"class".equalsIgnoreCase(reference.getName())
                || !(reference.getClassReference() instanceof ClassReference resolved)) {
                continue;
            }
            String fqn = InterceptorBindingIndexUtil.normalizeFqn(resolved.getFQN());
            if (fqn != null && !classes.contains(fqn)) {
                classes.add(fqn);
            }
        }

        return classes;
    }

    // ---- evaluation ------------------------------------------------------------------------

    /** Ray.Aop's {@code matchesClass}, with a third answer for what it could not decide. */
    private Verdict matchesClass(PointcutMatcher matcher, PhpClass target) {
        return switch (matcher) {
            case PointcutMatcher.Any ignored -> Verdict.YES;
            case PointcutMatcher.StartsWith(String prefix) -> Verdict.of(startsWith(target.getFQN(), prefix));
            case PointcutMatcher.SubclassesOf(String superClass) -> isSubclassOf(target, superClass);
            case PointcutMatcher.AnnotatedWith(String attribute) -> hasAttribute(target, attribute);
            case PointcutMatcher.Or(PointcutMatcher left, PointcutMatcher right) ->
                Verdict.or(matchesClass(left, target), matchesClass(right, target));
            case PointcutMatcher.And(PointcutMatcher left, PointcutMatcher right) ->
                Verdict.and(matchesClass(left, target), matchesClass(right, target));
            case PointcutMatcher.Not(PointcutMatcher inner) -> matchesClass(inner, target).negated();
            case PointcutMatcher.Unreadable ignored -> Verdict.unknown(REASON_UNREADABLE);
        };
    }

    /** Ray.Aop's {@code matchesMethod}, with the same third answer. */
    private Verdict matchesMethod(PointcutMatcher matcher, Method method) {
        return switch (matcher) {
            // AnyMatcher excludes magic methods and the ones it takes as built-in, so any() is not
            // quite "every method" -- and a caller asking about offsetGet() would be told it is.
            case PointcutMatcher.Any ignored -> Verdict.of(!isExcludedFromAny(method.getName()));
            case PointcutMatcher.StartsWith(String prefix) -> Verdict.of(method.getName().startsWith(prefix));
            case PointcutMatcher.AnnotatedWith(String attribute) -> hasAttribute(method, attribute);
            // Ray.Aop throws InvalidAnnotationException here rather than answering, so the pointcut
            // is not one this can decide -- and saying so names a fault in the module.
            case PointcutMatcher.SubclassesOf ignored -> Verdict.unknown(REASON_INVALID);
            case PointcutMatcher.Or(PointcutMatcher left, PointcutMatcher right) ->
                Verdict.or(matchesMethod(left, method), matchesMethod(right, method));
            case PointcutMatcher.And(PointcutMatcher left, PointcutMatcher right) ->
                Verdict.and(matchesMethod(left, method), matchesMethod(right, method));
            case PointcutMatcher.Not(PointcutMatcher inner) -> matchesMethod(inner, method).negated();
            case PointcutMatcher.Unreadable ignored -> Verdict.unknown(REASON_UNREADABLE);
        };
    }

    /**
     * {@code str_starts_with($class->name, $prefix)}, which compares reflection's name -- one with
     * no leading backslash. The prefix is written both ways in real modules, so both ends are
     * stripped before comparing; everything after that is compared as PHP compares it, exactly.
     */
    private static boolean startsWith(@Nullable String name, String prefix) {
        return name != null && unqualified(name).startsWith(unqualified(prefix));
    }

    private static String unqualified(String fqn) {
        return fqn.startsWith("\\") ? fqn.substring(1) : fqn;
    }

    private static boolean isExcludedFromAny(String method) {
        return method.startsWith("__") || ARRAY_OBJECT_METHODS.contains(method);
    }

    /**
     * {@code $class->isSubclassOf($superClass) || $class->name === $superClass}, walked over the
     * classes and interfaces the index resolves. A hierarchy with a link the index cannot resolve
     * cannot answer no -- the unresolved class may be the one that extends it -- so it answers
     * undecided instead.
     */
    private Verdict isSubclassOf(PhpClass phpClass, String superClass) {
        Set<String> seen = new HashSet<>();
        List<PhpClass> queue = new ArrayList<>(List.of(phpClass));
        boolean whole = true;
        while (!queue.isEmpty() && seen.size() < MAX_HIERARCHY) {
            PhpClass current = queue.remove(0);
            String fqn = current.getFQN();
            if (fqn == null || !seen.add(fqn.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (superClass.equalsIgnoreCase(fqn)) {
                return Verdict.YES;
            }
            PhpClass parent = current.getSuperClass();
            if (parent != null) {
                queue.add(parent);
            } else if (!current.getExtendsList().getReferenceElements().isEmpty()) {
                whole = false;
            }
            PhpClass[] interfaces = current.getImplementedInterfaces();
            queue.addAll(List.of(interfaces));
            whole &= interfaces.length >= current.getImplementsList().getReferenceElements().size();
        }

        return whole ? Verdict.NO : Verdict.unknown(REASON_HIERARCHY);
    }

    /**
     * Ray.Aop reads attributes with {@code ReflectionAttribute::IS_INSTANCEOF}, so
     * {@code annotatedWith(AbstractCacheControl::class)} matches an attribute that EXTENDS the
     * named class as surely as the class itself -- which is how {@code bear/query-repository} binds
     * its cache-control interceptor. Reading only exact names would miss those silently.
     *
     * <p>Attributes are read as declared: PHP reflection does not inherit a class attribute from a
     * parent class, so neither does this.
     */
    private Verdict hasAttribute(PhpAttributesOwner owner, String attribute) {
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
        boolean resolved = false;
        for (PhpClass phpClass : PhpIndex.getInstance(project).getClassesByFQN(declared)) {
            resolved = true;
            Verdict verdict = isSubclassOf(phpClass, attribute);
            if (verdict.match() != Match.NO) {
                return verdict;
            }
        }

        // An attribute class the index cannot resolve may or may not extend the named one, and
        // answering no would drop a pointcut that really does wrap the method.
        return resolved ? Verdict.NO : Verdict.unknown(REASON_HIERARCHY);
    }

    @Nullable
    private static String moduleClassOf(com.intellij.psi.PsiElement call) {
        PhpClass phpClass = PsiTreeUtil.getParentOfType(call, PhpClass.class);
        String fqn = phpClass == null ? null : phpClass.getFQN();

        return fqn == null || fqn.isBlank() || fqn.endsWith("\\") ? null : fqn;
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

    /** One {@code bindInterceptor()} call, read whole. */
    private record Pointcut(
        PointcutMatcher classMatcher,
        PointcutMatcher methodMatcher,
        List<String> interceptors,
        boolean priority,
        @Nullable String moduleClass,
        String filePath,
        @Nullable Integer line,
        @Nullable Reach reach
    ) {
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

    /** The three parameters Ray.Di declares, and whether the call is really its own. */
    private record PsiElementArguments(MethodReference call) {

        boolean isRayDiCall() {
            return call.getParameters().length == 3
                && call.getClassReference() instanceof Variable receiver
                && "this".equals(receiver.getName());
        }

        com.intellij.psi.PsiElement classMatcher() {
            return call.getParameters()[0];
        }

        com.intellij.psi.PsiElement methodMatcher() {
            return call.getParameters()[1];
        }

        com.intellij.psi.PsiElement interceptors() {
            return call.getParameters()[2];
        }
    }

    /** The answer being built: which interceptors wrap which method, and what stayed undecided. */
    private static final class Answer {

        private final Map<String, JsonArray> byMethod = new java.util.LinkedHashMap<>();
        private final Map<String, Method> methods = new java.util.LinkedHashMap<>();
        private final JsonArray unevaluated = new JsonArray();
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
            for (String interceptor : pointcut.interceptors()) {
                interceptors.add(interceptorJson(interceptor, pointcut));
            }
            // A pointcut naming no interceptor this could read still wraps the method with
            // something, and saying nothing would report the method as wrapped by nothing.
            if (pointcut.interceptors().isEmpty()) {
                JsonObject json = new JsonObject();
                json.addProperty("interceptorsUnreadable", true);
                addPointcut(json, pointcut);
                interceptors.add(json);
            }
        }

        void undecided(Pointcut pointcut, @Nullable String method, @Nullable String reason) {
            JsonObject json = new JsonObject();
            json.addProperty("reason", reason == null ? REASON_UNREADABLE : reason);
            if (method != null) {
                json.addProperty("method", method);
            }
            addPointcut(json, pointcut);
            unevaluated.add(json);
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

            JsonObject payload = new JsonObject();
            payload.add("scan", scan);
            payload.add("target", targetJson);
            payload.add("methods", methodsJson);
            payload.add("unevaluated", unevaluated);

            return Envelope.ok(provenance, payload).toJson();
        }
    }
}
