package idea.bear.sunday.annotation;

import com.intellij.patterns.ElementPattern;
import com.intellij.patterns.PatternCondition;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.util.ProcessingContext;
import com.jetbrains.php.lang.PhpLanguage;
import com.jetbrains.php.lang.documentation.phpdoc.lexer.PhpDocTokenTypes;
import com.jetbrains.php.lang.documentation.phpdoc.parser.PhpDocElementTypes;
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;

public class AnnotationPatternHelper {

    public static ElementPattern<PsiElement> getDocAttribute() {
        return PlatformPatterns.psiElement(PhpDocTokenTypes.DOC_IDENTIFIER)
            .afterLeafSkipping(
                PlatformPatterns.or(
                    PlatformPatterns.psiElement(PsiWhiteSpace.class),
                    PlatformPatterns.psiElement(PhpDocTokenTypes.DOC_TEXT).with(new PatternCondition<PsiElement>("Whitespace fix") {
                        @Override
                        public boolean accepts(@NotNull PsiElement psiElement, ProcessingContext processingContext) {
                            // nested issue
                            return StringUtils.isBlank(psiElement.getText());
                        }
                    })
                ),
                PlatformPatterns.or(
                    PlatformPatterns.psiElement(PhpDocTokenTypes.DOC_COMMA),
                    PlatformPatterns.psiElement(PhpDocTokenTypes.DOC_LPAREN),
                    PlatformPatterns.psiElement(PhpDocTokenTypes.DOC_LEADING_ASTERISK)
                )

            )
            .inside(PlatformPatterns
                .psiElement(PhpDocElementTypes.phpDocTag)
            )
            .withLanguage(PhpLanguage.INSTANCE);
    }
    public static ElementPattern<PsiElement> getTextIdentifier() {
        return PlatformPatterns.psiElement(PhpDocTokenTypes.DOC_STRING)
            .withParent(PlatformPatterns.psiElement(StringLiteralExpression.class)
                .afterLeafSkipping(
                    PlatformPatterns.or(
                        PlatformPatterns.psiElement(PhpDocTokenTypes.DOC_TEXT).withText(PlatformPatterns.string().containsChars("=")),
                        PlatformPatterns.psiElement(PsiWhiteSpace.class)
                    ),
                    PlatformPatterns.psiElement(PhpDocTokenTypes.DOC_IDENTIFIER)
                )
                .withParent(PlatformPatterns
                    .psiElement(PhpDocElementTypes.phpDocAttributeList)
                    .withParent(PlatformPatterns
                        .psiElement(PhpDocElementTypes.phpDocTag)
                    )
                )
            );
    }
    public static ElementPattern<PsiElement> getDocBlockTagAfterBackslash() {
        return PlatformPatterns.psiElement(PhpDocTokenTypes.DOC_TAG_NAME);
    }
}
