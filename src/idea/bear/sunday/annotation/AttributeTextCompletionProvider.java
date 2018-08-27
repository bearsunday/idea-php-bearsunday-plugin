package idea.bear.sunday.annotation;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ProcessingContext;
import com.jetbrains.php.PhpIndex;
import com.jetbrains.php.lang.documentation.phpdoc.psi.PhpDocComment;
import com.jetbrains.php.lang.documentation.phpdoc.psi.tags.PhpDocTag;
import com.jetbrains.php.lang.psi.elements.*;
import com.jetbrains.php.lang.psi.elements.impl.StringLiteralExpressionImpl;
import gnu.trove.THashSet;
import idea.bear.sunday.resource.ResourceCompletionProvider;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.Collection;

public class AttributeTextCompletionProvider extends CompletionProvider<CompletionParameters> {

    @Override
    protected void addCompletions(@NotNull CompletionParameters completionParameters,
                                  ProcessingContext processingContext,
                                  @NotNull CompletionResultSet completionResultSet) {

        PsiElement psiElement = completionParameters.getOriginalPosition();
        if(psiElement == null) {
            return;
        }

        String attribute = psiElement.getParent().getPrevSibling().getPrevSibling().getText();
        if (attribute.equals("href")
            || attribute.equals("src")
            || attribute.equals("uri")
        ) {
            ResourceCompletionProvider resourceCompletionProvider = new ResourceCompletionProvider();
            resourceCompletionProvider.addCompletions(
                completionParameters, processingContext, completionResultSet);
            return;
        }

        final String annotation = psiElement.getParent().getParent().getParent().getFirstChild().getText();
        final Project project = psiElement.getProject();
        final PhpIndex phpIndex = PhpIndex.getInstance(project);
        final Icon icon = IconLoader.getIcon("/idea/bear/sunday/icons/bearsunday.png");

        Collection<PhpClass> phpClasses = new THashSet<>();
        Collection<PhpNamespace> namespaces = phpIndex.getNamespacesByName("\\bear\\resource\\annotation");
        namespaces.addAll(phpIndex.getNamespacesByName("\\bear\\repositorymodule\\annotation"));
        for (PhpNamespace namespace: namespaces) {
            phpClasses.addAll(PsiTreeUtil.getChildrenOfTypeAsList(namespace.getStatements(), PhpClass.class));
        }

        for (PhpClass phpClass: phpClasses) {
            if (phpClass.isAbstract()) {
                continue;
            }
            if (("@" + phpClass.getName()).equals(annotation)) {
                Field[] fields = phpClass.getOwnFields();

                ExtendsList extendsList = phpClass.getExtendsList();
                for (ClassReference classReference: extendsList.getReferenceElements()) {
                    String fqn = classReference.getFQN();
                    Collection<PhpClass> extendsClasses = phpIndex.getClassesByFQN(fqn);
                    for (PhpClass extendsClass: extendsClasses) {
                        fields = ArrayUtil.mergeArrays(fields, extendsClass.getOwnFields());
                    }
                }

                for (Field field: fields) {
                    if (field.getName().equals(attribute)) {

                        PhpDocComment phpDocComment = field.getDocComment();
                        if (phpDocComment == null) {
                            break;
                        }
                        PhpDocTag[] phpDocTags = phpDocComment.getTagElementsByName("@Enum");
                        if (phpDocTags.length == 0
                            || phpDocTags[0].getChildren().length == 0
                        ) {
                            break;
                        }
                        PsiElement[] options = phpDocTags[0].getChildren()[0].getChildren();

                        for (PsiElement option: options) {
                            completionResultSet.addElement(
                                LookupElementBuilder
                                .create(((StringLiteralExpressionImpl) option).getContents())
                                .withTypeText("string", true)
                                .withIcon(icon)
                            );
                        }
                        break;
                    }
                }
                break;
            }
        }
    }
}
