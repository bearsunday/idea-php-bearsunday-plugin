package idea.bear.sunday.index;

import gnu.trove.THashMap;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class ResourceIndexData
{
    private Map<String, Resource> myUriMap = new THashMap<String, Resource>();

    public Map<String, Resource> getUriMap() {
        return myUriMap;
    }

    public void addUriMap(@Nullable String uriString, Resource uriObject) {
        if(uriString != null) {
            myUriMap.put(uriString, uriObject);
        }
    }
}
