package idea.bear.sunday.jsonschema;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.patterns.PlatformPatterns;
import com.jetbrains.php.lang.psi.elements.ArrayAccessExpression;

/**
 * Registers the {@code ->body['<caret>']} JSON Schema completion for PHP files. The provider itself
 * narrows the caret to a body subscript of a BEAR.Resource request call, so a broad pattern is
 * safe.
 */
public class JsonSchemaBodyCompletionContributor extends CompletionContributor {

    public JsonSchemaBodyCompletionContributor() {
        extend(CompletionType.BASIC,
            PlatformPatterns.psiElement().inside(PlatformPatterns.psiElement(ArrayAccessExpression.class)),
            new JsonSchemaBodyCompletionProvider());
    }
}