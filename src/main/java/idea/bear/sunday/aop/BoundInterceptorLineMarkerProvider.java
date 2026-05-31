package idea.bear.sunday.aop;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiElement;
import com.jetbrains.php.lang.psi.elements.ClassReference;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class BoundInterceptorLineMarkerProvider extends RelatedItemLineMarkerProvider {
    private static final Icon BEAR_ICON = IconLoader.getIcon("/icons/bear.png", BoundInterceptorLineMarkerProvider.class);

    @Override
    public String getName() {
        return "BEAR.Sunday bound interceptor";
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
        if (!(element instanceof ClassReference classReference)) {
            return;
        }

        if (InterceptorNavigationUtil.findAttributeClassReference(classReference) != classReference) {
            return;
        }

        Project project = element.getProject();
        if (DumbService.isDumb(project)) {
            return;
        }

        String annotationFqn = InterceptorBindingIndexUtil.normalizeFqn(classReference.getFQN());
        List<String> interceptorFqns = InterceptorNavigationUtil.findInterceptorFqns(annotationFqn, project);
        if (interceptorFqns.isEmpty()) {
            return;
        }

        PsiElement[] targets = InterceptorNavigationUtil.findInterceptorTargets(annotationFqn, project);
        if (targets.length == 0) {
            return;
        }

        LineMarkerInfo<PsiElement> marker = NavigationGutterIconBuilder.create(BEAR_ICON)
            .setTargets(Arrays.asList(targets))
            .setTooltipText(buildTooltip(interceptorFqns))
            .setPopupTitle("Bound Interceptors")
            .setEmptyPopupText("No bound interceptor found")
            .createLineMarkerInfo(classReference);
        result.add((RelatedItemLineMarkerInfo<?>) marker);
    }

    private static String buildTooltip(List<String> interceptorFqns) {
        String names = interceptorFqns.stream()
            .map(BoundInterceptorLineMarkerProvider::shortName)
            .distinct()
            .collect(Collectors.joining(", "));

        return interceptorFqns.size() == 1
            ? "Bound interceptor: " + names
            : "Bound interceptors: " + names;
    }

    private static String shortName(String fqn) {
        int index = fqn.lastIndexOf('\\');
        return index >= 0 ? fqn.substring(index + 1) : fqn;
    }
}
