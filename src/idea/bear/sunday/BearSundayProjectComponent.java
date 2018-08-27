package idea.bear.sunday;

import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class BearSundayProjectComponent implements ProjectComponent {

    final private static Logger LOG = Logger.getInstance("BEAR.Sunday Plugin");

    private Project project;

    public BearSundayProjectComponent(Project project) {
        this.project = project;
    }

    public void initComponent() {
    }

    public void disposeComponent() {
    }

    @NotNull
    public String getComponentName() {
        return "BearSundayProjectComponent";
    }

    public void projectOpened() {
    }

    public void projectClosed() {
    }

    public static Logger getLogger() {
        return LOG;
    }

    public static boolean isEnabled(@Nullable Project project) {
        return project != null && Settings.getInstance(project).pluginEnabled;
    }

    public static boolean isEnabled(@Nullable PsiElement psiElement) {
        return psiElement != null && isEnabled(psiElement.getProject());
    }

}
