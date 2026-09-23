package com.matthy.oie.claude.client;

import java.awt.Color;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mirth.connect.client.ui.AbstractSettingsPanel;
import com.mirth.connect.client.ui.PlatformUI;

import net.miginfocom.swing.MigLayout;

/** Settings > Claude Assistant: API key, model, effort, tool-call limit and extra mask patterns. */
public class ClaudeSettingsPanel extends AbstractSettingsPanel {

    private static final String[] MODELS = { "claude-opus-5", "claude-opus-5-5", "claude-sonnet-5", "claude-fable-5-1", "claude-haiku-4-5" };
    private static final String[] EFFORTS = { "low", "medium", "high", "xhigh", "max" };

    private final JPasswordField apiKeyField = new JPasswordField(40);
    private final JLabel apiKeyStatus = new JLabel();
    private final JComboBox<String> modelBox = new JComboBox<>(MODELS);
    private final JComboBox<String> effortBox = new JComboBox<>(EFFORTS);
    private final JSpinner maxToolCalls = new JSpinner(new SpinnerNumberModel(25, 1, 100, 1));
    private final JTextArea maskPatterns = new JTextArea(4, 50);
    private boolean loading;

    public ClaudeSettingsPanel(String tabName) {
        super(tabName);
        setLayout(new MigLayout("insets 12, novisualpadding, hidemode 3, fill", "[right][grow]"));
        setBackground(Color.WHITE);

        modelBox.setEditable(true);
        maskPatterns.setLineWrap(true);

        JPanel form = new JPanel(new MigLayout("insets 0, novisualpadding, hidemode 3", "[right][grow,fill]"));
        form.setBackground(Color.WHITE);
        form.add(new JLabel("Anthropic API key:"));
        form.add(apiKeyField, "wrap, growx");
        form.add(new JLabel(""));
        form.add(apiKeyStatus, "wrap");
        form.add(new JLabel("Model:"));
        form.add(modelBox, "wrap, w 260!");
        form.add(new JLabel("Effort:"));
        form.add(effortBox, "wrap, w 120!");
        form.add(new JLabel("Max. tool calls per question:"));
        form.add(maxToolCalls, "wrap, w 80!");
        form.add(new JLabel("Extra mask patterns:"), "top");
        form.add(new JScrollPane(maskPatterns), "wrap, growx, h 80!");
        form.add(new JLabel(""));
        form.add(new JLabel("<html>Regular expressions, separated by <code>;;</code>. They are masked in addition to the built-in rules "
                + "(HL7 PID, GDT 3000-3107, BSN).<br>The API key is stored encrypted on the server and never sent back to the Administrator. "
                + "Leave the field empty to keep the current key.</html>"), "wrap, w 600!");
        add(form, "growx, top");

        DocumentListener changed = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { markChanged(); }
            public void removeUpdate(DocumentEvent e) { markChanged(); }
            public void changedUpdate(DocumentEvent e) { markChanged(); }
        };
        apiKeyField.getDocument().addDocumentListener(changed);
        maskPatterns.getDocument().addDocumentListener(changed);
        modelBox.addActionListener(e -> markChanged());
        effortBox.addActionListener(e -> markChanged());
        maxToolCalls.addChangeListener(e -> markChanged());
    }

    private void markChanged() {
        if (!loading) {
            setSaveEnabled(true);
        }
    }

    @Override
    public void doRefresh() {
        if (PlatformUI.MIRTH_FRAME.alertRefresh()) {
            return;
        }
        final String workingId = getFrame().startWorking("Loading " + getTabName() + " settings...");
        new SwingWorker<JsonNode, Void>() {
            @Override
            protected JsonNode doInBackground() throws Exception {
                return ClaudeApi.settings();
            }

            @Override
            protected void done() {
                try {
                    show(get());
                } catch (Exception e) {
                    getFrame().alertError(getFrame(), "Loading settings failed: " + ClaudeApi.message(e));
                } finally {
                    getFrame().stopWorking(workingId);
                }
            }
        }.execute();
    }

    @Override
    public boolean doSave() {
        final ObjectNode body = ClaudeApi.MAPPER.createObjectNode();
        body.put("apiKey", new String(apiKeyField.getPassword()).trim());
        body.put("model", String.valueOf(modelBox.getSelectedItem()).trim());
        body.put("effort", String.valueOf(effortBox.getSelectedItem()));
        body.put("maxToolCalls", (Integer) maxToolCalls.getValue());
        body.put("maskPatterns", maskPatterns.getText().trim());

        final String workingId = getFrame().startWorking("Saving " + getTabName() + " settings...");
        new SwingWorker<JsonNode, Void>() {
            @Override
            protected JsonNode doInBackground() throws Exception {
                return ClaudeApi.saveSettings(body);
            }

            @Override
            protected void done() {
                try {
                    show(get());
                } catch (Exception e) {
                    getFrame().alertError(getFrame(), "Saving failed: " + ClaudeApi.message(e));
                    setSaveEnabled(true);
                } finally {
                    getFrame().stopWorking(workingId);
                }
            }
        }.execute();
        return true;
    }

    private void show(JsonNode s) {
        loading = true;
        try {
            apiKeyField.setText("");
            apiKeyStatus.setText(s.path("apiKeySet").asBoolean() ? "Set: " + s.path("apiKeyHint").asText() : "No API key set yet.");
            modelBox.setSelectedItem(s.path("model").asText(MODELS[0]));
            effortBox.setSelectedItem(s.path("effort").asText("high"));
            maxToolCalls.setValue(s.path("maxToolCalls").asInt(25));
            maskPatterns.setText(s.path("maskPatterns").asText(""));
            setSaveEnabled(false);
        } finally {
            loading = false;
        }
    }
}
