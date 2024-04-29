package idea.bear.sunday.index;

import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.jetbrains.annotations.NotNull;

public record Resource(@NotNull String uri, @NotNull String fqn) {
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        Resource resource = (Resource) o;
        return this.uri.equals(resource.uri())
                && this.fqn.equals(resource.fqn());
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder()
                .append(this.uri())
                .append(this.fqn())
                .toHashCode();
    }
}

