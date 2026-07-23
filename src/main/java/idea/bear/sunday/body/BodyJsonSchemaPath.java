package idea.bear.sunday.body;

import com.intellij.openapi.project.Project;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class BodyJsonSchemaPath {

    private static final String RESOURCE_APP = "\\Resource\\App\\";
    private static final String RESOURCE_PAGE = "\\Resource\\Page\\";

    private BodyJsonSchemaPath() {
    }

    static @Nullable Path fromClass(Project project, PhpClass phpClass) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            return null;
        }

        List<String> segments = resourceSegments(phpClass);
        if (segments.isEmpty()) {
            return null;
        }

        Path path = Path.of(basePath, "var", "json_schema");
        for (int i = 0; i < segments.size(); i++) {
            String segment = kebabCase(segments.get(i));
            path = i == segments.size() - 1
                ? path.resolve(segment + ".json")
                : path.resolve(segment);
        }

        return path;
    }

    static String relativeDisplayPath(Project project, Path path) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            return path.toString();
        }

        return Path.of(basePath).relativize(path).toString();
    }

    private static List<String> resourceSegments(PhpClass phpClass) {
        String namespace = Objects.requireNonNullElse(phpClass.getNamespaceName(), "");
        List<String> segments = new ArrayList<>();
        String resourceNamespace = resourceSubNamespace(namespace);
        if (resourceNamespace != null && !resourceNamespace.isBlank()) {
            for (String segment : resourceNamespace.split("\\\\")) {
                if (!segment.isBlank()) {
                    segments.add(segment);
                }
            }
        }

        String className = phpClass.getName();
        if (className != null && !className.isBlank()) {
            segments.add(className);
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
