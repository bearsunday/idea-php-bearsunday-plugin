package idea.bear.sunday.body;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class GenerateBodyJsonSchemaIntention extends PsiElementBaseIntentionAction {

    private static final String TEXT = "Generate BEAR body JSON Schema";
    private final BodyTypeCollector collector = new BodyTypeCollector();

    @Override
    public @NotNull String getText() {
        return TEXT;
    }

    @Override
    public @NotNull String getFamilyName() {
        return TEXT;
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        PhpClass phpClass = findPhpClass(element);
        if (phpClass == null || BodyJsonSchemaPath.fromClass(project, phpClass) == null) {
            return false;
        }

        return collector.collect(phpClass).isPresent();
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) throws IncorrectOperationException {
        PhpClass phpClass = findPhpClass(element);
        if (phpClass == null) {
            return;
        }

        Optional<BodyTypeCollection> bodyTypes = collector.collect(phpClass);
        Path schemaPath = BodyJsonSchemaPath.fromClass(project, phpClass);
        if (bodyTypes.isEmpty() || schemaPath == null) {
            return;
        }

        BodyType schemaType = BodyJsonSchemaTypeSelector.select(bodyTypes.get());
        String schema = BodyJsonSchemaRenderer.render(schemaType) + "\n";

        WriteCommandAction.runWriteCommandAction(project, TEXT, null, () -> writeSchema(schemaPath, schema));
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    private PhpClass findPhpClass(PsiElement element) {
        if (element instanceof PhpClass phpClass) {
            return phpClass;
        }

        return PsiTreeUtil.getParentOfType(element, PhpClass.class);
    }

    private void writeSchema(Path schemaPath, String schema) {
        try {
            Path parent = schemaPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(schemaPath, schema, StandardCharsets.UTF_8);
            LocalFileSystem.getInstance().refreshIoFiles(java.util.List.of(schemaPath.toFile()));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write JSON Schema: " + schemaPath, exception);
        }
    }

}
