package idea.bear.sunday.annotation;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionType;

public class AnnotationCompletionContributor extends CompletionContributor {

    public AnnotationCompletionContributor() {
        // @<caret>
        extend(CompletionType.BASIC, AnnotationPatternHelper.getDocBlockTag(), new AnnotationCompletionProvider());

        // @Foo(<caret>)
        extend(CompletionType.BASIC, AnnotationPatternHelper.getDocAttribute(), new AttributeCompletionProvider());

        // @Foo(bar="<caret">)
        extend(CompletionType.BASIC, AnnotationPatternHelper.getTextIdentifier(), new AttributeTextCompletionProvider());
    }

}
