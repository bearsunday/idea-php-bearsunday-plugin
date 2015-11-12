package idea.bear.sunday.resource;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import org.jetbrains.annotations.NotNull;

public class ResourceLookupElement extends LookupElement {

    private String uri;

    public ResourceLookupElement(String uri) {
        this.uri = uri;
    }

    @NotNull
    @Override
    public String getLookupString() {
        return uri;
    }

    public void renderElement(LookupElementPresentation presentation) {
        presentation.setItemText(getLookupString());
        presentation.setTypeGrayed(true);
    }

}
