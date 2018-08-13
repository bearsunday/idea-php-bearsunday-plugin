package idea.bear.sunday.resource;

import com.intellij.patterns.ElementPattern;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.patterns.StandardPatterns;
import com.intellij.psi.PsiElement;
import com.jetbrains.php.lang.parser.PhpElementTypes;
import com.jetbrains.php.lang.patterns.PhpPatterns;
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression;

public class UriElementPatternHelper {

    public static ElementPattern<PsiElement> getUriDefinition() {

        ElementPattern<PsiElement> pattern =  PlatformPatterns.or(
            PlatformPatterns.psiElement(PsiElement.class)
                .withParent(
                PlatformPatterns.psiElement(StringLiteralExpression.class).withParent(
                    PlatformPatterns.psiElement(PhpElementTypes.PARAMETER_LIST).withParent(
                        PlatformPatterns.psiElement(PhpElementTypes.METHOD_REFERENCE).referencing(
                            PhpPatterns.psiElement().withElementType(
                                PhpElementTypes.CLASS_METHOD
                            ).withName(
                                StandardPatterns.string().oneOf("uri")
                            )
                        )
                    )
                )
            ),
            PlatformPatterns.psiElement(PsiElement.class).withParent(
                PlatformPatterns.psiElement(StringLiteralExpression.class).withParent(
                    PlatformPatterns.psiElement(PhpElementTypes.PARAMETER_LIST).withParent(
                        PlatformPatterns.psiElement(PhpElementTypes.METHOD_REFERENCE).referencing(
                            PhpPatterns.psiElement().withElementType(
                                PhpElementTypes.CLASS_METHOD
                            ).withName(
                                StandardPatterns.string().oneOf("toInstance")
                            )
                        )
                    )
                )
            )
        );
        return pattern;
    }

}
