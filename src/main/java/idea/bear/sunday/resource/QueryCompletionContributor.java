package idea.bear.sunday.resource;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.patterns.PsiElementPattern;
import com.intellij.psi.PsiElement;
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression;

/**
 * Registers query parameter key completion for BEAR.Resource request calls.
 *
 * <p>The pattern is intentionally broad (caret inside any PHP array); the provider narrows it to
 * BEAR.Resource call sites and returns early otherwise.
 */
public class QueryCompletionContributor extends CompletionContributor {

    public QueryCompletionContributor() {
        PsiElementPattern.Capture<PsiElement> insideArray =
            PlatformPatterns.psiElement().inside(PlatformPatterns.psiElement(ArrayCreationExpression.class));
        extend(CompletionType.BASIC, insideArray, new QueryCompletionProvider());
    }
}