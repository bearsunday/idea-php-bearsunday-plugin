package idea.bear.sunday.input;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import idea.bear.sunday.BearSundayBundle;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class ExtractInputDtoDialog extends DialogWrapper {
    private static final Pattern BY_REFERENCE = Pattern.compile("&\\s*\\$");

    private final JBTextField className = new JBTextField("Input", 24);
    private final JBTextField variableName = new JBTextField("input", 24);
    private final List<ParamCheckBox> parameterBoxes = new ArrayList<>();
    private boolean variableNameManuallyEdited;
    private boolean updatingVariableName;

    ExtractInputDtoDialog(Project project, List<ExtractInputDtoTextRefactoring.ParamInfo> params, Set<String> initiallySelected) {
        super(project);
        setTitle(BearSundayBundle.message("input.dialog.title"));
        boolean hasInitialSelection = !initiallySelected.isEmpty();
        for (ExtractInputDtoTextRefactoring.ParamInfo param : params) {
            boolean supported = param.supported();
            boolean selected = supported && (!hasInitialSelection || initiallySelected.contains(param.name()));
            JBCheckBox checkBox = new JBCheckBox(labelFor(param), selected);
            if (!supported) {
                checkBox.setEnabled(false);
                checkBox.setToolTipText(unsupportedReason(param));
            }
            parameterBoxes.add(new ParamCheckBox(param.name(), supported, checkBox));
        }
        installVariableNameSync();
        init();
    }

    private void installVariableNameSync() {
        className.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                syncVariableName();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                syncVariableName();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                syncVariableName();
            }
        });
        variableName.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                markManualEdit();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                markManualEdit();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                markManualEdit();
            }
        });
    }

    private void syncVariableName() {
        if (variableNameManuallyEdited) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (variableNameManuallyEdited) {
                return;
            }
            updatingVariableName = true;
            try {
                variableName.setText(defaultVariableName(getDtoClass()));
            } finally {
                updatingVariableName = false;
            }
        });
    }

    private void markManualEdit() {
        if (!updatingVariableName) {
            variableNameManuallyEdited = true;
        }
    }

    private static String defaultVariableName(String dtoClass) {
        String stripped = dtoClass.trim().replaceFirst("^\\$", "");
        if (stripped.isEmpty()) {
            return "";
        }
        if (stripped.length() == 1) {
            return stripped.toLowerCase();
        }
        return Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
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
        panel.add(new JLabel(BearSundayBundle.message("input.dialog.dto.class")), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(className, c);

        c.gridx = 0;
        c.gridy++;
        c.weightx = 0;
        panel.add(new JLabel(BearSundayBundle.message("input.dialog.variable")), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(variableName, c);

        c.gridx = 0;
        c.gridy++;
        c.weightx = 0;
        c.gridwidth = 2;
        panel.add(new JLabel(BearSundayBundle.message("input.dialog.parameters")), c);

        for (ParamCheckBox box : parameterBoxes) {
            c.gridy++;
            panel.add(box.checkBox(), c);
        }

        return panel;
    }

    @Override
    protected void doOKAction() {
        if (getDtoClass().isBlank()) {
            setErrorText(BearSundayBundle.message("input.error.dto.class.required"));
            return;
        }
        if (getDtoVariable().isBlank()) {
            setErrorText(BearSundayBundle.message("input.error.variable.required"));
            return;
        }
        if (getSelectedParameterNames().isEmpty()) {
            setErrorText(BearSundayBundle.message("input.error.parameter.required"));
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
            if (box.supported() && box.checkBox().isSelected()) {
                names.add(box.name());
            }
        }
        return names;
    }

    private static String labelFor(ExtractInputDtoTextRefactoring.ParamInfo param) {
        if (param.supported()) {
            return param.originalText();
        }
        return param.originalText() + " — " + unsupportedReason(param);
    }

    private static String unsupportedReason(ExtractInputDtoTextRefactoring.ParamInfo param) {
        String text = param.originalText();
        if (BY_REFERENCE.matcher(text).find()) {
            return BearSundayBundle.message("input.unsupported.by.reference");
        }
        if (text.contains("...")) {
            return BearSundayBundle.message("input.unsupported.variadic");
        }
        if (text.contains("#[InputFile]")) {
            return BearSundayBundle.message("input.unsupported.input.file");
        }
        if (text.contains("#[Input]")) {
            return BearSundayBundle.message("input.unsupported.input");
        }
        return BearSundayBundle.message("input.unsupported.generic");
    }

    private record ParamCheckBox(String name, boolean supported, JBCheckBox checkBox) {
    }
}
