package idea.bear.sunday.annotation;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl;
import com.jetbrains.php.lang.documentation.phpdoc.psi.impl.tags.PhpDocTagImpl;
import com.jetbrains.php.lang.psi.elements.impl.PhpAttributeImpl;
import com.jetbrains.php.lang.psi.elements.impl.StringLiteralExpressionImpl;
import idea.bear.sunday.Settings;
import idea.bear.sunday.util.UriUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class AnnotationOrAttributeGotoDeclarationHandler implements GotoDeclarationHandler {

    final String[] targetAnnotations = {"@Query", "@Named"};
    final String[] targetAttributes = {"DbQuery", "Query", "Named"};

    @Nullable
    @Override
    public PsiElement[] getGotoDeclarationTargets(PsiElement psiElement, int i, Editor editor) {

        if (psiElement == null) {
            return new PsiElement[0];
        }

        PsiElement context = psiElement.getContext();
        if (!(context instanceof StringLiteralExpressionImpl stringLiteralExpression)) {
            return new PsiElement[0];
        }

        String contents = stringLiteralExpression.getContents();
        String resourceName = UriUtil.getUriValue(contents);
        if (resourceName == null) {
            return new PsiElement[0];
        }

        String name;
        PsiElement childContext = context.getParent().getParent().getFirstChild().getContext();
        if (childContext instanceof PhpDocTagImpl phpDocTagImpl) {
            name = phpDocTagImpl.getName();
        } else if (childContext instanceof PhpAttributeImpl phpAttributeImpl) {
            name = phpAttributeImpl.getName();
            if (name == null) {
                return new PsiElement[0];
            }
        } else {
            return new PsiElement[0];
        }

        Project project = psiElement.getProject();
        Settings settings = Settings.getInstance(project);
        // SQL
        if (Arrays.asList(this.targetAnnotations).contains(name) || Arrays.asList(this.targetAttributes).contains(name)) {
            String currentFilePath = editor.getVirtualFile().getPath();
            return this.sqlGoto(resourceName, project, currentFilePath, settings);
        }
        // JsonSchema
        if (name.equals("JsonSchema")) {
            return this.jsonSchemaGoto(resourceName, context, project, settings);
        }

        return new PsiElement[0];
    }

    private PsiElement @NotNull [] sqlGoto(
            @NotNull String resourceName,
            @NotNull Project project,
            @NotNull String currentFilePath,
            @NotNull Settings settings
    ) {

        final Collection<String> sqlPaths = settings.sqlPaths;
        final String[] names = resourceName.replace("\"", "").split(",");
        final List<PsiElement> psiElements = new ArrayList<>();

        PsiManager psiManager = PsiManager.getInstance(project);
        for (String name : names) {
            String sqlFileName;
            VirtualFile targetFile;

            if (name.contains("=")) {
                sqlFileName = name.split("=")[1];
            } else if (currentFilePath.toLowerCase().contains("preview") && name.contains("original-")) {
                sqlFileName = name.replace("original-", "");
            } else {
                sqlFileName = name;
            }
            sqlFileName += ".sql";

            for (String sqlPath : sqlPaths) {
                if (!sqlPath.endsWith("/")) {
                    sqlPath += "/";
                }

                targetFile = project.getBaseDir().findFileByRelativePath(sqlPath + sqlFileName);
                if (targetFile == null) {
                    continue;
                }

                PsiFile psiFile = psiManager.findFile(targetFile);
                psiElements.add(psiFile);
            }
        }

        if (psiElements.isEmpty()) {
            return new PsiElement[0];
        }

        return psiElements.toArray(new PsiElement[0]);
    }

    private PsiElement @NotNull [] jsonSchemaGoto(
            @NotNull String resourceName,
            @NotNull PsiElement stringLiteral, Project project,
            @NotNull Settings settings
    ) {

        if (!resourceName.contains(".json")) {
            return new PsiElement[0];
        }

        String jsonPath;
        PsiElement matchedSibling = this.getJsonSchemaMatchedSibling(stringLiteral);
        if (matchedSibling.textMatches("schema")) {
            jsonPath = settings.jsonSchemaPath;
        } else if (matchedSibling.textMatches("params")) {
            jsonPath = settings.jsonValidatePath;
        } else {
            jsonPath = settings.jsonSchemaPath;
        }

        if (!jsonPath.endsWith("/")) {
            jsonPath += "/";
        }

        String jsonFilePath = jsonPath + resourceName;
        VirtualFile targetFile = project.getBaseDir().findFileByRelativePath(jsonFilePath);
        if (targetFile == null) {
            return new PsiElement[0];
        }

        List<PsiElement> psiElements = new ArrayList<>();
        PsiFile psiFile = PsiManager.getInstance(project).findFile(targetFile);
        psiElements.add(psiFile);

        return psiElements.toArray(new PsiElement[0]);
    }

    private PsiElement getJsonSchemaMatchedSibling(PsiElement stringLiteral) {

        PsiElement sibiling = stringLiteral.getPrevSibling();
        if (sibiling == null) {
            // pattern: file name only / ex: #[JsonSchema('user.json')]
            return stringLiteral;
        } else if (sibiling instanceof PsiWhiteSpaceImpl) {
            // pattern: with whitespace / ex: #[JsonSchema(schema: 'user.json')]
            sibiling = sibiling.getPrevSibling();
        } else {
            // pattern: without whitespace / ex: #[JsonSchema(schema:'user.json')]
        }

        sibiling = sibiling.getPrevSibling();
        return sibiling;
    }
}