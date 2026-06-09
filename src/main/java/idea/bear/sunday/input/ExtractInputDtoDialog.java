package idea.bear.sunday.input;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ExtractInputDtoDialog extends DialogWrapper {
    private final JBTextField className = new JBTextField("Input", 24);
    private final JBTextField variableName = new JBTextField("input", 24);
    private final List<ParamCheckBox> parameterBoxes = new ArrayList<>();

    ExtractInputDtoDialog(Project project, List<ExtractInputDtoTextRefactoring.ParamInfo> params, Set<String> initiallySelected) {
        super(project);
        setTitle("Extract BEAR Input DTO");
        boolean hasInitialSelection = !initiallySelected.isEmpty();
        for (ExtractInputDtoTextRefactoring.ParamInfo param : params) {
            if (!param.supported()) {
                continue;
            }
            boolean selected = !hasInitialSelection || initiallySelected.contains(param.name());
            parameterBoxes.add(new ParamCheckBox(param.name(), param.type(), selected));
        }
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.gridy = 0;
        panel.add(new JLabel("DTO class:"), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(className, c);

        c.gridx = 0;
        c.gridy++;
        c.weightx = 0;
        panel.add(new JLabel("Variable:"), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(variableName, c);

        c.gridx = 0;
        c.gridy++;
        c.weightx = 0;
        c.gridwidth = 2;
        panel.add(new JLabel("Parameters:"), c);

        for (ParamCheckBox box : parameterBoxes) {
            c.gridy++;
            panel.add(box.checkBox(), c);
        }

        return panel;
    }

    @Override
    protected void doOKAction() {
        if (getDtoClass().isBlank()) {
            setErrorText("DTO class is required");
            return;
        }
        if (getDtoVariable().isBlank()) {
            setErrorText("Variable name is required");
            return;
        }
        if (getSelectedParameterNames().isEmpty()) {
            setErrorText("Select at least one parameter");
            return;
        }
        setErrorText(null);
        super.doOKAction();
    }

    String getDtoClass() {
        return className.getText().trim();
    }

    String getDtoVariable() {
        return variableName.getText().trim().replaceFirst("^\\$", "");
    }

    Set<String> getSelectedParameterNames() {
        Set<String> names = new LinkedHashSet<>();
        for (ParamCheckBox box : parameterBoxes) {
            if (box.checkBox().isSelected()) {
                names.add(box.name());
            }
        }
        return names;
    }

    private record ParamCheckBox(String name, String type, JBCheckBox checkBox) {
        ParamCheckBox(String name, String type, boolean selected) {
            this(name, type, new JBCheckBox(type + " $" + name, selected));
        }
    }
}
