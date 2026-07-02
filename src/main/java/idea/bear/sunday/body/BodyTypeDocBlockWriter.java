package idea.bear.sunday.body;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.jetbrains.php.lang.documentation.phpdoc.psi.PhpDocComment;
import com.jetbrains.php.lang.psi.elements.PhpClass;

final class BodyTypeDocBlockWriter {

    private BodyTypeDocBlockWriter() {
    }

    static boolean update(Project project, PhpClass phpClass, BodyTypeCollection bodyTypes) {
        PsiFile containingFile = phpClass.getContainingFile();
        if (containingFile == null) {
            return false;
        }

        PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);
        Document document = documentManager.getDocument(containingFile);
        if (document == null) {
            return false;
        }

        documentManager.commitDocument(document);
        update(document, phpClass, bodyTypes);
        documentManager.commitDocument(document);

        return true;
    }

    static void update(Document document, PhpClass phpClass, BodyTypeCollection bodyTypes) {
        PhpDocComment docComment = phpClass.getDocComment();

        if (docComment == null) {
            String newDocBlock = BodyDocBlockUpdater.create(bodyTypes);
            document.insertString(phpClass.getTextRange().getStartOffset(), newDocBlock + "\n");
            return;
        }

        String updatedDocBlock = BodyDocBlockUpdater.update(
            docComment.getText(),
            bodyTypes,
            BodyTypeName.legacyGetFromClass(phpClass)
        );
        document.replaceString(
            docComment.getTextRange().getStartOffset(),
            docComment.getTextRange().getEndOffset(),
            updatedDocBlock
        );
    }

}
