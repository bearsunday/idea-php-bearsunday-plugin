package idea.bear.sunday.relation;

import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.psi.elements.ClassReference;
import com.jetbrains.php.lang.psi.elements.PhpAttribute;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression;
import idea.bear.sunday.util.UriUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ResourceRelationIndexUtil {

    private static final String LINK = "Link";
    private static final String EMBED = "Embed";

    private ResourceRelationIndexUtil() {
    }

    public static Map<String, List<ResourceRelation>> index(PsiFile psiFile) {
        return index(psiFile, ProjectUtil.guessProjectDir(psiFile.getProject()));
    }

    static Map<String, List<ResourceRelation>> index(PsiFile psiFile, @Nullable VirtualFile baseDir) {
        Map<String, List<ResourceRelation>> result = new HashMap<>();
        PhpClass sourceClass = PsiTreeUtil.findChildOfType(psiFile, PhpClass.class);
        String sourceUri = UriUtil.toResourceUri(sourceClass);
        if (sourceClass == null || sourceUri == null) {
            return result;
        }

        VirtualFile sourceFile = psiFile.getVirtualFile();
        if (baseDir == null || sourceFile == null) {
            return result;
        }

        String sourceFilePath = VfsUtil.getRelativePath(sourceFile, baseDir, '/');
        if (sourceFilePath == null) {
            return result;
        }

        boolean pageContext = sourceFilePath.startsWith("src/Resource/Page/");
        for (PhpAttribute attribute : PsiTreeUtil.findChildrenOfType(psiFile, PhpAttribute.class)) {
            RelationSpec spec = relationSpec(attribute);
            if (spec == null) {
                continue;
            }

            String rawTargetUri = stringArgument(attribute, spec.targetArgumentName(), spec.targetIndex());
            if (rawTargetUri == null) {
                continue;
            }

            String targetResourcePath = UriUtil.toSupportedResourceRelativePath(rawTargetUri, pageContext);
            String targetUri = UriUtil.normalizeSupportedResourceUri(rawTargetUri, pageContext);
            if (targetResourcePath == null || targetUri == null) {
                continue;
            }

            String rel = stringArgument(attribute, "rel", spec.relIndex());
            ResourceRelation relation = new ResourceRelation(
                spec.kind(),
                rel == null ? "" : rel,
                sourceUri,
                sourceClass.getFQN(),
                targetUri,
                targetMethod(attribute, spec),
                rawTargetUri,
                sourceFilePath,
                attribute.getTextOffset()
            );
            result.computeIfAbsent(targetResourcePath, key -> new ArrayList<>()).add(relation);
        }

        return result;
    }

    @Nullable
    private static RelationSpec relationSpec(PhpAttribute attribute) {
        String name = attributeShortName(attribute);
        if (LINK.equals(name)) {
            return new RelationSpec(LINK, "href", 1, 0);
        }
        if (EMBED.equals(name)) {
            return new RelationSpec(EMBED, "src", 0, 1);
        }

        return null;
    }

    @Nullable
    private static String attributeShortName(PhpAttribute attribute) {
        String fqn = attribute.getFQN();
        if (fqn != null && !fqn.isBlank()) {
            return shortName(fqn);
        }

        ClassReference classReference = attribute.getClassReference();
        return classReference == null ? null : classReference.getName();
    }

    private static String shortName(String fqn) {
        int index = fqn.lastIndexOf('\\');
        return index >= 0 ? fqn.substring(index + 1) : fqn;
    }

    @Nullable
    private static String stringArgument(PhpAttribute attribute, String name, int index) {
        PsiElement parameter = attribute.getParameter(name, index);
        if (parameter == null) {
            return null;
        }

        if (parameter instanceof StringLiteralExpression stringLiteral) {
            return stringLiteral.getContents();
        }

        StringLiteralExpression stringLiteral = PsiTreeUtil.findChildOfType(parameter, StringLiteralExpression.class);
        return stringLiteral == null ? null : stringLiteral.getContents();
    }

    private static String targetMethod(PhpAttribute attribute, RelationSpec spec) {
        if (EMBED.equals(spec.kind())) {
            return "onGet";
        }

        String method = stringArgument(attribute, "method", 2);
        if (method == null || method.isBlank()) {
            method = "get";
        }

        method = method.toLowerCase(Locale.ROOT);
        return "on" + method.substring(0, 1).toUpperCase(Locale.ROOT) + method.substring(1);
    }

    private record RelationSpec(String kind, String targetArgumentName, int targetIndex, int relIndex) {
    }
}
