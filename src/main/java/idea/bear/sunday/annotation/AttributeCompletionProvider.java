package idea.bear.sunday.annotation;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ProcessingContext;
import com.jetbrains.php.PhpIndex;
import com.jetbrains.php.lang.documentation.phpdoc.parser.PhpDocElementTypes;
import com.jetbrains.php.lang.psi.elements.*;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;

public class AttributeCompletionProvider extends CompletionProvider<CompletionParameters> {

    @Override
    protected void addCompletions(@NotNull CompletionParameters completionParameters,
                                  @NotNull ProcessingContext processingContext,
                                  @NotNull CompletionResultSet completionResultSet) {

        // if Php Annotation Plugin was installed, then nothing to do.
        if (PluginManager.isPluginInstalled(PluginId.getId("de.espend.idea.php.annotation"))
            && Objects.requireNonNull(PluginManagerCore.getPlugin(PluginId.getId("de.espend.idea.php.annotation"))).isEnabled()
        ){
            return;
        }

        PsiElement psiElement = completionParameters.getOriginalPosition();
        if(psiElement == null) {
            return;
        }

        String annotation;
        if (((LeafPsiElement) psiElement).getElementType().equals(PhpDocElementTypes.WHITE_SPACE)){
            annotation = psiElement.getPrevSibling().getFirstChild().getText();
        } else if (((LeafPsiElement) psiElement).getElementType().equals(PhpDocElementTypes.DOC_RPAREN)){
            annotation = psiElement.getParent().getParent().getFirstChild().getText();
        } else {
            return;
        }

        final Project project = psiElement.getProject();
        final PhpIndex phpIndex = PhpIndex.getInstance(project);

        Collection<PhpClass> phpClasses = new HashSet<>();
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
                    LookupElementBuilder lookupElement = LookupElementBuilder
                        .create(field.getName() + "=\"\"")
                        .withPresentableText(field.getName())
                        .withInsertHandler(new InsertHandler<LookupElement>() {
                            @Override
                            public void handleInsert(@NotNull InsertionContext insertionContext, @NotNull LookupElement lookupElement) {
                                // caret move into quotation after insert completion
                                insertionContext.getEditor().getCaretModel().moveCaretRelatively(-1, 0, false, false, true);
                            }
                        });
                    completionResultSet.addElement(lookupElement);
                }
                break;
            }
        }
    }
}
