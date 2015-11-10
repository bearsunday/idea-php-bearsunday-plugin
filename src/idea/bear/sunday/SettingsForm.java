package idea.bear.sunday;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import idea.bear.sunday.stubs.util.IndexUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class SettingsForm implements Configurable {

    private Project project;

    private JPanel panel;

    private JCheckBox pluginEnabled;

    public SettingsForm(@NotNull final Project project) {
        this.project = project;
    }

    @Nls
    @Override
    public String getDisplayName() {
        return "BEAR.Sunday Plugin";
    }

    @Nullable
    @Override
    public String getHelpTopic() {
        return null;
    }

    public JComponent createComponent() {
        return (JComponent) panel;
    }

    @Override
    public boolean isModified() {
        return !pluginEnabled.isSelected() == getSettings().pluginEnabled;
    }

    @Override
    public void apply() throws ConfigurationException {
        getSettings().pluginEnabled = pluginEnabled.isSelected();
    }

    @Override
    public void reset() {
        pluginEnabled.setSelected(getSettings().pluginEnabled);
    }

    @Override
    public void disposeUIResources() {
    }

    private Settings getSettings() {
        return Settings.getInstance(project);
    }

    private void createUIComponents() {
        // TODO: place custom component creation code here
    }
}
