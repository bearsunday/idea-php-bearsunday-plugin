package idea.bear.sunday.index;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.indexing.*;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import com.jetbrains.php.lang.PhpFileType;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class ResourceIndex extends FileBasedIndexExtension<String, Resource> {

    public static final ID<String, Resource> RESOURCE_URI_INDEX = ID.create("idea.bear.sunday.resource.uri");
    private static final int INDEX_VERSION = 3;
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

    @Override
    public KeyDescriptor<String> getKeyDescriptor() {
        return new EnumeratorStringDescriptor();
    }

    @Override
    public DataExternalizer<Resource> getValueExternalizer() {
        return myExternalizer;
    }

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

    public static Collection<String> getNames(Project project)
    {
        return FileBasedIndex.getInstance().getAllKeys(RESOURCE_URI_INDEX, project);
    }

    private static class MyDataIndexer implements DataIndexer<String, Resource, FileContent> {
        @NotNull
        @Override
        public Map<String, Resource> map(FileContent inputData) {
            return ResourceIndexUtil.indexFile(inputData).getUriMap();
        }
    }

}

