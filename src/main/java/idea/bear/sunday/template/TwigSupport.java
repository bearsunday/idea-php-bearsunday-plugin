package idea.bear.sunday.template;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.twig.TwigLanguage;
import com.jetbrains.twig.elements.TwigElementTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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
        // src/Resource/Page/Index.php -> the template is named Page/Index.html.twig, located
        // wherever the project's Twig paths point. Find it by name and keep the candidates whose
        // path is that relative file under this app root, so the template directory needs no config.
        String templateRel = relPath.substring(0, relPath.length() - ".php".length()) + EXTENSION;
        List<VirtualFile> results = new ArrayList<>();
        for (VirtualFile candidate : TemplateUtils.filesNamed(resourceClass.getProject(), TemplateUtils.fileNameOf(templateRel))) {
            if (TemplateUtils.isUnderAppRootWithRel(candidate.getPath(), appRoot, templateRel)) {
                results.add(candidate);
            }
        }
        return results;
    }

    @Nullable
    @Override
    public PhpClass resolveResourceClass(@NotNull VirtualFile templateFile, @NotNull Project project) {
        String name = templateFile.getName();
        if (!name.endsWith(EXTENSION)) {
            return null;
        }
        String filePath = templateFile.getPath();
        // Reverse of resolveTemplates: Page/Index.html.twig maps back to the resource Page/Index.php
        // under src/Resource/. Look up resource files by that name and keep the one whose own
        // relative path, re-expressed as a template, is this file under the same app root.
        String resourceFileName = name.substring(0, name.length() - EXTENSION.length()) + ".php";
        for (VirtualFile resourceFile : TemplateUtils.filesNamed(project, resourceFileName)) {
            String appRoot = TemplateUtils.appRootOfPath(resourceFile.getPath());
            String resourceRel = TemplateUtils.relativeFromResourcePath(resourceFile.getPath());
            if (appRoot == null || resourceRel == null) {
                continue;
            }
            String templateRel = resourceRel.substring(0, resourceRel.length() - ".php".length()) + EXTENSION;
            if (TemplateUtils.isUnderAppRootWithRel(filePath, appRoot, templateRel)) {
                PhpClass cls = TemplateUtils.findClass(project, resourceFile);
                if (cls != null) {
                    return cls;
                }
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
