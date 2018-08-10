package idea.bear.sunday.resource;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.psi.PsiElement;
import com.intellij.util.ProcessingContext;
import idea.bear.sunday.BearSundayProjectComponent;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;

public class ResourceCompletionProvider extends CompletionProvider<CompletionParameters> {

    ArrayList<String> list = new ArrayList();

    public void addCompletions(@NotNull CompletionParameters parameters,
                               ProcessingContext context,
                               @NotNull CompletionResultSet resultSet) {

        if(!BearSundayProjectComponent.isEnabled(parameters.getPosition())) {
            return;
        }

        PsiElement element = parameters.getOriginalPosition();

        if(element == null) {
            return;
        }

        String[] schemeList = {"App", "Page"};
        for (String scheme : schemeList) {
            Path dir = Paths.get(element.getProject().getBaseDir().getPath() + "/src/Resource/" + scheme);

            try {
                Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs){
                        if (!file.getFileName().toString().contains(".php")) {
                            return FileVisitResult.CONTINUE;
                        }
                        list.add(file.toString());
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        Collections.sort(list);

        for (String file : list) {
            String baseDir = element.getProject().getBaseDir().getPath() + "/src/Resource/";
            String uri = file.replace(baseDir, "").replace(".php", "");
            String scheme = "app";
            if (uri.startsWith("Page")) {
                scheme = "page";
            }

            uri = scheme + "://self"
                + ("-"
                    + StringUtils.join(
                        StringUtils.splitByCharacterTypeCamelCase(
                            uri.replaceFirst(WordUtils.capitalize(scheme), "")
                        ),
                        "-"
                    )
                ).replace("-/-", "/").toLowerCase().replace(scheme, "");
            resultSet.addElement(LookupElementBuilder.create(uri));
        }
    }

}
