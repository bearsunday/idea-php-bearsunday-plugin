package idea.bear.sunday.index;

import org.jetbrains.annotations.NotNull;

public class Resource {

    @NotNull
    private final String uri;

    @NotNull
    private final String fqn;

    public Resource(@NotNull String uri, @NotNull String fqn) {
        this.uri = uri;
        this.fqn = fqn;
    }

    @NotNull
    public String getUri() {
        return uri;
    }

    @NotNull
    public String getFqn() {
        return fqn;
    }

}

