package idea.bear.sunday.resource;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionType;

public class UriCompletionContributor extends CompletionContributor {

    public UriCompletionContributor() {
        extend(
                CompletionType.BASIC, UriElementPatternHelper.getUriDefinition(),
                new ResourceCompletionProvider()
        );
    }

}
