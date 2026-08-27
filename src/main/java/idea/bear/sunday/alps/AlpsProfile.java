package idea.bear.sunday.alps;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A normalized ALPS profile. JSON and XML profiles share this model, so consumers never
 * branch on the source format; {@code xml} only records where the profile came from.
 */
public record AlpsProfile(
    @Nullable String title,
    @Nullable String doc,
    List<AlpsLink> links,
    List<AlpsDescriptor> descriptors,
    String sourcePath,
    boolean xml
) {
}
