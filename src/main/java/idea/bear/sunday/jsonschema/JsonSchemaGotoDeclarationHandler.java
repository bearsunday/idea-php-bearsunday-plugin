package idea.bear.sunday.jsonschema;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl;
import idea.bear.sunday.Settings;
import idea.bear.sunday.util.PhpDocAttributeTargets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Navigates from a BEAR.Resource JSON Schema reference ({@code @JsonSchema} or the
 * {@code #[JsonSchema]} attribute) to the referenced {@code .json} file under the
 * configured response/request schema paths.
 */
public class JsonSchemaGotoDeclarationHandler implements GotoDeclarationHandler {

    private static final String JSON_SCHEMA = "JsonSchema";

    @Nullable
    @Override
    public PsiElement[] getGotoDeclarationTargets(PsiElement psiElement, int i, Editor editor) {

        PhpDocAttributeTargets.Target target = PhpDocAttributeTargets.resolve(psiElement);
        if (target == null) {
            return new PsiElement[0];
        }

        if (!JSON_SCHEMA.equals(target.name())) {
            return new PsiElement[0];
        }

        return this.jsonSchemaGoto(target.resourceName(), target.literal(), psiElement.getProject());
    }

    private PsiElement @NotNull [] jsonSchemaGoto(
            @NotNull String resourceName,
            @NotNull PsiElement stringLiteral,
            @NotNull Project project
    ) {
        if (!resourceName.contains(".json")) {
            return new PsiElement[0];
        }

        Settings settings = Settings.getInstance(project);
        Collection<String> jsonPaths;
        PsiElement matchedSibling = this.getJsonSchemaSibling(stringLiteral);
        if (matchedSibling.textMatches("params")) {
            jsonPaths = settings.jsonValidatePath;
        } else {
            jsonPaths = settings.jsonSchemaPath;
        }

        VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
        if (baseDir == null) {
            return new PsiElement[0];
        }

        List<PsiElement> psiElements = new ArrayList<>();
        PsiManager psiManager = PsiManager.getInstance(project);

        for (String jsonPath : jsonPaths) {
            if (!jsonPath.endsWith("/")) {
                jsonPath += "/";
            }

            String jsonFilePath = jsonPath + resourceName;
            VirtualFile targetFile = baseDir.findFileByRelativePath(jsonFilePath);
            if (targetFile == null) {
                continue;
            }

            PsiFile psiFile = psiManager.findFile(targetFile);
            if (psiFile != null) {
                psiElements.add(psiFile);
            }
        }

        if (psiElements.isEmpty()) {
            return new PsiElement[0];
        }

        return psiElements.toArray(new PsiElement[0]);
    }

    private PsiElement getJsonSchemaSibling(PsiElement stringLiteral) {

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