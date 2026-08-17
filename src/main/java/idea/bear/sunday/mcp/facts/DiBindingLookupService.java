package idea.bear.sunday.mcp.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.psi.elements.ClassConstantReference;
import com.jetbrains.php.lang.psi.elements.ClassReference;
import com.jetbrains.php.lang.psi.elements.MethodReference;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.PhpReturn;
import com.jetbrains.php.lang.psi.elements.Statement;
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression;
import com.jetbrains.php.lang.psi.elements.Variable;
import com.jetbrains.php.util.PhpStringUtil;
import idea.bear.sunday.aop.InterceptorBindingIndexUtil;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Answers which implementation a Ray.Di module binds an interface to, and which module file binds
 * it. This is the question a text search cannot answer: {@code #[Named('category')]
 * SurrogateKeyInterface $key} names neither the implementation class nor the string that leads to
 * it, so grepping for the implementation finds the class and misses every place it is injected.
 *
 * <p>The module files are walked and read from PSI rather than looked up in an index, so the
 * answer is available while the project index is still building, and it reflects unsaved editor
 * changes. A context narrows the scan to the modules that context installs, and resolving a
 * context needs the index, so only that form of the question waits for it.
 *
 * <p>What this version reads is one construct: a {@code $this->bind(...)} chain. Without a context
 * the answer is every binding in the scanned root, whichever context installs it; with one, the
 * files read are those of the module tree {@link DiModuleTreeService} walks, and each binding says
 * which segment reached the module it was read from. Which of several bindings of the same
 * interface wins is still not decided here -- the priority a binding carries is what it takes to
 * decide it. Bindings made by {@code MultiBinder} are not read at all, and a {@code rename()} call
 * is reported rather than applied.
 */
@Service(Service.Level.PROJECT)
public final class DiBindingLookupService {

    static final String DEFAULT_MODULE_ROOT = "src";

    private static final String BIND = "bind";
    private static final String RENAME = "rename";
    private static final String CONFIGURE = "configure";
    private static final String MODULE = "Module";
    private static final String ANNOTATED_WITH = "annotatedWith";
    private static final String IN = "in";
    private static final String TO = "to";

    private static final String TO_PROVIDER = "toProvider";
    private static final String TO_CONSTRUCTOR = "toConstructor";

    /** Every way {@code Ray\Di\Bind} takes a target, written as Ray.Di writes it. */
    private static final List<String> TARGET_METHODS =
        List.of(TO, TO_PROVIDER, TO_CONSTRUCTOR, "toInstance", "toNull");

    /** The ones whose argument is a class name; the rest take a value or nothing. */
    private static final List<String> NAMES_A_CLASS = List.of(TO, TO_PROVIDER, TO_CONSTRUCTOR);

    private static final String RESOLUTION_STATIC = "static";
    private static final String RESOLUTION_DYNAMIC = "dynamic-unresolved";

    private static final String BOUND_BY_UNTARGETED = "untargeted";
    private static final String BOUND_BY_UNKNOWN = "unknown";
    private static final String QUALIFIER_NAME = "name";
    private static final String QUALIFIER_CLASS = "class";
    private static final String QUALIFIER_UNRESOLVED = "unresolved";

    private static final String REASON_INTERFACE = "interface-unreadable";
    private static final String REASON_QUALIFIER = "qualifier-unreadable";
    private static final String REASON_CHAIN = "chain-unreadable";
    private static final String REASON_RENAME = "rename-not-applied";

    /** Long enough for every chain in bear/* and ray/*; a toConstructor argument map can exceed it. */
    private static final int MAX_TEXT = 300;

    /** Well past any project's src; reached only by a root such as "vendor", and then reported. */
    private static final int MAX_FILES = 2000;

    private final Project project;

    public DiBindingLookupService(Project project) {
        this.project = project;
    }

    public static DiBindingLookupService getInstance(Project project) {
        return project.getService(DiBindingLookupService.class);
    }

    public String lookup(
        @Nullable String interfaceName,
        @Nullable String qualifier,
        @Nullable String moduleRoot,
        @Nullable String context
    ) {
        // Not ReadAction.compute: that holds the read lock until the whole scan is done, and a
        // pending write action (every keystroke) waits behind it. The non-blocking form is
        // cancelled and retried when a write action needs the lock, so a long scan cannot
        // freeze the editor.
        return ReadAction.nonBlocking(() -> lookUpBindings(interfaceName, qualifier, moduleRoot, context))
            .executeSynchronously();
    }

    private String lookUpBindings(
        @Nullable String interfaceName,
        @Nullable String qualifier,
        @Nullable String moduleRoot,
        @Nullable String context
    ) {
        if (context != null && !context.isBlank()) {
            // Refused rather than resolved by a rule of precedence: the two name the scan in
            // different terms -- one a set of modules, the other a directory of files -- and
            // quietly dropping either would answer a question that was not asked.
            if (moduleRoot != null && !moduleRoot.isBlank()) {
                return Envelope.notFound(
                    "Pass either context or moduleRoot: a context names the modules to read, a root names the files."
                ).toJson();
            }

            return contextBindings(interfaceName, qualifier, context.trim());
        }

        String root = FactsFiles.normalizeRoot(moduleRoot, DEFAULT_MODULE_ROOT);
        if (root == null) {
            return Envelope.notFound("Unsupported module root: " + moduleRoot).toJson();
        }
        VirtualFile rootDir = FactsFiles.find(project, root);
        if (rootDir == null || !rootDir.isDirectory()) {
            return Envelope.notFound("Module root not found: " + root).toJson();
        }

        ClassFilter interfaceFilter = ClassFilter.of(interfaceName);
        QualifierFilter qualifierFilter = QualifierFilter.of(qualifier);
        Answer answer = new Answer(interfaceFilter, qualifierFilter);
        List<VirtualFile> found = FactsFiles.phpFilesUnder(rootDir);
        // A root such as "vendor" holds tens of thousands of files, and every one of them would be
        // parsed inside one read action. The files are walked in path order, so the cut is at least
        // the same one every time -- and it is reported, because a silent one reads as "that is
        // everything there is".
        List<VirtualFile> files = found.size() <= MAX_FILES ? found : found.subList(0, MAX_FILES);
        boolean unsaved = false;

        for (VirtualFile file : files) {
            // Every walked file counts towards freshness: an unsaved edit can add a binding as
            // easily as it can remove one, and this answer is read from PSI either way.
            unsaved |= FactsFiles.isUnsaved(file);
            readCalls(file, answer, null);
        }

        JsonObject scan = new JsonObject();
        scan.addProperty("moduleRoot", root);
        scan.addProperty("files", files.size());
        if (found.size() > files.size()) {
            scan.addProperty("filesSkipped", found.size() - files.size());
        }
        scan.addProperty("moduleFiles", answer.moduleFiles);
        scan.addProperty("bindings", answer.bindingsFound);
        scan.addProperty("renames", answer.renamesFound);

        JsonObject payload = new JsonObject();
        payload.add("scan", scan);
        payload.add("bindings", answer.bindings);
        payload.add("unresolved", answer.unresolved);

        return Envelope.ok(Provenance.derived(root, unsaved), payload).toJson();
    }

    /**
     * The bindings of the modules a context installs, rather than of a directory: the module tree
     * is walked first, and the files read are the ones its modules are wired in -- their own and
     * their base modules', which is where a module that leaves its wiring to a base states its
     * bindings.
     *
     * <p>What the walk could not read is reported alongside, because a tree with holes in it makes
     * an empty answer that reads as "nothing binds this": a segment no class answers to, a module
     * class the index could not resolve, an install whose module the source does not name. Reading
     * whole files rather than class bodies also means a file may hold a class the context does not
     * install; each binding names the module class it was read from, which is what settles it.
     */
    private String contextBindings(@Nullable String interfaceName, @Nullable String qualifier, String context) {
        DiModuleTreeService.Walk walk;
        try {
            walk = DiModuleTreeService.getInstance(project).walk(context);
        } catch (IndexNotReadyException exception) {
            return Envelope.indexNotReady(
                "The project index is still building; the modules a context installs cannot be resolved yet. "
                    + "Ask without a context to read a directory instead."
            ).toJson();
        }

        Answer answer = new Answer(ClassFilter.of(interfaceName), QualifierFilter.of(qualifier));
        Set<VirtualFile> read = new HashSet<>();
        for (DiModuleTreeService.WalkedModule module : walk.modules()) {
            Reach reach = new Reach(module.segment(), module.priority());
            for (VirtualFile file : module.files()) {
                // The walk hands its modules out in priority order, so the first module to reach a
                // file is the strongest one that does, and that is the reach the file is read under.
                // A base module two segments share is one file, read once.
                if (read.add(file)) {
                    readCalls(file, answer, reach);
                }
            }
        }

        JsonObject scan = new JsonObject();
        scan.addProperty("context", walk.context());
        scan.addProperty("modules", walk.modules().size());
        scan.addProperty("files", read.size());
        scan.addProperty("moduleFiles", answer.moduleFiles);
        scan.addProperty("bindings", answer.bindingsFound);
        scan.addProperty("renames", answer.renamesFound);
        if (!walk.unresolvedJson().isEmpty()) {
            JsonArray segments = new JsonArray();
            for (JsonElement element : walk.unresolvedJson()) {
                segments.add(element.getAsJsonObject().get("segment").getAsString());
            }
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
        // Without src/Module/AppModule.php the app-side candidate of a segment cannot even be
        // named, so a segment resolved to the framework module may be one an app module shadows.
        if (walk.appNamespace() == null) {
            scan.addProperty("appNamespaceUnknown", true);
        }

        JsonObject payload = new JsonObject();
        payload.add("scan", scan);
        payload.add("bindings", answer.bindings);
        payload.add("unresolved", answer.unresolved);

        return Envelope.ok(Provenance.derived(walk.context(), walk.unsaved()), payload).toJson();
    }

    private void readCalls(VirtualFile file, Answer answer, @Nullable Reach reach) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile == null) {
            return;
        }
        // Most walked files bind nothing, and a Site forces a Document open and a project-root
        // lookup, so it is built once the file turns out to hold a binding and not before.
        Site site = null;

        for (MethodReference call : PsiTreeUtil.findChildrenOfType(psiFile, MethodReference.class)) {
            ProgressManager.checkCanceled();
            boolean bind = isBindCall(call);
            if (!bind && !isRenameCall(call)) {
                continue;
            }
            if (site == null) {
                site = new Site(
                    FactsFiles.relativePath(project, file),
                    PsiDocumentManager.getInstance(project).getDocument(psiFile)
                );
                answer.moduleFiles++;
            }
            if (bind) {
                answer.addBinding(call, site, reach);
            } else {
                answer.addRename(call, site, reach);
            }
        }
    }

    /**
     * A {@code $this->bind()} call. {@code AbstractModule::bind()} is protected and takes at most
     * the interface name, so the receiver and the argument count separate it from most other
     * {@code bind} methods, and {@link #isModuleLike} separates it from the rest.
     */
    private static boolean isBindCall(MethodReference call) {
        return BIND.equalsIgnoreCase(call.getName())
            && isThis(call.getClassReference())
            && call.getParameters().length <= 1
            && isModuleLike(call, BIND);
    }

    /**
     * A {@code rename()} call, which moves an existing binding to another qualifier. Ray.Di
     * declares it with two required parameters and two optional ones. The receiver is not required
     * to be {@code $this}: a module renames a binding of the module it was handed
     * ({@code $module->rename(...)}) as often as one of its own. The arguments are not required to
     * be literals either -- a rename this cannot read is the one most worth reporting.
     */
    private static boolean isRenameCall(MethodReference call) {
        int arguments = call.getParameters().length;

        return RENAME.equalsIgnoreCase(call.getName())
            // Ray.Di's rename is reached through $this or through a module held in a variable, so
            // a receiver that is anything else -- $this->filesystem->rename($from, $to) -- renames
            // something that is not a binding.
            && call.getClassReference() instanceof Variable
            && arguments >= 2
            && arguments <= 4
            && isModuleLike(call, RENAME)
            && isModule(call);
    }

    /**
     * Whether the class a call sits in is a Ray.Di module rather than merely a class that extends
     * something. Only the rename path asks: {@code rename} is an ordinary method name, and
     * {@code $this->rename($from, $to)} on a class holding files or table names would otherwise be
     * published as a rename that may have moved the binding the caller asked about -- a warning
     * about nothing, in the one channel this tool asks to be believed. A {@code bind()} chain
     * names itself well enough to need no such test.
     *
     * <p>Three ways to be one, all file-local so the answer holds while the index is building: the
     * class declares {@code configure()}, which every concrete module does because
     * {@code AbstractModule} declares it abstract; or it extends a class named {@code *Module}, so
     * a module that inherits {@code configure()} from a base module still counts; or it is a trait,
     * which can declare neither and whose bindings run in whichever module uses it.
     */
    private static boolean isModule(PsiElement call) {
        PhpClass phpClass = PsiTreeUtil.getParentOfType(call, PhpClass.class);
        if (phpClass == null) {
            return false;
        }
        if (phpClass.isTrait() || phpClass.findOwnMethodByName(CONFIGURE) != null) {
            return true;
        }
        for (ClassReference parent : phpClass.getExtendsList().getReferenceElements()) {
            String name = parent.getName();
            if (name != null && name.regionMatches(true, name.length() - MODULE.length(), MODULE, 0, MODULE.length())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Whether the class a call sits in can be a Ray.Di module. Both tests read only the class's own
     * PSI, so they hold while the project index is still building, and neither can hide a real
     * module: {@code AbstractModule} is abstract, so a module always extends something -- or the
     * call sits in a trait a module uses, which extends nothing by definition -- and a class that
     * declares {@code bind()} itself is the one PHP calls, not Ray.Di's.
     */
    private static boolean isModuleLike(PsiElement call, String method) {
        PhpClass phpClass = PsiTreeUtil.getParentOfType(call, PhpClass.class);

        return phpClass != null
            && (phpClass.isTrait() || !phpClass.getExtendsList().getReferenceElements().isEmpty())
            && phpClass.findOwnMethodByName(method) == null;
    }

    private static boolean isThis(@Nullable PsiElement receiver) {
        return receiver instanceof Variable variable && "this".equals(variable.getName());
    }

    /**
     * Reads a {@code $this->bind(...)} chain by walking up the calls made on its result:
     * {@code bind()} is the innermost node of {@code $this->bind(X)->annotatedWith(n)->to(Y)}, and
     * each following call has the previous one as its receiver.
     */
    private static Binding readBinding(MethodReference bindCall) {
        PsiElement[] parameters = bindCall.getParameters();
        // bind() with no argument binds a name alone, which is a binding with no interface, not an
        // unreadable one. So does bind(''), which passes Ray.Di's own default explicitly. Only an
        // argument whose value the source does not state makes the interface unreadable.
        PsiElement argument = parameters.length == 1 ? parameters[0] : null;
        String boundInterface = readInterface(argument);
        boolean interfaceUnreadable = argument != null && boundInterface == null && !isEmptyString(argument);

        Qualifier qualifier = null;
        String boundBy = BOUND_BY_UNTARGETED;
        String implementation = null;
        String targetClass = null;
        boolean targetUnreadable = false;
        String scope = null;

        MethodReference current = bindCall;
        while (true) {
            PsiElement parent = current.getParent();
            if (!(parent instanceof MethodReference next) || next.getClassReference() != current) {
                break;
            }
            String name = next.getName();
            current = next;
            if (name == null) {
                continue;
            }
            PsiElement firstArgument = next.getParameters().length == 0 ? null : next.getParameters()[0];
            String target = targetMethod(name);
            if (ANNOTATED_WITH.equalsIgnoreCase(name)) {
                qualifier = readQualifier(firstArgument);
            } else if (IN.equalsIgnoreCase(name)) {
                scope = firstArgument == null ? null : text(firstArgument);
            } else if (target != null) {
                boundBy = target;
                boolean namesAClass = NAMES_A_CLASS.contains(target);
                // to(), toProvider() and toConstructor() take their class as 'My\Impl' as well as
                // My\Impl::class -- the same two forms bind() accepts -- while toInstance() takes
                // a value, whose string is a string and not the name of a class.
                String argumentClass = namesAClass ? readInterface(firstArgument) : classConstFqn(firstArgument);
                if (TO.equals(target)) {
                    implementation = argumentClass;
                } else {
                    targetClass = argumentClass;
                }
                // When the argument of a class-naming target is one this cannot read, saying so is
                // what separates "bound to something I could not name" from "bound to nothing
                // nameable", which toInstance() and toNull() are.
                targetUnreadable = argumentClass == null && namesAClass;
            }
        }

        // A chain that named no target is Ray.Di's untargeted binding -- but only when it really
        // ends here. A chain continued through a variable, a parenthesis or a return ends the walk
        // too, and calling that untargeted would claim Ray.Di builds the class itself while the
        // caller names an implementation on the very next line.
        PsiElement end = current.getParent();
        if (BOUND_BY_UNTARGETED.equals(boundBy) && (!(end instanceof Statement) || end instanceof PhpReturn)) {
            boundBy = BOUND_BY_UNKNOWN;
        }

        return new Binding(
            boundInterface,
            interfaceUnreadable,
            qualifier,
            boundBy,
            implementation,
            targetClass,
            targetUnreadable,
            scope,
            text(current)
        );
    }

    /**
     * The Ray.Di target method a chain link names, spelled as Ray.Di spells it, or {@code null}
     * when the link is not one. PHP compares method names case-insensitively, so the spelling in
     * the file is not necessarily the one reported.
     */
    @Nullable
    private static String targetMethod(String name) {
        for (String method : TARGET_METHODS) {
            if (method.equalsIgnoreCase(name)) {
                return method;
            }
        }

        return null;
    }

    /**
     * The class an argument names. Ray.Di declares these arguments as strings, so a class
     * constant and a plain string literal name a class equally well: {@code bind()} takes both,
     * and so do the class-naming targets. {@code bind('')} names none, as {@code bind()} does.
     */
    @Nullable
    private static String readInterface(@Nullable PsiElement argument) {
        if (argument == null) {
            return null;
        }
        String literal = stringValue(argument);
        if (literal != null) {
            return literal.isEmpty() ? null : InterceptorBindingIndexUtil.normalizeFqn(literal);
        }

        return classConstFqn(argument);
    }

    private static boolean isEmptyString(PsiElement argument) {
        return "".equals(stringValue(argument));
    }

    /**
     * What {@code annotatedWith()} names. A string is the {@code #[Named('x')]} value; a class
     * constant is a qualifier attribute class, which Ray.Di keys the binding by under its own
     * class name. Anything else -- a variable, a concatenation, an interpolated string -- is
     * reported as unresolved rather than guessed at.
     */
    private static Qualifier readQualifier(@Nullable PsiElement argument) {
        String name = stringValue(argument);
        if (name != null) {
            return new Qualifier(QUALIFIER_NAME, name);
        }
        String fqn = classConstFqn(argument);
        if (fqn != null) {
            return new Qualifier(QUALIFIER_CLASS, fqn);
        }

        return new Qualifier(QUALIFIER_UNRESOLVED, argument == null ? null : text(argument));
    }

    /**
     * The string a literal argument stands for, or {@code null} when the argument is not a literal
     * whose value the source states. An interpolated string states a template, not a value: its
     * text is {@code "{$this->qualifier}_dsn"} while the name Ray.Di keys the binding by is
     * whatever the property held. Escapes are decoded, because {@code "a\tb"} keys the binding
     * under a tab, not under a backslash and a t.
     */
    @Nullable
    private static String stringValue(@Nullable PsiElement element) {
        if (!(element instanceof StringLiteralExpression literal) || literal.getFirstPsiChild() != null) {
            return null;
        }

        return PhpStringUtil.unescapeText(literal);
    }

    /**
     * The normalized class name a {@code Foo::class} expression names, or {@code null}. Any other
     * class constant is refused: {@code Registry::DEFAULT_IMPL} holds a value this cannot read,
     * and reporting the class that declares it would name the wrong class with full confidence.
     */
    @Nullable
    private static String classConstFqn(@Nullable PsiElement element) {
        if (!(element instanceof ClassConstantReference reference)) {
            return null;
        }
        // PHP writes ::class case-insensitively, as it writes every constant fetch on a class.
        if (!"class".equalsIgnoreCase(reference.getName())) {
            return null;
        }
        if (!(reference.getClassReference() instanceof ClassReference resolved)) {
            return null;
        }

        return InterceptorBindingIndexUtil.normalizeFqn(resolved.getFQN());
    }

    /** Source text on one line. A chain spans several lines and may carry a docblock between them. */
    private static String text(PsiElement element) {
        String text = element.getText().replaceAll("\\s+", " ").trim();

        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "…";
    }

    /**
     * The class a call sits in. An anonymous module class has no name, and its FQN is the
     * namespace alone -- a name no class in the project answers to, so none is reported for it.
     */
    @Nullable
    private static String moduleClassOf(PsiElement call) {
        PhpClass phpClass = PsiTreeUtil.getParentOfType(call, PhpClass.class);
        String fqn = phpClass == null ? null : phpClass.getFQN();

        return fqn == null || fqn.isBlank() || fqn.endsWith("\\") ? null : fqn;
    }

    /** The 1-based line a call starts on, or {@code null} when the file has no document to count in. */
    @Nullable
    private static Integer lineOf(@Nullable Document document, int offset) {
        return document == null || offset >= document.getTextLength() ? null : document.getLineNumber(offset) + 1;
    }

    /** The file a call was read from, and the document its line numbers are counted in. */
    private record Site(String filePath, @Nullable Document document) {
    }

    /**
     * Where in a context's module tree a call was read from: the segment whose subtree reached the
     * module, and where that segment stands in the priority order. {@code null} for a scan of a
     * directory, which is not a tree and orders nothing.
     */
    private record Reach(@Nullable String segment, int priority) {
    }

    private record Qualifier(String kind, @Nullable String value) {
    }

    private record Binding(
        @Nullable String boundInterface,
        boolean interfaceUnreadable,
        @Nullable Qualifier qualifier,
        String boundBy,
        @Nullable String implementation,
        @Nullable String targetClass,
        boolean targetUnreadable,
        @Nullable String scope,
        String text
    ) {
    }

    /**
     * The answer being built. A binding the filters cannot decide on -- because the element being
     * filtered is the one that could not be read -- goes to {@code unresolved} instead of being
     * dropped, so that "no such binding" stays distinguishable from "I could not read this one".
     */
    private static final class Answer {

        private final ClassFilter interfaceFilter;
        private final QualifierFilter qualifierFilter;
        private final JsonArray bindings = new JsonArray();
        private final JsonArray unresolved = new JsonArray();
        private int moduleFiles;
        private int bindingsFound;
        private int renamesFound;

        Answer(ClassFilter interfaceFilter, QualifierFilter qualifierFilter) {
            this.interfaceFilter = interfaceFilter;
            this.qualifierFilter = qualifierFilter;
        }

        void addBinding(MethodReference bindCall, Site site, @Nullable Reach reach) {
            bindingsFound++;
            Binding binding = readBinding(bindCall);
            if (binding.interfaceUnreadable() && !interfaceFilter.matchesEverything()) {
                unresolved.add(entry(REASON_INTERFACE, bindCall, site, null, binding.text(), reach));

                return;
            }
            if (!interfaceFilter.matches(binding.boundInterface())) {
                return;
            }
            // A chain this could not follow may name a qualifier further along, so a qualifier
            // query cannot decide it -- unless the chain already named one before it broke, which
            // is the whole answer the query needs.
            if (BOUND_BY_UNKNOWN.equals(binding.boundBy())
                && binding.qualifier() == null
                && !qualifierFilter.matchesEverything()) {
                unresolved.add(entry(REASON_CHAIN, bindCall, site, binding.boundInterface(), binding.text(), reach));

                return;
            }
            if (isUnresolved(binding.qualifier()) && !qualifierFilter.matchesEverything()) {
                unresolved.add(entry(REASON_QUALIFIER, bindCall, site, binding.boundInterface(), binding.text(), reach));

                return;
            }
            if (!qualifierFilter.matches(binding.qualifier())) {
                return;
            }
            bindings.add(json(binding, bindCall, site, reach));
        }

        /**
         * A {@code rename()} moves a binding to another qualifier, and a version that does not
         * apply it can be wrong about which qualifier a binding answers to; reporting the call is
         * what keeps that from becoming a confident "no such binding".
         *
         * <p>Ray.Di's {@code rename($interface, $newName, $sourceName, $targetInterface)} can move
         * it to another interface too, so both ends are about the interface being asked for: a
         * query for the interface a binding LANDS on has to see the rename that puts it there. An
         * empty {@code $targetInterface} is Ray.Di's own default for "the same one"
         * ({@code $targetInterface = $targetInterface ?: $interface}); an end whose value the
         * source does not state at all is read as "could be this one".
         */
        void addRename(MethodReference call, Site site, @Nullable Reach reach) {
            renamesFound++;
            PsiElement[] parameters = call.getParameters();
            String source = readInterface(parameters[0]);
            boolean moves = parameters.length >= 4 && !isEmptyString(parameters[3]);
            String target = moves ? readInterface(parameters[3]) : source;
            boolean unreadable = source == null || (moves && target == null);
            if (!unreadable && !interfaceFilter.matches(source) && !interfaceFilter.matches(target)) {
                return;
            }
            unresolved.add(entry(REASON_RENAME, call, site, source, text(call), reach));
        }

        private static boolean isUnresolved(@Nullable Qualifier qualifier) {
            return qualifier != null && QUALIFIER_UNRESOLVED.equals(qualifier.kind());
        }

        private static JsonObject json(Binding binding, MethodReference bindCall, Site site, @Nullable Reach reach) {
            JsonObject json = new JsonObject();
            if (binding.boundInterface() != null) {
                json.addProperty("interface", binding.boundInterface());
            }
            // Without this, a bind() whose argument could not be read looks exactly like a bind()
            // that was given no argument at all, which is a binding under a name and no interface.
            if (binding.interfaceUnreadable()) {
                json.addProperty("interfaceUnreadable", true);
            }
            if (binding.qualifier() != null) {
                JsonObject qualifier = new JsonObject();
                qualifier.addProperty("kind", binding.qualifier().kind());
                if (binding.qualifier().value() != null) {
                    qualifier.addProperty("value", binding.qualifier().value());
                }
                json.add("qualifier", qualifier);
            }
            json.addProperty("boundBy", binding.boundBy());
            json.addProperty(
                "resolution",
                binding.implementation() == null ? RESOLUTION_DYNAMIC : RESOLUTION_STATIC
            );
            if (binding.implementation() != null) {
                json.addProperty("implementation", binding.implementation());
            }
            if (binding.targetClass() != null) {
                json.addProperty("targetClass", binding.targetClass());
            }
            if (binding.targetUnreadable()) {
                json.addProperty("targetUnreadable", true);
            }
            if (binding.scope() != null) {
                json.addProperty("scope", binding.scope());
            }
            addSite(json, bindCall, site, reach);
            json.addProperty("text", binding.text());

            return json;
        }

        private static JsonObject entry(
            String reason,
            MethodReference call,
            Site site,
            @Nullable String boundInterface,
            String text,
            @Nullable Reach reach
        ) {
            JsonObject json = new JsonObject();
            json.addProperty("reason", reason);
            if (boundInterface != null) {
                json.addProperty("interface", boundInterface);
            }
            addSite(json, call, site, reach);
            json.addProperty("text", text);

            return json;
        }

        private static void addSite(JsonObject json, MethodReference call, Site site, @Nullable Reach reach) {
            String moduleClass = moduleClassOf(call);
            if (moduleClass != null) {
                json.addProperty("moduleClass", moduleClass);
            }
            json.addProperty("filePath", site.filePath());
            Integer line = lineOf(site.document(), call.getTextOffset());
            if (line != null) {
                json.addProperty("line", line);
            }
            if (reach == null) {
                return;
            }
            // The two modules the loader adds itself are reached by no segment, and naming one for
            // them would invent a context segment the caller did not write. Their priority still
            // places them: 0 is the loader's final override, past the last segment is the module it
            // starts from.
            if (reach.segment() != null) {
                json.addProperty("segment", reach.segment());
            }
            json.addProperty("priority", reach.priority());
        }
    }

    /**
     * Which interface the answer keeps. A query holding a backslash is a class name and is matched
     * whole; a query without one is matched against the last segment. Both are compared
     * case-insensitively, as PHP compares class names.
     */
    private record ClassFilter(@Nullable String fqn, @Nullable String shortName) {

        static ClassFilter of(@Nullable String query) {
            if (query == null || query.isBlank()) {
                return new ClassFilter(null, null);
            }
            String trimmed = query.trim();

            return trimmed.indexOf('\\') >= 0
                ? new ClassFilter(InterceptorBindingIndexUtil.normalizeFqn(trimmed), null)
                : new ClassFilter(null, trimmed);
        }

        boolean matchesEverything() {
            return fqn == null && shortName == null;
        }

        boolean matches(@Nullable String candidate) {
            if (matchesEverything()) {
                return true;
            }
            if (candidate == null) {
                return false;
            }

            return fqn != null
                ? fqn.equalsIgnoreCase(candidate)
                : shortName.equalsIgnoreCase(candidate.substring(candidate.lastIndexOf('\\') + 1));
        }
    }

    /**
     * Which qualifier the answer keeps. Ray.Di keys a binding by a plain string either way -- a
     * {@code #[Named('x')]} value is the string {@code x}, a qualifier attribute class is its own
     * class name -- so a query is matched against both: as a {@code #[Named]} value, compared
     * exactly because PHP compares strings exactly, and as a class name, whole when it holds a
     * backslash and by last segment when it does not. Matching only one of the two would let a
     * {@code #[Named]} value that happens to contain a backslash answer nothing at all. No query
     * keeps every binding, including the ones bound under no qualifier at all.
     */
    private record QualifierFilter(ClassFilter classFilter, @Nullable String name) {

        static QualifierFilter of(@Nullable String query) {
            if (query == null || query.isBlank()) {
                return new QualifierFilter(ClassFilter.of(null), null);
            }
            String trimmed = query.trim();

            return new QualifierFilter(ClassFilter.of(trimmed), trimmed);
        }

        boolean matchesEverything() {
            return classFilter.matchesEverything();
        }

        boolean matches(@Nullable Qualifier qualifier) {
            if (matchesEverything()) {
                return true;
            }
            if (qualifier == null) {
                return false;
            }

            return QUALIFIER_NAME.equals(qualifier.kind())
                ? name != null && name.equals(qualifier.value())
                : classFilter.matches(qualifier.value());
        }
    }
}
