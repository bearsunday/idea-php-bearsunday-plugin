package idea.bear.sunday.aop;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
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

/**
 * Indexes Ray.Aop interceptor bindings declared in modules via {@code bindInterceptor()},
 * keyed by the annotated attribute/annotation FQN and valued by the bound interceptor FQNs.
 */
public class InterceptorBindingIndex extends FileBasedIndexExtension<String, List<String>> {

    public static final ID<String, List<String>> KEY = ID.create("idea.bear.sunday.aop.interceptor.binding");
    private static final int INDEX_VERSION = 1;

    private final DataIndexer<String, List<String>, FileContent> myIndexer = new MyDataIndexer();
    private final DataExternalizer<List<String>> myExternalizer = new InterceptorBindingExternalizer();

    @NotNull
    @Override
    public ID<String, List<String>> getName() {
        return KEY;
    }

    @NotNull
    @Override
    public DataIndexer<String, List<String>, FileContent> getIndexer() {
        return myIndexer;
    }

    @NotNull
    @Override
    public KeyDescriptor<String> getKeyDescriptor() {
        return new EnumeratorStringDescriptor();
    }

    @NotNull
    @Override
    public DataExternalizer<List<String>> getValueExternalizer() {
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

    /**
     * Returns the interceptor FQNs bound to the given annotation/attribute FQN across the project.
     */
    public static List<String> findInterceptors(String annotationFqn, Project project) {
        List<String> result = new ArrayList<>();
        FileBasedIndex.getInstance().processValues(
            KEY,
            annotationFqn,
            null,
            (VirtualFile file, List<String> value) -> {
                result.addAll(value);
                return true;
            },
            GlobalSearchScope.allScope(project)
        );
        return result;
    }

    private static class MyDataIndexer implements DataIndexer<String, List<String>, FileContent> {
        @NotNull
        @Override
        public Map<String, List<String>> map(@NotNull FileContent inputData) {
            // Cheap pre-filter: only parse files that actually declare interceptor bindings.
            if (!StringUtil.contains(inputData.getContentAsText(), "bindInterceptor")) {
                return Collections.emptyMap();
            }

            return InterceptorBindingIndexUtil.index(inputData.getPsiFile());
        }
    }
}
