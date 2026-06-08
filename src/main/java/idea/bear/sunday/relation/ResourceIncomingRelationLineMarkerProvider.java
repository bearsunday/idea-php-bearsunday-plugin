package idea.bear.sunday.relation;

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.psi.elements.Method;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ResourceIncomingRelationLineMarkerProvider extends RelatedItemLineMarkerProvider {

    private static final Icon BEAR_ICON = IconLoader.getIcon("/icons/bear.png", ResourceIncomingRelationLineMarkerProvider.class);

    @Override
    public String getName() {
        return "BEAR.Sunday incoming resource relations";
    }

    @Override
    public Icon getIcon() {
        return BEAR_ICON;
    }

    @Override
    protected void collectNavigationMarkers(
        @NotNull PsiElement element,
        @NotNull Collection<? super RelatedItemLineMarkerInfo<?>> result
    ) {
        Method method = PsiTreeUtil.getParentOfType(element, Method.class);
        if (method == null || method.getContainingClass() == null) {
            return;
        }

        PsiElement markerElement = findLineMarkerAnchor(method);
        if (markerElement != element) {
            return;
        }

        Project project = element.getProject();
        if (DumbService.isDumb(project)) {
            return;
        }

        String targetResourcePath = currentResourcePath(method, project);
        if (targetResourcePath == null) {
            return;
        }

        List<ResourceRelation> relations = ResourceRelationIndex.findIncoming(targetResourcePath, project);
        relations = relations.stream()
            .filter(relation -> relation.targetMethod().equalsIgnoreCase(method.getName()))
            .toList();
        if (relations.isEmpty()) {
            return;
        }

        List<ResourceRelationTarget> targets = resolveTargets(relations, project);
        if (targets.isEmpty()) {
            return;
        }

        targets.sort(Comparator
            .comparing((ResourceRelationTarget target) -> target.relation().sourceFilePath())
            .thenComparing(target -> target.relation().kind())
            .thenComparing(target -> target.relation().rel())
            .thenComparing(target -> target.relation().rawTargetUri()));

        NavigationGutterIconBuilder<ResourceRelationTarget> builder = NavigationGutterIconBuilder.create(
            BEAR_ICON,
            target -> Collections.singletonList(target.element())
        );

        result.add(builder
            .setTargets(targets)
            .setNamer(target -> target.relation().popupText())
            .setTooltipText(buildTooltip(targets.size()))
            .setPopupTitle("Incoming Resource Relations")
            .setEmptyPopupText("No incoming resource relations found")
            .createLineMarkerInfo(markerElement));
    }

    private static String buildTooltip(int count) {
        return count == 1 ? "1 incoming resource relation" : count + " incoming resource relations";
    }

    private static PsiElement findLineMarkerAnchor(Method method) {
        PsiElement anchor = method.getNameIdentifier();
        if (anchor == null) {
            return method;
        }

        while (anchor.getFirstChild() != null) {
            anchor = anchor.getFirstChild();
        }

        return anchor;
    }

    private static String currentResourcePath(Method method, Project project) {
        VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
        PsiFile containingFile = method.getContainingFile();
        VirtualFile virtualFile = containingFile == null ? null : containingFile.getVirtualFile();
        if (baseDir == null || virtualFile == null) {
            return null;
        }

        String relativePath = VfsUtil.getRelativePath(virtualFile, baseDir, '/');
        if (relativePath == null
            || (!relativePath.startsWith("src/Resource/App/")
            && !relativePath.startsWith("src/Resource/Page/"))
        ) {
            return null;
        }

        return relativePath;
    }

    private static List<ResourceRelationTarget> resolveTargets(List<ResourceRelation> relations, Project project) {
        VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
        if (baseDir == null) {
            return Collections.emptyList();
        }

        PsiManager psiManager = PsiManager.getInstance(project);
        List<ResourceRelationTarget> targets = new ArrayList<>();
        for (ResourceRelation relation : relations) {
            VirtualFile sourceFile = baseDir.findFileByRelativePath(relation.sourceFilePath());
            if (sourceFile == null) {
                continue;
            }

            PsiFile psiFile = psiManager.findFile(sourceFile);
            if (psiFile == null) {
                continue;
            }

            PsiElement target = findAttributeElement(psiFile, relation.attributeTextOffset());
            targets.add(new ResourceRelationTarget(relation, target));
        }

        return targets;
    }

    private static PsiElement findAttributeElement(PsiFile psiFile, int offset) {
        if (psiFile.getTextLength() == 0) {
            return psiFile;
        }

        int safeOffset = Math.max(0, Math.min(offset, psiFile.getTextLength() - 1));
        PsiElement element = psiFile.findElementAt(safeOffset);
        return element == null ? psiFile : element;
    }

    private record ResourceRelationTarget(ResourceRelation relation, PsiElement element) {
    }
}
