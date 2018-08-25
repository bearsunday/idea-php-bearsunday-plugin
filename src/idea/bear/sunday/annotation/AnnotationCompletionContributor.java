package idea.bear.sunday.annotation;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import idea.bear.sunday.BearSundayProjectComponent;

public class AnnotationCompletionContributor extends CompletionContributor {

    public AnnotationCompletionContributor() {
        Project project = ProjectManager.getInstance().getDefaultProject();
        if(!BearSundayProjectComponent.isEnabled(project)) {
            return;
        }

        extend(CompletionType.BASIC, AnnotationPatternHelper.getDocAttribute(), new AttributeCompletionProvider());
        extend(CompletionType.BASIC, AnnotationPatternHelper.getTextIdentifier(), new AttributeTextCompletionProvider());
    }

}
