package idea.bear.sunday.resource;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import idea.bear.sunday.BearSundayProjectComponent;

public class UriCompletionContributor extends CompletionContributor {

    public UriCompletionContributor() {

        Project project = ProjectManager.getInstance().getDefaultProject();
        if(!BearSundayProjectComponent.isEnabled(project)) {
            return;
        }

        extend(
                CompletionType.BASIC, UriElementPatternHelper.getUriDefinition(),
                new ResourceCompletionProvider()
        );
    }

}
