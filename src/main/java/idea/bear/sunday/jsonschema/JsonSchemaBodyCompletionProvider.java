package idea.bear.sunday.jsonschema;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import com.jetbrains.php.lang.psi.elements.ArrayAccessExpression;
import com.jetbrains.php.lang.psi.elements.ClassReference;
import com.jetbrains.php.lang.psi.elements.FieldReference;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.MethodReference;
import com.jetbrains.php.lang.psi.elements.PhpAttribute;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression;
import idea.bear.sunday.Settings;
import idea.bear.sunday.util.JsonSchemaProperties;
import idea.bear.sunday.util.UriUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Completes the {@code ->body['<caret>']} keys of a BEAR.Resource request call from the JSON Schema
 * of the target resource method.
 *
 * <p>For a request like
 * {@code $resource->get('app://self/user', ['id' => 1])->body['<caret>']} the resource class is
 * resolved the same way goto does, the matching {@code on{Verb}} method's {@code #[JsonSchema]}
 * attribute is read for the <em>response</em> schema file (the {@code schema:} argument or the
 * first positional argument), and the top-level {@code properties} of that schema are offered as
 * completion keys.
 *
 * <p>Supported call forms (the caret is inside the body subscript):
 * <ul>
 *   <li>{@code $resource->get('app://self/user', $q)->body['<caret>']} — verb carries the URI</li>
 *   <li>{@code $resource->get->uri('app://self/user')->withQuery($q)->body['<caret>']} — chain</li>
 *   <li>{@code $resource->uri('app://self/user')($q)->body['<caret>']} — invoke form</li>
 * </ul>
 * The verb is read from the method name, or from an earlier {@code ->get}/{@code ->post}/...
 * property in the chain (defaulting to {@code get}).
 */
public class JsonSchemaBodyCompletionProvider extends CompletionProvider<CompletionParameters> {

    @Override
    public void addCompletions(@NotNull CompletionParameters parameters,
                               @NotNull ProcessingContext context,
                               @NotNull CompletionResultSet resultSet) {

        for (String name : bodyKeys(parameters.getPosition(), parameters.getEditor())) {
            resultSet.addElement(LookupElementBuilder.create(name).withTypeText("json schema", true));
        }
    }

    /**
     * Returns the {@code ->body[...]} keys offered at the caret position, or an empty list when the
     * caret is not inside a {@code ->body[...]} subscript of a BEAR.Resource request call (or the
     * target resource / its response JSON Schema cannot be resolved).
     */
    @NotNull
    public static List<String> bodyKeys(@Nullable PsiElement position, @Nullable Editor editor) {
        if (position == null) {
            return List.of();
        }

        ArrayAccessExpression access = PsiTreeUtil.getParentOfType(position, ArrayAccessExpression.class);
        if (access == null) {
            return List.of();
        }

        PsiElement arrayExpr = access.getValue();
        if (!(arrayExpr instanceof FieldReference bodyRef) || !"body".equals(bodyRef.getName())) {
            return List.of();
        }

        Request request = resolveRequest(bodyRef.getClassReference());
        if (request == null || request.uriLiteral() == null) {
            return List.of();
        }

        String methodName = methodName(request.verb());
        if (methodName == null) {
            methodName = "onGet";
        }

        Project project = position.getProject();
        PhpClass resourceClass = resolveResourceClass(request.uriLiteral(), project, editor, position);
        if (resourceClass == null) {
            return List.of();
        }
        Method method = resourceClass.findMethodByName(methodName);
        if (method == null) {
            return List.of();
        }

        String schemaFile = responseSchemaFile(method);
        if (schemaFile == null) {
            return List.of();
        }

        String schemaJson = loadSchemaFile(project, schemaFile, position);
        if (schemaJson == null) {
            return List.of();
        }

        return JsonSchemaProperties.propertyNames(schemaJson);
    }

    private record Request(@Nullable String verb, @Nullable StringLiteralExpression uriLiteral) {
    }

    private static final Map<String, String> VERB_TO_METHOD = Map.of(
        "get", "onGet",
        "post", "onPost",
        "put", "onPut",
        "patch", "onPatch",
        "delete", "onDelete",
        "head", "onHead",
        "options", "onOptions"
    );
    private static final Set<String> VERBS = VERB_TO_METHOD.keySet();

    /** {@code true} if the name is a BEAR.Resource request verb. */
    private static boolean isVerb(@Nullable String name) {
        return name != null && VERBS.contains(name);
    }

    /** The resource method name ({@code onGet}, ...) for a verb, or {@code null} if not a verb. */
    @Nullable
    private static String methodName(@Nullable String verb) {
        return verb == null ? null : VERB_TO_METHOD.get(verb);
    }

    /**
     * Resolves the request verb and URI string literal from the expression the {@code ->body} field
     * is read off. Handles the direct verb/uri/withQuery method calls and the invoke form
     * {@code ->uri($uri)($q)} by descending to the inner request method call.
     */
    @Nullable
    private static Request resolveRequest(@Nullable PsiElement callElement) {
        if (callElement instanceof MethodReference methodRef) {
            String name = methodRef.getName();
            PsiElement[] args = methodRef.getParameters();
            if (isVerb(name)) {
                return new Request(name, firstStringLiteral(args));
            }
            if ("uri".equals(name)) {
                return new Request(resolveChain(methodRef.getClassReference()).verb(), firstStringLiteral(args));
            }
            if ("withQuery".equals(name)) {
                ChainInfo info = resolveChain(methodRef.getClassReference());
                return new Request(info.verb(), info.uriLiteral());
            }
            return null;
        }

        if (callElement != null) {
            // Invoke form: $resource->uri($uri)($q) — descend to the inner request method call.
            MethodReference inner = PsiTreeUtil.findChildOfAnyType(callElement, MethodReference.class);
            if (inner != null) {
                return resolveRequest(inner);
            }
        }
        return null;
    }

    private record ChainInfo(@Nullable String verb, @Nullable StringLiteralExpression uriLiteral) {
    }

    /**
     * Walks a BEAR.Resource call chain leftward collecting the request verb (a {@code ->get} /
     * {@code ->post} property) and the URI string argument of an earlier {@code ->uri(...)} call.
     */
    private static ChainInfo resolveChain(@Nullable PsiElement node) {
        String verb = null;
        StringLiteralExpression uriLiteral = null;

        while (node != null && (verb == null || uriLiteral == null)) {
            if (node instanceof MethodReference methodRef) {
                String name = methodRef.getName();
                if (uriLiteral == null && "uri".equals(name)) {
                    PsiElement[] args = methodRef.getParameters();
                    if (args.length > 0 && args[0] instanceof StringLiteralExpression literal) {
                        uriLiteral = literal;
                    }
                }
                if (verb == null && isVerb(name)) {
                    verb = name;
                }
                node = methodRef.getClassReference();
            } else if (node instanceof FieldReference fieldRef) {
                String name = fieldRef.getName();
                if (verb == null && isVerb(name)) {
                    verb = name;
                }
                node = fieldRef.getClassReference();
            } else {
                break;
            }
        }

        return new ChainInfo(verb, uriLiteral);
    }

    @Nullable
    private static StringLiteralExpression firstStringLiteral(PsiElement[] args) {
        if (args.length == 0) {
            return null;
        }
        return args[0] instanceof StringLiteralExpression literal ? literal : null;
    }

    /**
     * Returns the response schema file name declared on the method via a {@code #[JsonSchema]}
     * attribute — the {@code schema:} named argument, or the first positional argument.
     */
    @Nullable
    private static String responseSchemaFile(@NotNull Method method) {
        for (PhpAttribute attribute : method.getAttributes()) {
            if (!"JsonSchema".equals(attributeShortName(attribute))) {
                continue;
            }
            String file = stringContents(attribute.getParameter("schema", 0));
            if (file != null && !file.isBlank()) {
                return file;
            }
        }
        return null;
    }

    @Nullable
    private static String attributeShortName(@NotNull PhpAttribute attribute) {
        String fqn = attribute.getFQN();
        if (fqn != null && !fqn.isBlank()) {
            return shortName(fqn);
        }
        ClassReference classReference = attribute.getClassReference();
        return classReference == null ? null : classReference.getName();
    }

    private static String shortName(@NotNull String fqn) {
        int index = fqn.lastIndexOf('\\');
        return index >= 0 ? fqn.substring(index + 1) : fqn;
    }

    @Nullable
    private static String stringContents(@Nullable PsiElement element) {
        if (element == null) {
            return null;
        }
        if (element instanceof StringLiteralExpression literal) {
            return literal.getContents();
        }
        StringLiteralExpression literal = PsiTreeUtil.findChildOfType(element, StringLiteralExpression.class);
        return literal == null ? null : literal.getContents();
    }

    @Nullable
    private static String loadSchemaFile(@NotNull Project project, @NotNull String schemaFile,
                                         @Nullable PsiElement position) {
        VirtualFile projectDir = findProjectBase(project, position);
        if (projectDir == null) {
            return null;
        }

        Settings settings = Settings.getInstance(project);
        for (String path : settings.jsonSchemaPath) {
            String basePath = path.endsWith("/") ? path : path + "/";
            VirtualFile targetFile = projectDir.findFileByRelativePath(basePath + schemaFile);
            if (targetFile == null) {
                continue;
            }
            try {
                return new String(targetFile.contentsToByteArray(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    private static PhpClass resolveResourceClass(@NotNull StringLiteralExpression uriLiteral,
                                                 @NotNull Project project,
                                                 @Nullable Editor editor,
                                                 @Nullable PsiElement position) {
        String uri = uriLiteral.getContents();
        if (uri == null || uri.isBlank()) {
            return null;
        }

        VirtualFile projectDir = findProjectBase(project, position);
        if (projectDir == null) {
            return null;
        }

        boolean pageContext = false;
        if (editor != null) {
            VirtualFile editorFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
            pageContext = editorFile != null
                && editorFile.getPath().startsWith(projectDir.getPath() + "/src/Resource/Page");
        }

        String relPath = UriUtil.toResourceRelativePath(uri, pageContext);
        if (relPath == null) {
            return null;
        }

        VirtualFile targetFile = projectDir.findFileByRelativePath(relPath);
        if (targetFile == null) {
            return null;
        }
        PsiFile psiFile = PsiManager.getInstance(project).findFile(targetFile);
        if (psiFile == null) {
            return null;
        }

        return PsiTreeUtil.findChildOfType(psiFile, PhpClass.class);
    }

    /**
     * Resolves the project base directory. Prefers {@link ProjectUtil#guessProjectDir(Project)};
     * falls back to walking up from the current file to the directory that contains
     * {@code src/Resource} for environments (e.g. the test fixture) where the project base is not
     * set on the project instance.
     */
    @Nullable
    private static VirtualFile findProjectBase(@NotNull Project project, @Nullable PsiElement position) {
        VirtualFile projectDir = ProjectUtil.guessProjectDir(project);
        if (projectDir != null) {
            return projectDir;
        }

        PsiFile file = position == null ? null : position.getContainingFile();
        VirtualFile parent = file == null ? null : file.getVirtualFile();
        parent = parent == null ? null : parent.getParent();
        while (parent != null) {
            VirtualFile src = parent.findChild("src");
            if (src != null && src.findChild("Resource") != null) {
                return parent;
            }
            parent = parent.getParent();
        }
        return null;
    }
}