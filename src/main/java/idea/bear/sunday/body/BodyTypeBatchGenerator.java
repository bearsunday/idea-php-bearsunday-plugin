package idea.bear.sunday.body;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.psi.elements.PhpClass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class BodyTypeBatchGenerator {

    static final String COMMAND_NAME = "Generate BEAR body type";
    private static final Set<String> SKIPPED_DIRECTORY_NAMES = Set.of(
        ".git",
        ".idea",
        ".gradle",
        ".intellijPlatform",
        "build",
        "node_modules",
        "var",
        "vendor"
    );

    private final BodyTypeCollector collector = new BodyTypeCollector();

    int generate(Project project, VirtualFile[] roots) {
        List<BodyTypeGenerationTarget> targets = collectTargets(project, roots);
        if (targets.isEmpty()) {
            return 0;
        }

        int[] updated = {0};
        WriteCommandAction.runWriteCommandAction(project, COMMAND_NAME, null, () -> {
            for (BodyTypeGenerationTarget target : targets) {
                if (!target.phpClass().isValid()) {
                    continue;
                }
                if (BodyTypeDocBlockWriter.update(project, target.phpClass(), target.bodyTypes())) {
                    updated[0]++;
                }
            }
        });
        return updated[0];
    }

    List<BodyTypeGenerationTarget> collectTargets(Project project, VirtualFile[] roots) {
        Computable<List<BodyTypeGenerationTarget>> computable = () -> collectTargetsInReadAction(project, roots);

        return ApplicationManager.getApplication().runReadAction(computable);
    }

    private List<BodyTypeGenerationTarget> collectTargetsInReadAction(Project project, VirtualFile[] roots) {
        List<BodyTypeGenerationTarget> targets = new ArrayList<>();
        PsiManager psiManager = PsiManager.getInstance(project);
        for (VirtualFile phpFile : phpFiles(roots)) {
            PsiFile psiFile = psiManager.findFile(phpFile);
            if (psiFile == null) {
                continue;
            }

            Collection<PhpClass> phpClasses = PsiTreeUtil.findChildrenOfType(psiFile, PhpClass.class);
            for (PhpClass phpClass : phpClasses) {
                Optional<BodyTypeCollection> bodyTypes = collector.collect(phpClass);
                if (bodyTypes.isPresent()) {
                    targets.add(new BodyTypeGenerationTarget(phpClass, bodyTypes.get()));
                }
            }
        }

        targets.sort(Comparator
            .comparing(BodyTypeBatchGenerator::path)
            .thenComparing(target -> target.phpClass().getTextRange().getStartOffset()));

        return targets;
    }

    private static String path(BodyTypeGenerationTarget target) {
        PsiFile containingFile = target.phpClass().getContainingFile();
        VirtualFile virtualFile = containingFile == null ? null : containingFile.getVirtualFile();

        return virtualFile == null ? "" : virtualFile.getPath();
    }

    static boolean isCandidateRoot(VirtualFile virtualFile) {
        if (virtualFile == null || !virtualFile.isValid()) {
            return false;
        }
        if (virtualFile.isDirectory()) {
            return !shouldSkipDirectory(virtualFile);
        }

        return isPhpFile(virtualFile);
    }

    private static List<VirtualFile> phpFiles(VirtualFile[] roots) {
        Map<String, VirtualFile> files = new LinkedHashMap<>();
        for (VirtualFile root : roots) {
            collectPhpFiles(root, files);
        }

        return new ArrayList<>(files.values());
    }

    private static void collectPhpFiles(VirtualFile virtualFile, Map<String, VirtualFile> files) {
        if (virtualFile == null || !virtualFile.isValid()) {
            return;
        }
        if (virtualFile.isDirectory()) {
            if (shouldSkipDirectory(virtualFile)) {
                return;
            }
            VirtualFile[] children = virtualFile.getChildren();
            Arrays.sort(children, Comparator.comparing(VirtualFile::getName));
            for (VirtualFile child : children) {
                collectPhpFiles(child, files);
            }
            return;
        }
        if (!isPhpFile(virtualFile)) {
            return;
        }

        files.put(virtualFile.getPath(), virtualFile);
    }

    private static boolean shouldSkipDirectory(VirtualFile virtualFile) {
        return SKIPPED_DIRECTORY_NAMES.contains(virtualFile.getName());
    }

    private static boolean isPhpFile(VirtualFile virtualFile) {
        return "php".equalsIgnoreCase(virtualFile.getExtension());
    }

    record BodyTypeGenerationTarget(PhpClass phpClass, BodyTypeCollection bodyTypes) {
    }

}
