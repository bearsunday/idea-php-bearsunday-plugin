package idea.bear.sunday.template;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.twig.TwigLanguage;
import com.jetbrains.twig.elements.TwigElementTypes;
import idea.bear.sunday.Settings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class TwigSupport implements TemplateEngineSupport {

    public static final TwigSupport INSTANCE = new TwigSupport();

    private static final String EXTENSION = ".html.twig";

    private TwigSupport() {
    }

    @Override
    public boolean accepts(@NotNull VirtualFile file, @NotNull Project project) {
        return file.getName().endsWith(EXTENSION);
    }

    @Nullable
    @Override
    public String extractVariableName(@NotNull PsiElement element) {
        PsiFile containingFile = element.getContainingFile();
        if (containingFile == null || containingFile.getLanguage() != TwigLanguage.INSTANCE) {
            return null;
        }
        if (element.getNode() == null) {
            return null;
        }
        if (element.getNode().getElementType() != TwigElementTypes.VARIABLE_REFERENCE) {
            return null;
        }
        if (!isInsidePrintBlock(element)) {
            return null;
        }
        return element.getText();
    }

    @NotNull
    @Override
    public List<VirtualFile> resolveTemplates(@NotNull PhpClass resourceClass) {
        String appRoot = TemplateUtils.appRootOf(resourceClass);
        String relPath = TemplateUtils.relativeFromResource(resourceClass);
        if (appRoot == null || relPath == null || !relPath.endsWith(".php")) {
            return Collections.emptyList();
        }
        String templateRel = relPath.substring(0, relPath.length() - ".php".length()) + EXTENSION;

        Collection<String> twigPaths = Settings.getInstance(resourceClass.getProject()).twigTemplatePaths;
        List<VirtualFile> results = new ArrayList<>();
        for (String twigPath : twigPaths) {
            String absPath = appRoot + "/" + TemplateUtils.trimSlash(twigPath) + "/" + templateRel;
            VirtualFile candidate = LocalFileSystem.getInstance().findFileByPath(absPath);
            if (candidate != null) {
                results.add(candidate);
            }
        }
        return results;
    }

    @Nullable
    @Override
    public PhpClass resolveResourceClass(@NotNull VirtualFile templateFile, @NotNull Project project) {
        if (!templateFile.getName().endsWith(EXTENSION)) {
            return null;
        }
        String filePath = templateFile.getPath();
        Collection<String> twigPaths = Settings.getInstance(project).twigTemplatePaths;
        for (String twigPath : twigPaths) {
            String segment = "/" + TemplateUtils.trimSlash(twigPath) + "/";
            int idx = filePath.lastIndexOf(segment);
            if (idx < 0) {
                continue;
            }
            String appRoot = filePath.substring(0, idx);
            String relInTemplates = filePath.substring(idx + segment.length());
            if (!relInTemplates.endsWith(EXTENSION)) {
                continue;
            }
            String classRel = relInTemplates.substring(0, relInTemplates.length() - EXTENSION.length()) + ".php";
            String classAbsPath = appRoot + TemplateUtils.RESOURCE_DIR_SEGMENT + classRel;
            PhpClass cls = TemplateUtils.findClassByAbsolutePath(project, classAbsPath);
            if (cls != null) {
                return cls;
            }
        }
        return null;
    }

    private static boolean isInsidePrintBlock(@NotNull PsiElement element) {
        PsiElement current = element.getParent();
        while (current != null) {
            if (current.getNode() != null && current.getNode().getElementType() == TwigElementTypes.PRINT_BLOCK) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }
}
