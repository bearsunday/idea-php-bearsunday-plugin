package idea.bear.sunday.graph;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.util.PsiNavigateUtil;
import com.intellij.util.ui.JBUI;
import com.jetbrains.php.PhpIndex;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import idea.bear.sunday.mcp.facts.AppContextListService;
import idea.bear.sunday.mcp.facts.DiModuleTreeService;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Iterator;
import java.util.List;

/**
 * Draws the module tree of a BEAR.Sunday context. The tree, and the picture of it, are the ones
 * {@code DiModuleTreeService} answers with -- this panel adds no facts of its own, so what is drawn
 * here and what an AI is told through {@code bear_di_module_tree_read} cannot disagree.
 */
final class ModuleTreePanel extends JPanel implements Disposable {

    private static final String DEFAULT_CONTEXT = "prod-app";

    private final Project project;
    /**
     * Editable, and that is the point: the collector reads the two shapes an app names a context
     * in, and an app that names one some third way must still be answerable about.
     */
    private final ComboBox<String> contextField = new ComboBox<>();
    private final JPanel viewer = new JPanel(new BorderLayout());

    @Nullable
    private final JBCefBrowser browser;

    @Nullable
    private final JComponent browserComponent;

    /** The page's way back into the IDE, and the only one: a click in JCEF is not a Swing event. */
    @Nullable
    private final JBCefJSQuery openQuery;

    ModuleTreePanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        // JCEF is absent from some JDK/IDE builds, and a panel that assumed it would throw rather
        // than draw. Without it the Mermaid source is still worth showing.
        this.browser = JBCefApp.isSupported() ? new JBCefBrowser() : null;
        this.browserComponent = browser == null ? null : browser.getComponent();
        // Made here, before anything is loaded: the bridge has to exist while the browser is still
        // being set up, and one bridge serves every page the panel goes on to draw.
        this.openQuery = browser == null ? null : JBCefJSQuery.create((JBCefBrowserBase) browser);
        if (openQuery != null) {
            Disposer.register(this, openQuery);
            openQuery.addHandler(this::openModule);
        }

        add(controls(), BorderLayout.NORTH);
        add(viewer, BorderLayout.CENTER);
        // Drawn when the contexts are in, not before: drawing prod-app first would draw a tree for
        // a context this is about to learn the app never boots under.
        offerContexts();
    }

    /**
     * Opens the module class a box was drawn for. The handler is called off the EDT by JCEF, and
     * resolving a class needs a read action, so both are asked for rather than assumed.
     */
    private JBCefJSQuery.Response openModule(String fqn) {
        ApplicationManager.getApplication().invokeLater(() -> {
            PhpClass phpClass = ReadAction.nonBlocking(() -> {
                // The drawing names the class the tree named, so the first the index answers with
                // is the one the tree was walked over; a name two classes answer to is a project
                // with two, and either is the module the box stands for.
                Iterator<PhpClass> classes = PhpIndex.getInstance(project).getClassesByFQN(fqn).iterator();

                return classes.hasNext() ? classes.next() : null;
            }).executeSynchronously();
            if (phpClass != null) {
                PsiNavigateUtil.navigate(phpClass);
            }
        }, project.getDisposed());

        return null;
    }

    private JComponent controls() {
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4)));
        controls.add(new JBLabel("Context:"));
        contextField.setEditable(true);
        contextField.setPrototypeDisplayValue("prod-hal-api-app-xxxx");
        contextField.setSelectedItem(DEFAULT_CONTEXT);
        controls.add(contextField);
        JButton draw = new JButton("Draw");
        draw.addActionListener(event -> draw());
        controls.add(draw);
        // A combo box fires on every selection, including the one that fills it, so only what the
        // reader did -- pressing enter, picking an item -- draws.
        contextField.addActionListener(event -> draw());

        return controls;
    }

    /**
     * Offers the contexts the app's own entry points name. Read off the EDT, because it is a scan
     * of the project's files, and a default that guesses at a convention yields to the first
     * context the app actually boots under.
     */
    private void offerContexts() {
        new Task.Backgroundable(project, "Reading the BEAR.Sunday contexts", true) {
            @Override
            public void run(ProgressIndicator indicator) {
                List<String> contexts = AppContextListService.getInstance(project).names();
                ApplicationManager.getApplication().invokeLater(() -> offer(contexts), project.getDisposed());
            }
        }.queue();
    }

    private void offer(List<String> contexts) {
        if (!contexts.isEmpty()) {
            // Whatever is typed stays typed: the list is an offer, and replacing what a reader
            // wrote with the first of it would take the panel away from them mid-sentence.
            String typed = context();
            boolean untouched = DEFAULT_CONTEXT.equals(typed);
            contextField.removeAllItems();
            contexts.forEach(contextField::addItem);
            contextField.setSelectedItem(untouched ? contexts.get(0) : typed);
        }
        draw();
    }

    /** What the field holds, typed or picked. */
    private String context() {
        Object editing = contextField.getEditor() == null ? null : contextField.getEditor().getItem();
        Object selected = editing == null ? contextField.getSelectedItem() : editing;

        return selected == null ? "" : selected.toString().trim();
    }

    private void draw() {
        String context = context();
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
                DiModuleTreeService.Drawn drawn = DiModuleTreeService.getInstance(project).readDrawn(context, true);
                ApplicationManager.getApplication().invokeLater(() -> show(drawn));
            }
        }.queue();
    }

    private void show(DiModuleTreeService.Drawn drawn) {
        JsonObject envelope = JsonParser.parseString(drawn.envelope()).getAsJsonObject();
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
        browser.loadHTML(ModuleTreeDiagramPage.html(
            diagram,
            openQuery == null ? null : drawn.nodes(),
            openQuery == null ? "" : openQuery.inject("fqn"),
            !JBColor.isBright()
        ));
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
