package idea.bear.sunday.index;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.containers.hash.HashSet;
import com.intellij.util.indexing.*;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import com.jetbrains.php.lang.PhpFileType;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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

    public static Collection<String> getNames(Project project) {
        return FileBasedIndex.getInstance().getAllKeys(RESOURCE_URI_INDEX, project);
    }

    public static PsiElement[] getFileByUri(String uri, Project project, GlobalSearchScope searchScope)
    {
        final Collection<VirtualFile> files = FileBasedIndex.getInstance().getContainingFiles(RESOURCE_URI_INDEX, uri, searchScope);
        Collection<PsiElement> psiElements = new HashSet<PsiElement>();

        for(VirtualFile vFile : files) {
            final PsiElement psiElement = PsiManager.getInstance(project).findFile(vFile);
            psiElements.add(psiElement);
        }

        return psiElements.toArray(new PsiElement[psiElements.size()]);
    }

    private static class MyDataIndexer implements DataIndexer<String, Resource, FileContent> {
        @NotNull
        @Override
        public Map<String, Resource> map(FileContent inputData) {
            final Map<String, Resource> map = new THashMap<String, Resource>();

            PsiFile psiFile = inputData.getPsiFile();

            if(!isValidForIndex(inputData, psiFile)) {
                return map;
            }

            return ResourceIndexUtil.indexFile(inputData).getUriMap();
        }
    }

    public static boolean isValidForIndex(FileContent inputData, PsiFile psiFile) {

        String fileName = psiFile.getName();
        if(fileName.startsWith(".") || fileName.contains("Test")) {
            return false;
        }

        String relativePath = VfsUtil.getRelativePath(inputData.getFile(), psiFile.getProject().getBaseDir(), '/');
        if(relativePath != null && (relativePath.contains("/tests/") )) {
            return false;
        }

        return true;
    }

}

