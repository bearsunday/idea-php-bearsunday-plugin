package idea.bear.sunday.index;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public class ResourceIndexData
{
    private final Map<String, Resource> myUriMap = new HashMap<String, Resource>();

    public Map<String, Resource> getUriMap() {
        return myUriMap;
    }

    public void addUriMap(@Nullable String uriString, Resource uriObject) {
        if(uriString != null) {
            myUriMap.put(uriString, uriObject);
        }
    }
}
