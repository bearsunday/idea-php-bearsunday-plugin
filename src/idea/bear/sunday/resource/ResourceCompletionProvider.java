package idea.bear.sunday.resource;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.psi.PsiElement;
import com.intellij.util.ProcessingContext;
import idea.bear.sunday.BearSundayProjectComponent;
import idea.bear.sunday.index.ResourceIndex;
import org.jetbrains.annotations.NotNull;

public class ResourceCompletionProvider extends CompletionProvider<CompletionParameters> {

    public void addCompletions(@NotNull CompletionParameters parameters, ProcessingContext context, @NotNull CompletionResultSet resultSet) {

        if(!BearSundayProjectComponent.isEnabled(parameters.getPosition())) {
            return;
        }

        PsiElement element = parameters.getOriginalPosition();

        if(element == null) {
            return;
        }

        for(String uri : ResourceIndex.getNames(element.getProject())) {
            resultSet.addElement(new ResourceLookupElement(uri));
        }
    }

}
