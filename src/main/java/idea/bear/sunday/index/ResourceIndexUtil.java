package idea.bear.sunday.index;

import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.indexing.FileContent;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import idea.bear.sunday.util.UriUtil;

public class ResourceIndexUtil {

    private static final Key<ResourceIndexData> ourDartCachesData = Key.create("idea.bear.sunday.caches.index.data");

    public static ResourceIndexData indexFile(FileContent content) {
        ResourceIndexData indexData = content.getUserData(ourDartCachesData);
        if(indexData != null) {
            return indexData;
        }
        synchronized(content) {
            indexData = content.getUserData(ourDartCachesData);
            if(indexData != null) {
                return indexData;
            }
            indexData = indexResources(content.getPsiFile());
        }
        return indexData;
    }

    private static ResourceIndexData indexResources(PsiFile psiFile) {
        ResourceIndexData result = new ResourceIndexData();

        PhpClass phpClass = PsiTreeUtil.findChildOfType(psiFile, PhpClass.class);

        if(phpClass == null) {
            return result;
        }

        String uri = UriUtil.toResourceUri(phpClass);
        if(uri == null) {
            return result;
        }

        result.addUriMap(uri, new Resource(uri, phpClass.getFQN()));

        return result;
    }

}
