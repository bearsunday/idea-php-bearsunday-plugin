package idea.bear.sunday.resource;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.util.ProcessingContext;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.WordUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;

public class ResourceCompletionProvider extends CompletionProvider<CompletionParameters> {

    private static final Logger LOG = Logger.getInstance(ResourceCompletionProvider.class);

    private ArrayList<String> filePathList;
    private int targetCnt = -1;
    private ArrayList<LookupElementBuilder> lookupElementBuilders;

    public void addCompletions(@NotNull CompletionParameters parameters,
                               @NotNull ProcessingContext context,
                               @NotNull CompletionResultSet resultSet) {

        PsiElement element = parameters.getOriginalPosition();

        if(element == null) {
            return;
        }

        Editor editor = parameters.getEditor();
        Project project = element.getProject();

        VirtualFile projectDir = ProjectUtil.guessProjectDir(project);
        if (projectDir == null) {
            return;
        }
        String projectBasePath = projectDir.getPath();

        // editor may be an EditorWindowImpl for injected fragments (e.g. Qiq tags),
        // which is not an EditorImpl; resolve the file without casting.
        VirtualFile editorFile = editor == null ? null
            : FileDocumentManager.getInstance().getFile(editor.getDocument());
        String editFile = editorFile == null ? "" : editorFile.getPath();

        String baseDir = projectBasePath + "/src/Resource/";

        filePathList = new ArrayList<>();

        String[] schemeList = {"App", "Page"};
        for (String scheme : schemeList) {
            try {
                Path dir = Paths.get(baseDir + scheme);
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
            } catch (IOException | IllegalArgumentException e) {
                // The resource directory may not exist in some projects; fall back to no
                // completions rather than breaking completion. InvalidPathException (an
                // IllegalArgumentException) is thrown by Paths.get when the project path
                // contains characters invalid for the default filesystem.
                LOG.warn("Unable to walk resource directory: " + baseDir + scheme, e);
            }
        }

        // when initialized
        if (targetCnt < 0) {
            targetCnt = filePathList.size();
        }

        if (targetCnt == filePathList.size()
            && lookupElementBuilders != null
            && !lookupElementBuilders.isEmpty()
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
                    LookupElementBuilder.create(uri)
                        .withTypeText(StringUtils.replace(file, projectBasePath + "/", ""), true);
                lookupElementBuilders.add(lookupElementBuilder);
            }
            uri = scheme + "://self" + uri;
            LookupElementBuilder lookupElementBuilder =
                LookupElementBuilder.create(uri)
                    .withTypeText(
                        StringUtils.replace(file, projectBasePath + "/", ""), true);
            lookupElementBuilders.add(lookupElementBuilder);
        }
        resultSet.addAllElements(lookupElementBuilders);
    }

}
