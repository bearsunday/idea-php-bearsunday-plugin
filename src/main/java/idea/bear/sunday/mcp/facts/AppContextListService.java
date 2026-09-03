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
import com.jetbrains.php.lang.lexer.PhpTokenTypes;
import com.jetbrains.php.lang.psi.elements.BinaryExpression;
import com.jetbrains.php.lang.psi.elements.ClassReference;
import com.jetbrains.php.lang.psi.elements.FunctionReference;
import com.jetbrains.php.lang.psi.elements.MethodReference;
import com.jetbrains.php.lang.psi.elements.NewExpression;
import com.jetbrains.php.lang.psi.elements.ParenthesizedExpression;
import com.jetbrains.php.lang.psi.elements.PhpPsiElement;
import com.jetbrains.php.lang.psi.elements.TernaryExpression;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers which contexts an application actually runs under, read from the places its own code
 * names one. Every other tool that takes a context -- the module tree, the binding lookup, the
 * pointcut lookup -- asks for a string no file states in one place, so the caller has been left to
 * guess it from a convention; this is where the app says it.
 *
 * <p>A context is written as an argument, and in two shapes:
 * {@code (new Bootstrap())('prod-hal-app', $GLOBALS, $_SERVER)}, which is how an entry point boots
 * the app, and {@code Injector::getInstance('app')}, which is how a test reaches into it. Both are
 * matched by the name the class is written under rather than by a resolved one: every BEAR app
 * declares its own {@code Bootstrap} and {@code Injector} in its own namespace, so the name is the
 * convention and the FQN is not.
 *
 * <p>Read as the source states it. A context named by a variable -- {@code Injector::getInstance(
 * $context)}, which is what {@code Bootstrap} itself writes -- states no context, and is counted
 * rather than guessed at.
 */
@Service(Service.Level.PROJECT)
public final class AppContextListService {

    private static final String BOOTSTRAP = "Bootstrap";
    private static final String INJECTOR = "Injector";
    private static final String GET_INSTANCE = "getInstance";

    /**
     * Where an app names the context it boots under. {@code vendor} is not among them: the
     * framework's own fixtures name contexts that are no app's.
     */
    private static final List<String> ROOTS = List.of("public", "bin", "tests", "src");

    /** Well past any app's entry points; a root such as tests can hold a great many files. */
    private static final int MAX_FILES = 2000;

    private final Project project;

    public AppContextListService(Project project) {
        this.project = project;
    }

    public static AppContextListService getInstance(Project project) {
        return project.getService(AppContextListService.class);
    }

    public String list() {
        // Non-blocking so a pending write action is not made to wait out the scan; cancelled and
        // retried instead. See DiBindingLookupService#lookup.
        return ReadAction.nonBlocking(this::listContexts).executeSynchronously();
    }

    /**
     * The context names alone, in the order they were found, for a caller that offers them as a
     * choice rather than reporting them. Empty when the scan found none, which is an answer: an app
     * whose entry points name no context is one this cannot offer a choice for.
     */
    public List<String> names() {
        return ReadAction.nonBlocking(() -> new ArrayList<>(scan().contexts.keySet())).executeSynchronously();
    }

    private String listContexts() {
        try {
            Scan scan = scan();

            JsonObject scanJson = new JsonObject();
            scanJson.addProperty("roots", String.join(", ", ROOTS));
            scanJson.addProperty("files", scan.files);
            if (scan.filesSkipped > 0) {
                scanJson.addProperty("filesSkipped", scan.filesSkipped);
            }
            // A call that names its context with a variable is reported rather than dropped: the
            // app runs under a context this did not read, and a list that stayed silent about it
            // would read as the whole list.
            if (scan.unreadable > 0) {
                scanJson.addProperty("argumentsUnreadable", scan.unreadable);
            }

            JsonArray contexts = new JsonArray();
            scan.contexts.forEach((context, sites) -> {
                JsonObject json = new JsonObject();
                json.addProperty("context", context);
                JsonArray written = new JsonArray();
                sites.forEach(site -> written.add(site.toJson()));
                json.add("writtenIn", written);
                contexts.add(json);
            });

            JsonObject payload = new JsonObject();
            payload.add("scan", scanJson);
            payload.add("contexts", contexts);

            return Envelope.ok(Provenance.derived(String.join(", ", ROOTS), scan.unsaved), payload).toJson();
        } catch (IndexNotReadyException exception) {
            return Envelope.indexNotReady("The project index is still building; the app's files cannot be read yet.").toJson();
        }
    }

    private Scan scan() {
        Scan scan = new Scan();
        for (String root : ROOTS) {
            VirtualFile dir = FactsFiles.find(project, root);
            if (dir == null || !dir.isDirectory()) {
                continue;
            }
            for (VirtualFile file : FactsFiles.phpFilesUnder(dir)) {
                if (scan.files >= MAX_FILES) {
                    scan.filesSkipped++;

                    continue;
                }
                scan.files++;
                scan.unsaved |= FactsFiles.isUnsaved(file);
                read(file, scan);
            }
        }

        return scan;
    }

    private void read(VirtualFile file, Scan scan) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile == null) {
            return;
        }
        String path = FactsFiles.relativePath(project, file);
        for (FunctionReference call : PsiTreeUtil.findChildrenOfType(psiFile, FunctionReference.class)) {
            ProgressManager.checkCanceled();
            PsiElement argument = contextArgument(call);
            if (argument != null) {
                collect(argument, psiFile, path, scan);
            }
        }
    }

    /**
     * The argument a call names a context in, or {@code null} when the call is not one that does.
     * {@code (new Bootstrap())(...)} is a call on the object rather than on a method, so it reaches
     * the tree as a plain function call whose first child is the parenthesized {@code new}.
     */
    @Nullable
    private static PsiElement contextArgument(FunctionReference call) {
        PsiElement[] parameters = call.getParameters();
        if (parameters.length == 0) {
            return null;
        }
        if (call instanceof MethodReference method) {
            return GET_INSTANCE.equalsIgnoreCase(method.getName())
                && isNamed(method.getClassReference(), INJECTOR)
                ? parameters[0]
                : null;
        }

        return isNewOf(call.getFirstChild(), BOOTSTRAP) ? parameters[0] : null;
    }

    private static boolean isNewOf(@Nullable PsiElement element, String className) {
        if (!(element instanceof ParenthesizedExpression parenthesized)) {
            return false;
        }
        PhpPsiElement inner = parenthesized.getArgument();

        return inner instanceof NewExpression created && isNamed(created.getClassReference(), className);
    }

    private static boolean isNamed(@Nullable PsiElement reference, String className) {
        return reference instanceof ClassReference named && className.equalsIgnoreCase(named.getName());
    }

    /**
     * The contexts an argument expression states. Only the positions whose value the call receives
     * are read: the condition of {@code PHP_SAPI === 'cli-server' ? 'hal-app' : 'prod-hal-app'}
     * holds a string too, and it is not a context -- it is what the entry point tests to choose
     * one.
     */
    private static void collect(PsiElement argument, PsiFile file, String path, Scan scan) {
        String literal = PhpSource.stringValue(argument);
        if (literal != null) {
            if (!literal.isBlank()) {
                scan.found(literal, path, FactsFiles.lineOf(file, argument.getTextOffset()));
            }

            return;
        }
        if (argument instanceof TernaryExpression ternary) {
            // A short ternary -- $context ?: 'prod-app' -- states its first value as its condition.
            collectOrCount(ternary.isShort() ? ternary.getCondition() : ternary.getTrueVariant(), file, path, scan);
            collectOrCount(ternary.getFalseVariant(), file, path, scan);

            return;
        }
        // Only ?? states an alternative value; every other binary expression -- a concatenation
        // above all -- builds one this cannot read out of the parts.
        if (argument instanceof BinaryExpression binary
            && binary.getOperationType() == PhpTokenTypes.opCOALESCE) {
            collectOrCount(binary.getLeftOperand(), file, path, scan);
            collectOrCount(binary.getRightOperand(), file, path, scan);

            return;
        }
        scan.unreadable++;
    }

    private static void collectOrCount(@Nullable PsiElement element, PsiFile file, String path, Scan scan) {
        if (element == null) {
            scan.unreadable++;

            return;
        }
        collect(element, file, path, scan);
    }

    /** Where a context is written. A context named twice is one context and two places. */
    private record Site(String filePath, @Nullable Integer line) {

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("filePath", filePath);
            if (line != null) {
                json.addProperty("line", line);
            }

            return json;
        }
    }

    private static final class Scan {

        /** Insertion-ordered: the first context an entry point names is the first offered. */
        private final Map<String, List<Site>> contexts = new LinkedHashMap<>();
        private int files;
        private int filesSkipped;
        private int unreadable;
        private boolean unsaved;

        void found(String context, String path, @Nullable Integer line) {
            List<Site> sites = contexts.computeIfAbsent(context, key -> new ArrayList<>());
            Site site = new Site(path, line);
            if (!sites.contains(site)) {
                sites.add(site);
            }
        }
    }
}
