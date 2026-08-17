package idea.bear.sunday.graph;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/** Registers the module tree panel as a tool window. */
final class ModuleTreeToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ModuleTreePanel panel = new ModuleTreePanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, null, false);
        // Ties the panel's JCEF browser to the tool window's life, so closing the window releases it.
        Disposer.register(content, panel);
        toolWindow.getContentManager().addContent(content);
    }
}
