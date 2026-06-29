package idea.bear.sunday.util;

import com.intellij.psi.PsiElement;
import com.jetbrains.php.lang.documentation.phpdoc.psi.impl.tags.PhpDocTagImpl;
import com.jetbrains.php.lang.psi.elements.impl.PhpAttributeImpl;
import com.jetbrains.php.lang.psi.elements.impl.StringLiteralExpressionImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves the BEAR.Sunday annotation/attribute context around a PHP string literal element.
 *
 * <p>BEAR.Sunday annotates resource methods either with PHPDoc tags ({@code @Query}),
 * PHP 8 attributes ({@code #[Query]}), or both. The IDE caret lands on a leaf inside the
 * string literal argument; this helper walks up to the enclosing doc tag / attribute and
 * reports the argument text together with the annotation/attribute name so that goto
 * handlers can dispatch on it.
 */
public final class PhpDocAttributeTargets {

    private PhpDocAttributeTargets() {
    }

    public record Target(@NotNull String resourceName, @NotNull String name, @NotNull PsiElement literal) {
    }

    @Nullable
    public static Target resolve(@Nullable PsiElement psiElement) {
        if (psiElement == null) {
            return null;
        }

        PsiElement context = psiElement.getContext();
        if (!(context instanceof StringLiteralExpressionImpl stringLiteralExpression)) {
            return null;
        }

        String contents = stringLiteralExpression.getContents();
        String resourceName = UriUtil.getUriValue(contents);
        if (resourceName == null || resourceName.isBlank()) {
            return null;
        }

        PsiElement childContext = context.getParent().getParent().getFirstChild().getContext();
        String name;
        if (childContext instanceof PhpDocTagImpl phpDocTagImpl) {
            name = phpDocTagImpl.getName();
        } else if (childContext instanceof PhpAttributeImpl phpAttributeImpl) {
            name = phpAttributeImpl.getName();
        } else {
            return null;
        }
        if (name == null) {
            return null;
        }

        return new Target(resourceName, name, context);
    }
}