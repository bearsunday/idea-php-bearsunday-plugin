package idea.bear.sunday.resource;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiElement;
import com.intellij.util.ProcessingContext;
import idea.bear.sunday.BearSundayProjectComponent;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;

public class ResourceCompletionProvider extends CompletionProvider<CompletionParameters> {

    private ArrayList<String> filePathList;
    private int targetCnt = -1;
    private ArrayList<LookupElementBuilder> lookupElementBuilders;

    public void addCompletions(@NotNull CompletionParameters parameters,
                               ProcessingContext context,
                               @NotNull CompletionResultSet resultSet) {

        if (!BearSundayProjectComponent.isEnabled(parameters.getPosition())) {
            return;
        }

        PsiElement element = parameters.getOriginalPosition();

        if(element == null) {
            return;
        }

        Editor editor = parameters.getEditor();
        Project project = element.getProject();
        String editFile = ((EditorImpl) editor).getVirtualFile().getPath();
        String baseDir = project.getBasePath() + "/src/Resource/";
        final Icon icon = IconLoader.getIcon("/idea/bear/sunday/icons/bearsunday.png");

        filePathList = new ArrayList<>();

        String[] schemeList = {"App", "Page"};
        for (String scheme : schemeList) {
            Path dir = Paths.get(baseDir + scheme);

            try {
                Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs){
                        if (!file.getFileName().toString().contains(".php")) {
                            return FileVisitResult.CONTINUE;
                        }
                        filePathList.add(file.toString());
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // when initialized
        if (targetCnt < 0) {
            targetCnt = filePathList.size();
        }

        if (targetCnt == filePathList.size()
            && lookupElementBuilders != null
            && lookupElementBuilders.size() > 0
        ){
            resultSet.addAllElements(lookupElementBuilders);
            return;
        }

        targetCnt = filePathList.size();
        lookupElementBuilders = new ArrayList<>();

        for (String file : filePathList) {
            String uri = file.replace(baseDir, "").replace(".php", "");
            String scheme = "app";
            if (uri.startsWith("Page")) {
                scheme = "page";
            }

            uri = ("-"
                    + StringUtils.join(
                        StringUtils.splitByCharacterTypeCamelCase(
                            uri.replaceFirst(WordUtils.capitalize(scheme), "")
                        ),
                        "-"
                    )
                ).replace("-/-", "/").toLowerCase().replace(scheme, "");

            if (editFile.startsWith(baseDir + "App") && scheme.equals("app")
                || editFile.startsWith(baseDir + "Page") && scheme.equals("page")){
                LookupElementBuilder lookupElementBuilder =
                    LookupElementBuilder.create(uri).withIcon(icon).withTypeText(
                        StringUtils.replace(file, project.getBasePath() + "/", ""), true);
                lookupElementBuilders.add(lookupElementBuilder);
            }
            uri = scheme + "://self" + uri;
            LookupElementBuilder lookupElementBuilder =
                LookupElementBuilder.create(uri).withIcon(icon).withTypeText(
                    StringUtils.replace(file, project.getBasePath() + "/", ""), true);
            lookupElementBuilders.add(lookupElementBuilder);
        }
        resultSet.addAllElements(lookupElementBuilders);
    }

}
