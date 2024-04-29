package idea.bear.sunday.index;

import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.indexing.FileContent;
import com.jetbrains.php.lang.psi.elements.PhpClass;

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

        String nameSpace = phpClass.getNamespaceName();
        if(!nameSpace.contains("Resource")) {
            return result;
        }

        int index = nameSpace.indexOf("Resource\\");
        String scheme = nameSpace.substring(index + 9).replace("\\", "/").toLowerCase();

        if(scheme.startsWith("app")) {
            scheme = scheme.replace("app", "app://self");
        }
        if(scheme.startsWith("page")) {
            scheme = scheme.replace("page", "page://self");
        }

        String className = phpClass.getName().toLowerCase();
        String uri = scheme + className;

        result.addUriMap(uri, new Resource(uri, phpClass.getFQN()));

        return result;
    }

}
