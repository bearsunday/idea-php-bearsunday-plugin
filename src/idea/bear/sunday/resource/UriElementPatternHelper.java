package idea.bear.sunday.resource;

import com.intellij.patterns.ElementPattern;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.patterns.StandardPatterns;
import com.intellij.psi.PsiElement;
import com.jetbrains.php.lang.PhpLanguage;

public class UriElementPatternHelper {

    public static ElementPattern<PsiElement> getUriDefinition() {

        return PlatformPatterns.or(
                PlatformPatterns
                        .psiElement()
                        .withText(
                                StandardPatterns.string().startsWith("app://")
                        )
                        .withLanguage(PhpLanguage.INSTANCE)
                ,
                PlatformPatterns
                        .psiElement()
                        .withText(
                                StandardPatterns.string().startsWith("page://")
                        )
                        .withLanguage(PhpLanguage.INSTANCE)
        );
    }

}
