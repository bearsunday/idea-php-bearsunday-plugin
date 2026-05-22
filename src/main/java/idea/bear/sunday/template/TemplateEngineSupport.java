package idea.bear.sunday.template;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public interface TemplateEngineSupport {

    boolean accepts(@NotNull VirtualFile file, @NotNull Project project);

    @NotNull
    List<VirtualFile> resolveTemplates(@NotNull PhpClass resourceClass);

    @Nullable
    PhpClass resolveResourceClass(@NotNull VirtualFile templateFile, @NotNull Project project);

    @Nullable
    String extractVariableName(@NotNull PsiElement element);

    @Nullable
    static TemplateEngineSupport forFile(@NotNull VirtualFile file, @NotNull Project project) {
        for (TemplateEngineSupport support : List.of(TwigSupport.INSTANCE, QiqSupport.INSTANCE)) {
            if (support.accepts(file, project)) {
                return support;
            }
        }
        return null;
    }
}
