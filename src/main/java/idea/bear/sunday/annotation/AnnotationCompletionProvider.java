package idea.bear.sunday.annotation;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.util.ProcessingContext;
import com.jetbrains.php.PhpIndex;
import com.jetbrains.php.codeInsight.PhpCodeInsightUtil;
import com.jetbrains.php.lang.psi.PhpGroupUseElement;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.PhpNamespace;
import com.jetbrains.php.lang.psi.elements.PhpPsiElement;
import com.jetbrains.php.refactoring.PhpAliasImporter;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashSet;

public class AnnotationCompletionProvider extends CompletionProvider<CompletionParameters> {

    @Override
    protected void addCompletions(@NotNull CompletionParameters completionParameters,
                                  @NotNull ProcessingContext processingContext,
                                  @NotNull CompletionResultSet completionResultSet) {

        final PsiElement psiElement = completionParameters.getOriginalPosition();
        if(psiElement == null) {
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
            LookupElementBuilder lookupElement = LookupElementBuilder
                .create(phpClass.getName() + "()")
                .withPresentableText(phpClass.getName())
                .withTypeText(phpClass.getPresentableFQN(), true)
                .withPsiElement(phpClass.getContext())
                .withInsertHandler(new InsertHandler<LookupElement>() {
                    @Override
                    public void handleInsert(@NotNull InsertionContext insertionContext, @NotNull LookupElement lookupElement) {
                        // caret move into quotation after insert completion
                        insertionContext.getEditor().getCaretModel().moveCaretRelatively(-1, 0, false, false, true);
                        PsiElement psiElement = PsiUtilCore.getElementAtOffset(
                            insertionContext.getFile(), insertionContext.getStartOffset());
                        PhpPsiElement scope = PhpCodeInsightUtil.findScopeForUseOperator(psiElement);
                        String insertPhpUse = "\\" + PsiTreeUtil.getChildrenOfTypeAsList(lookupElement.getPsiElement(), PhpClass.class).get(0).getPresentableFQN();
                        if (scope == null) {
                            return;
                        }
                        if (PhpCodeInsightUtil.findImportedName(scope, insertPhpUse, PhpGroupUseElement.PhpUseKeyword.CLASS) == null) {
                            PhpAliasImporter.insertUseStatement(insertPhpUse, scope);
                        }
                    }
                });
            completionResultSet.addElement(lookupElement);
        }
    }
}
