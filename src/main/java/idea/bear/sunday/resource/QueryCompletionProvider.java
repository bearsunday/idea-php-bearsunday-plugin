package idea.bear.sunday.resource;

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
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression;
import com.jetbrains.php.lang.psi.elements.ArrayHashElement;
import com.jetbrains.php.lang.psi.elements.FieldReference;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.MethodReference;
import com.jetbrains.php.lang.psi.elements.Parameter;
import com.jetbrains.php.lang.psi.elements.ParameterList;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression;
import idea.bear.sunday.util.ResourceHttpMethods;
import idea.bear.sunday.util.UriUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Completes the query parameter keys of a BEAR.Resource request call from the parameters of the
 * target resource method.
 *
 * <p>Supported call forms (the caret is inside the query array):
 * <ul>
 *   <li>{@code $resource->get('app://self/weekday', ['<caret>'])} — verb carries the URI</li>
 *   <li>{@code $resource->uri('app://self/weekday', ['<caret>'])} — uri() carries the URI</li>
 *   <li>{@code $resource->get->uri('app://self/weekday')->withQuery(['<caret>'])} — chain</li>
 * </ul>
 * The verb is read from the method name, or from an earlier {@code ->get}/{@code ->post}/...
 * property in the chain (defaulting to {@code get}). The URI is resolved to the resource class
 * the same way goto does, and the matching {@code on{Verb}} method's parameter names are offered
 * as array keys, excluding keys already present in the array.
 */
public class QueryCompletionProvider extends CompletionProvider<CompletionParameters> {

    /** A candidate query key together with the resource method it comes from. */
    public record QueryKey(@NotNull String name, @NotNull String method) {
    }

    @Override
    public void addCompletions(@NotNull CompletionParameters parameters,
                               @NotNull ProcessingContext context,
                               @NotNull CompletionResultSet resultSet) {

        List<QueryKey> keys = queryKeys(parameters.getPosition(), parameters.getEditor());
        for (QueryKey key : keys) {
            resultSet.addElement(LookupElementBuilder.create(key.name())
                .withTypeText(key.method() + " parameter", true));
        }
    }

    /**
     * Returns the query parameter keys offered at the caret position, or an empty list when the
     * caret is not inside a BEAR.Resource request query array (or the target resource cannot be
     * resolved).
     */
    @NotNull
    public static List<QueryKey> queryKeys(@Nullable PsiElement position, @Nullable Editor editor) {
        if (position == null) {
            return List.of();
        }

        ArrayCreationExpression array = PsiTreeUtil.getParentOfType(position, ArrayCreationExpression.class);
        if (array == null) {
            return List.of();
        }

        PsiElement arrayParent = array.getParent();
        if (!(arrayParent instanceof ParameterList parameterList)) {
            return List.of();
        }

        PsiElement refElement = parameterList.getParent();
        if (!(refElement instanceof MethodReference methodRef)) {
            return List.of();
        }

        String refName = methodRef.getName();
        PsiElement[] callArgs = methodRef.getParameters();

        StringLiteralExpression uriLiteral;
        String verb;
        int queryIndex;

        if (ResourceHttpMethods.isVerb(refName)) {
            // Form A: $resource-><verb>($uri, $queryArray)
            queryIndex = 1;
            verb = refName;
            uriLiteral = firstStringLiteral(callArgs);
        } else if ("uri".equals(refName)) {
            // Form B: $resource->[verb->]uri($uri, $queryArray)
            queryIndex = 1;
            verb = resolveChain(methodRef.getClassReference()).verb();
            uriLiteral = firstStringLiteral(callArgs);
        } else if ("withQuery".equals(refName)) {
            // Form C: $resource->[verb->]uri($uri)->withQuery($queryArray)
            queryIndex = 0;
            ChainInfo info = resolveChain(methodRef.getClassReference());
            verb = info.verb();
            uriLiteral = info.uriLiteral();
        } else {
            return List.of();
        }

        if (uriLiteral == null) {
            return List.of();
        }
        if (queryIndex >= callArgs.length || callArgs[queryIndex] != array) {
            return List.of();
        }
        String methodName = ResourceHttpMethods.methodName(verb);
        if (methodName == null) {
            methodName = ResourceHttpMethods.defaultMethodName();
        }

        Project project = position.getProject();
        PhpClass resourceClass = resolveResourceClass(uriLiteral, project, editor, position);
        if (resourceClass == null) {
            return List.of();
        }
        Method method = resourceClass.findMethodByName(methodName);
        if (method == null) {
            return List.of();
        }

        Set<String> usedKeys = collectUsedKeys(array);
        List<QueryKey> result = new ArrayList<>();
        for (PsiElement p : method.getParameters()) {
            if (!(p instanceof Parameter param)) {
                continue;
            }
            String name = param.getName();
            if (name == null || name.isEmpty() || usedKeys.contains(name)) {
                continue;
            }
            result.add(new QueryKey(name, methodName));
        }
        return result;
    }

    @Nullable
    private static StringLiteralExpression firstStringLiteral(PsiElement[] args) {
        if (args.length == 0) {
            return null;
        }
        return args[0] instanceof StringLiteralExpression literal ? literal : null;
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
            if (node instanceof MethodReference mr) {
                String name = mr.getName();
                if (uriLiteral == null && "uri".equals(name)) {
                    PsiElement[] args = mr.getParameters();
                    if (args.length > 0 && args[0] instanceof StringLiteralExpression literal) {
                        uriLiteral = literal;
                    }
                }
                if (verb == null && ResourceHttpMethods.isVerb(name)) {
                    verb = name;
                }
                node = mr.getClassReference();
            } else if (node instanceof FieldReference fr) {
                String name = fr.getName();
                if (verb == null && ResourceHttpMethods.isVerb(name)) {
                    verb = name;
                }
                node = fr.getClassReference();
            } else {
                break;
            }
        }

        return new ChainInfo(verb, uriLiteral);
    }

    @NotNull
    private static Set<String> collectUsedKeys(@NotNull ArrayCreationExpression array) {
        Set<String> used = new HashSet<>();
        for (ArrayHashElement element : array.getHashElements()) {
            PsiElement key = element.getKey();
            if (key instanceof StringLiteralExpression literal) {
                used.add(literal.getContents());
            } else if (key != null) {
                used.add(key.getText());
            }
        }
        return used;
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