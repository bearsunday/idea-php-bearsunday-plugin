package idea.bear.sunday.index;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.project.Project;
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
import gnu.trove.THashMap;
import idea.bear.sunday.BearSundayProjectComponent;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;

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

    public static PsiElement[] getFileByUri(String uri, Project project, Editor editor)
    {
        try {
            URI u = new URI(uri);
            String relPath = "src/Resource/";
            if (u.getScheme() == null) {
                String editFile = ((EditorImpl) editor).getVirtualFile().getPath();
                if (editFile.startsWith(project.getBasePath() + "/src/Resource/Page")){
                    relPath += "Page";
                } else {
                    relPath += "App";
                }
                relPath += StringUtils.remove(WordUtils.capitalizeFully(u.getPath(), new char[]{'/', '-'}), "-");
            } else {
                relPath += WordUtils.capitalize(u.getScheme())
                        + StringUtils.remove(WordUtils.capitalizeFully(u.getPath(), new char[]{'/', '-'}), "-");
            }
            if (relPath.endsWith("/")) {
                relPath += "index.php";
            } else {
                relPath += ".php";
            }
            VirtualFile targetFile = project.getBaseDir().findFileByRelativePath(relPath);

            if(targetFile == null){
                return new PsiElement[0];
            }

            PsiFile psiFile = PsiManager.getInstance(project).findFile(targetFile);
            List<PsiElement> psiElements = new ArrayList();
            psiElements.add(psiFile);

            return psiElements.toArray(new PsiElement[psiElements.size()]);
        } catch (URISyntaxException e) {
            return new PsiElement[0];
        }
    }

    private static class MyDataIndexer implements DataIndexer<String, Resource, FileContent> {
        @NotNull
        @Override
        public Map<String, Resource> map(FileContent inputData) {
            final Map<String, Resource> map = new THashMap<String, Resource>();
            PsiFile psiFile = inputData.getPsiFile();

            if(!BearSundayProjectComponent.isEnabled(psiFile.getProject())) {
                return map;
            }

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

