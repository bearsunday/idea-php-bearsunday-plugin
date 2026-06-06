package idea.bear.sunday.relation;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileBasedIndexExtension;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.indexing.ID;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import com.jetbrains.php.lang.PhpFileType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ResourceRelationIndex extends FileBasedIndexExtension<String, List<ResourceRelation>> {

    public static final ID<String, List<ResourceRelation>> KEY = ID.create("idea.bear.sunday.resource.relation.incoming");
    private static final int INDEX_VERSION = 2;

    private final DataIndexer<String, List<ResourceRelation>, FileContent> myIndexer = new MyDataIndexer();
    private final DataExternalizer<List<ResourceRelation>> myExternalizer = new ResourceRelationExternalizer();

    @NotNull
    @Override
    public ID<String, List<ResourceRelation>> getName() {
        return KEY;
    }

    @NotNull
    @Override
    public DataIndexer<String, List<ResourceRelation>, FileContent> getIndexer() {
        return myIndexer;
    }

    @NotNull
    @Override
    public KeyDescriptor<String> getKeyDescriptor() {
        return new EnumeratorStringDescriptor();
    }

    @NotNull
    @Override
    public DataExternalizer<List<ResourceRelation>> getValueExternalizer() {
        return myExternalizer;
    }

    @NotNull
    @Override
    public FileBasedIndex.InputFilter getInputFilter() {
        return file -> file.getFileType() == PhpFileType.INSTANCE;
    }

    @Override
    public boolean dependsOnFileContent() {
        return true;
    }

    @Override
    public int getVersion() {
        return INDEX_VERSION;
    }

    public static List<ResourceRelation> findIncoming(String targetResourcePath, Project project) {
        List<ResourceRelation> result = new ArrayList<>();
        FileBasedIndex.getInstance().processValues(
            KEY,
            targetResourcePath,
            null,
            (VirtualFile file, List<ResourceRelation> value) -> {
                result.addAll(value);
                return true;
            },
            GlobalSearchScope.allScope(project)
        );

        return result;
    }

    private static class MyDataIndexer implements DataIndexer<String, List<ResourceRelation>, FileContent> {
        @NotNull
        @Override
        public Map<String, List<ResourceRelation>> map(@NotNull FileContent inputData) {
            String content = inputData.getContentAsText().toString();
            if (!StringUtil.contains(content, "#[")
                || (!StringUtil.containsIgnoreCase(content, "Link")
                && !StringUtil.containsIgnoreCase(content, "Embed"))
            ) {
                return Collections.emptyMap();
            }

            if (!isValidForIndex(inputData, inputData.getPsiFile())) {
                return Collections.emptyMap();
            }

            return ResourceRelationIndexUtil.index(inputData.getPsiFile());
        }
    }

    private static boolean isValidForIndex(FileContent inputData, PsiFile psiFile) {
        String fileName = psiFile.getName();
        if (fileName.startsWith(".") || fileName.contains("Test")) {
            return false;
        }

        VirtualFile baseDir = ProjectUtil.guessProjectDir(psiFile.getProject());
        String relativePath = baseDir == null ? null : VfsUtil.getRelativePath(inputData.getFile(), baseDir, '/');
        return relativePath == null
            || (!relativePath.contains("/tests/") && !relativePath.contains("/vendor/"));
    }
}
