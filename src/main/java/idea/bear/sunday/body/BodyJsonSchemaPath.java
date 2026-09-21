package idea.bear.sunday.body;

import com.intellij.openapi.project.Project;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class BodyJsonSchemaPath {

    private static final String RESOURCE_APP = "\\Resource\\App\\";
    private static final String RESOURCE_PAGE = "\\Resource\\Page\\";

    private BodyJsonSchemaPath() {
    }

    public static @Nullable Path fromClass(Project project, PhpClass phpClass) {
        String basePath = project.getBasePath();
        List<String> segments = kebabSegments(phpClass);
        if (basePath == null || segments.isEmpty()) {
            return null;
        }

        Path path = Path.of(basePath, "var", "json_schema");
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            path = path.resolve(i == segments.size() - 1 ? segment + ".json" : segment);
        }

        return path;
    }

    /**
     * The file the convention names for a resource class, relative to the schema directory:
     * {@code ...\Resource\App\Admin\User} is {@code admin/user.json}, and a class whose namespace
     * names no {@code Resource\App} or {@code Resource\Page} is the class name alone. Both readers
     * reach the class through its path under {@code src/Resource}, so that last form is what a
     * namespace which does not match the directory resolves to, not a refusal. {@code null} only
     * when the class has no name to give.
     *
     * <p>Separate from {@link #fromClass} because the readers resolve the name under the
     * {@code jsonSchemaPath} directories, while {@code fromClass} answers where this generator
     * writes.
     */
    public static @Nullable String conventionalFileName(PhpClass phpClass) {
        List<String> segments = kebabSegments(phpClass);

        return segments.isEmpty() ? null : String.join("/", segments) + ".json";
    }

    static String relativeDisplayPath(Project project, Path path) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            return path.toString();
        }

        return Path.of(basePath).relativize(path).toString();
    }

    private static List<String> kebabSegments(PhpClass phpClass) {
        String namespace = Objects.requireNonNullElse(phpClass.getNamespaceName(), "");
        List<String> segments = new ArrayList<>();
        String resourceNamespace = resourceSubNamespace(namespace);
        if (resourceNamespace != null && !resourceNamespace.isBlank()) {
            for (String segment : resourceNamespace.split("\\\\")) {
                if (!segment.isBlank()) {
                    segments.add(kebabCase(segment));
                }
            }
        }

        String className = phpClass.getName();
        if (className != null && !className.isBlank()) {
            segments.add(kebabCase(className));
        }

        return segments;
    }

    private static @Nullable String resourceSubNamespace(String namespace) {
        int appIndex = namespace.indexOf(RESOURCE_APP);
        if (appIndex >= 0) {
            return namespace.substring(appIndex + RESOURCE_APP.length());
        }

        int pageIndex = namespace.indexOf(RESOURCE_PAGE);
        if (pageIndex >= 0) {
            return namespace.substring(pageIndex + RESOURCE_PAGE.length());
        }

        return null;
    }

    private static String kebabCase(String value) {
        StringBuilder result = new StringBuilder();
        char previous = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '_' || current == '-' || current == ' ') {
                appendDash(result);
                previous = current;
                continue;
            }
            if (Character.isUpperCase(current)
                && result.length() > 0
                && previous != 0
                && previous != '_'
                && previous != '-'
                && previous != ' '
                && !Character.isUpperCase(previous)) {
                appendDash(result);
            }
            result.append(Character.toLowerCase(current));
            previous = current;
        }

        return result.toString().toLowerCase(Locale.ROOT);
    }

    private static void appendDash(StringBuilder result) {
        if (!result.isEmpty() && result.charAt(result.length() - 1) != '-') {
            result.append('-');
        }
    }

}
