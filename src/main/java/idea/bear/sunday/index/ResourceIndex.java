package idea.bear.sunday.index;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.indexing.*;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import com.jetbrains.php.lang.PhpFileType;
import idea.bear.sunday.util.UriUtil;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class ResourceIndex extends FileBasedIndexExtension<String, Resource> {

    public static final ID<String, Resource> RESOURCE_URI_INDEX = ID.create("idea.bear.sunday.resource.uri");
    private static final int INDEX_VERSION = 4;
    private final DataIndexer<String, Resource, FileContent> myIndexer = new MyDataIndexer();
    private final DataExternalizer<Resource> myExternalizer = new ResourceExternalizer();

    @NotNull
    @Override
    public ID<String, Resource> getName() {
        return RESOURCE_URI_INDEX;
    }

    @NotNull
    @Override
    public DataIndexer<String, Resource, FileContent> getIndexer() {
        return myIndexer;
    }

    @NotNull
    @Override
    public KeyDescriptor<String> getKeyDescriptor() {
        return new EnumeratorStringDescriptor();
    }

    @NotNull
    @Override
    public DataExternalizer<Resource> getValueExternalizer() {
        return myExternalizer;
    }

    @NotNull
    @Override
    public FileBasedIndex.InputFilter getInputFilter() {
        return new FileBasedIndex.InputFilter() {
            @Override
            public boolean acceptInput(@NotNull VirtualFile file) {
                return file.getFileType() == PhpFileType.INSTANCE;
            }
        };
    }

    @Override
    public boolean dependsOnFileContent() {
        return true;
    }

    @Override
    public int getVersion() {
        return INDEX_VERSION;
    }

    public static PsiElement[] getFileByUri(String uri, Project project, Editor editor)
    {
        VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
        if (baseDir == null) {
            return new PsiElement[0];
        }
        if (editor == null) {
            return new PsiElement[0];
        }

        VirtualFile editorFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        boolean pageContext = editorFile != null
            && editorFile.getPath().startsWith(baseDir.getPath() + "/src/Resource/Page");

        String relPath = UriUtil.toResourceRelativePath(uri, pageContext);
        if (relPath == null) {
            return new PsiElement[0];
        }

        VirtualFile targetFile = baseDir.findFileByRelativePath(relPath);
        if (targetFile == null) {
            return new PsiElement[0];
        }

        PsiFile psiFile = PsiManager.getInstance(project).findFile(targetFile);
        if (psiFile == null) {
            return new PsiElement[0];
        }

        return new PsiElement[]{psiFile};
    }

    private static class MyDataIndexer implements DataIndexer<String, Resource, FileContent> {
        @NotNull
        @Override
        public Map<String, Resource> map(@NotNull FileContent inputData) {
            if(!isValidForIndex(inputData, inputData.getPsiFile())) {
                return new HashMap<>();
            }

            return ResourceIndexUtil.indexFile(inputData).getUriMap();
        }
    }

    private static boolean isValidForIndex(FileContent inputData, PsiFile psiFile) {

        String fileName = psiFile.getName();
        if(fileName.startsWith(".") || fileName.contains("Test")) {
            return false;
        }

        VirtualFile baseDir = ProjectUtil.guessProjectDir(psiFile.getProject());
        String relativePath = baseDir == null ? null : VfsUtil.getRelativePath(inputData.getFile(), baseDir, '/');
        return relativePath == null
            || (!relativePath.contains("/tests/") && !relativePath.contains("/vendor/"));
    }

}

