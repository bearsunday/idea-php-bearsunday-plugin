package idea.bear.sunday.resource;

import com.intellij.patterns.ElementPattern;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;

public class UriElementPatternHelper {

    public static ElementPattern<PsiElement> getUriDefinition() {
        return PlatformPatterns.psiElement();
    }

}
