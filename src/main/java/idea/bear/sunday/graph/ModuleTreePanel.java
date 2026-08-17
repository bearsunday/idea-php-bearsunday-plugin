package idea.bear.sunday.graph;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.ui.JBUI;
import idea.bear.sunday.mcp.facts.DiModuleTreeService;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/**
 * Draws the module tree of a BEAR.Sunday context. The tree, and the picture of it, are the ones
 * {@code DiModuleTreeService} answers with -- this panel adds no facts of its own, so what is drawn
 * here and what an AI is told through {@code bear_di_module_tree_read} cannot disagree.
 */
final class ModuleTreePanel extends JPanel implements Disposable {

    private static final String DEFAULT_CONTEXT = "prod-app";

    private final Project project;
    private final JBTextField contextField = new JBTextField(DEFAULT_CONTEXT, 24);
    private final JPanel viewer = new JPanel(new BorderLayout());

    @Nullable
    private final JBCefBrowser browser;

    @Nullable
    private final JComponent browserComponent;

    ModuleTreePanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        // JCEF is absent from some JDK/IDE builds, and a panel that assumed it would throw rather
        // than draw. Without it the Mermaid source is still worth showing.
        this.browser = JBCefApp.isSupported() ? new JBCefBrowser() : null;
        this.browserComponent = browser == null ? null : browser.getComponent();

        add(controls(), BorderLayout.NORTH);
        add(viewer, BorderLayout.CENTER);
        draw();
    }

    private JComponent controls() {
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4)));
        controls.add(new JBLabel("Context:"));
        controls.add(contextField);
        JButton draw = new JButton("Draw");
        draw.addActionListener(event -> draw());
        controls.add(draw);
        contextField.addActionListener(event -> draw());

        return controls;
    }

    private void draw() {
        String context = contextField.getText();
        DumbService dumb = DumbService.getInstance(project);
        // Module classes are resolved through the index, so asking while it builds can only answer
        // index_not_ready. Saying so and then drawing by itself beats leaving that as the last
        // word -- a panel opened with the IDE would otherwise sit on it until someone pressed Draw.
        if (dumb.isDumb()) {
            showText("Waiting for the project index to finish building…");
        }
        dumb.runWhenSmart(() -> read(context));
    }

    private void read(String context) {
        // The walk can run long on a big graph, so it is not run on the UI thread;
        // DiModuleTreeService takes its own read action.
        new Task.Backgroundable(project, "Reading the BEAR.Sunday module tree", true) {
            @Override
            public void run(ProgressIndicator indicator) {
                String answer = DiModuleTreeService.getInstance(project).read(context, true);
                ApplicationManager.getApplication().invokeLater(() -> show(answer));
            }
        }.queue();
    }

    private void show(String answer) {
        JsonObject envelope = JsonParser.parseString(answer).getAsJsonObject();
        String diagram = string(envelope, "diagram");
        if (diagram == null) {
            // An envelope with no diagram is an answer -- not_found for a context nothing resolves,
            // index_not_ready while the index builds -- and saying which is the point.
            showText(status(envelope));

            return;
        }
        if (browser == null || browserComponent == null) {
            showText(diagram);

            return;
        }
        // Put the browser back: an earlier answer with no diagram -- index_not_ready while the
        // index builds, not_found for an empty context -- swapped it out for the message, and
        // leaving it out would make every later answer draw into a panel nothing can see.
        show(browserComponent);
        browser.loadHTML(ModuleTreeDiagramPage.html(diagram, !JBColor.isBright()));
    }

    private void showText(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        show(new JScrollPane(area));
    }

    private void show(JComponent component) {
        if (viewer.getComponentCount() == 1 && viewer.getComponent(0) == component) {
            return;
        }
        viewer.removeAll();
        viewer.add(component, BorderLayout.CENTER);
        viewer.revalidate();
        viewer.repaint();
    }

    private static String status(JsonObject envelope) {
        String status = string(envelope, "status");
        String error = string(envelope, "error");

        return (status == null ? "no answer" : status) + (error == null ? "" : ": " + error);
    }

    /** The JCEF browser holds a native process; the tool window disposes this panel with it. */
    @Override
    public void dispose() {
        if (browser != null) {
            Disposer.dispose(browser);
        }
    }

    @Nullable
    private static String string(JsonObject json, String key) {
        JsonElement element = json.get(key);

        return element == null || element.isJsonNull() ? null : element.getAsString();
    }
}
