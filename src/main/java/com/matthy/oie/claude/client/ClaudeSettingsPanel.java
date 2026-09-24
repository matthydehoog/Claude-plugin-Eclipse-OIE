package com.matthy.oie.claude.client;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.net.URI;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
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

/**
 * Settings > Claude Assistant: API keys, model, effort, tool-call limit, extra mask patterns, and
 * this month's spend from the Anthropic Cost API.
 */
public class ClaudeSettingsPanel extends AbstractSettingsPanel {

    private static final String[] MODELS = { "claude-opus-5", "claude-opus-5-5", "claude-sonnet-5", "claude-fable-5-1", "claude-haiku-4-5" };
    private static final String[] EFFORTS = { "low", "medium", "high", "xhigh", "max" };
    /** The credit balance has no API, so the tab links to the Console's billing page instead. */
    private static final String BILLING_URL = "https://platform.claude.com/settings/billing";

    private final JPasswordField apiKeyField = new JPasswordField(40);
    private final JLabel apiKeyStatus = new JLabel();
    private final JComboBox<String> modelBox = new JComboBox<>(MODELS);
    private final JComboBox<String> effortBox = new JComboBox<>(EFFORTS);
    private final JSpinner maxToolCalls = new JSpinner(new SpinnerNumberModel(25, 1, 100, 1));
    private final JTextArea maskPatterns = new JTextArea(4, 50);

    private final JPasswordField adminKeyField = new JPasswordField(40);
    private final JLabel adminKeyStatus = new JLabel();
    private final JCheckBox clearAdminKey = new JCheckBox("Remove the Admin API key");
    private final JLabel spendOrganization = new JLabel("–");
    private final JLabel spendWorkspace = new JLabel("");
    private final JLabel spendNote = new JLabel(" ");
    private final JButton refreshSpend = new JButton("Refresh");
    private final JButton openBilling = new JButton("Open Billing in Console");

    private boolean loading;

    public ClaudeSettingsPanel(String tabName) {
        super(tabName);
        setLayout(new MigLayout("insets 12, novisualpadding, hidemode 3, fill", "[right][grow]"));
        setBackground(Color.WHITE);

        modelBox.setEditable(true);
        maskPatterns.setLineWrap(true);
        clearAdminKey.setBackground(Color.WHITE);
        spendOrganization.setFont(spendOrganization.getFont().deriveFont(Font.BOLD));
        spendNote.setForeground(new Color(0x707070));

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
                + "(HL7 PID, GDT 3000-3107, BSN).<br>The API keys are stored encrypted on the server and never sent back to the Administrator. "
                + "Leave a key field empty to keep the current key.</html>"), "wrap, w 600!");

        form.add(new JSeparator(), "span, growx, gaptop 12, gapbottom 6, wrap");
        form.add(new JLabel("<html><b>Usage</b></html>"), "span, left, wrap");
        form.add(new JLabel("Admin API key (optional):"));
        form.add(adminKeyField, "wrap, growx");
        form.add(new JLabel(""));
        // Own left-aligned rows: the checkbox and button sit next to their text instead of being
        // pushed to the edge of the stretched column, and hiding the checkbox cannot drop a "wrap".
        form.add(leftRow(adminKeyStatus, clearAdminKey), "wrap");
        form.add(new JLabel("Spend this month:"));
        form.add(leftRow(spendOrganization, refreshSpend), "wrap");
        form.add(new JLabel(""));
        form.add(spendWorkspace, "wrap");
        form.add(new JLabel("Credits:"));
        form.add(openBilling, "wrap, growx 0");
        form.add(new JLabel(""));
        form.add(spendNote, "wrap, w 600!");
        form.add(new JLabel(""));
        form.add(new JLabel("<html>Spend comes from the Anthropic Cost API and needs an Admin API key (<code>sk-ant-admin…</code>, "
                + "created by an organization admin in the Console). It is in USD, since the 1st of the month (UTC), and runs up to about "
                + "5 minutes behind. The credit balance is not available through the API; the button opens the Console's billing page.</html>"), "wrap, w 600!");
        add(form, "growx, top");

        DocumentListener changed = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { markChanged(); }
            public void removeUpdate(DocumentEvent e) { markChanged(); }
            public void changedUpdate(DocumentEvent e) { markChanged(); }
        };
        apiKeyField.getDocument().addDocumentListener(changed);
        adminKeyField.getDocument().addDocumentListener(changed);
        maskPatterns.getDocument().addDocumentListener(changed);
        modelBox.addActionListener(e -> markChanged());
        effortBox.addActionListener(e -> markChanged());
        maxToolCalls.addChangeListener(e -> markChanged());
        clearAdminKey.addActionListener(e -> markChanged());
        refreshSpend.addActionListener(e -> loadSpend(true));
        openBilling.addActionListener(e -> openBilling());
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
        body.put("adminApiKey", new String(adminKeyField.getPassword()).trim());
        body.put("clearAdminApiKey", clearAdminKey.isSelected());
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
            adminKeyField.setText("");
            clearAdminKey.setSelected(false);
            boolean adminSet = s.path("adminApiKeySet").asBoolean();
            adminKeyStatus.setText(adminSet ? "Set: " + s.path("adminApiKeyHint").asText() : "No Admin API key set.");
            clearAdminKey.setVisible(adminSet);
            modelBox.setSelectedItem(s.path("model").asText(MODELS[0]));
            effortBox.setSelectedItem(s.path("effort").asText("high"));
            maxToolCalls.setValue(s.path("maxToolCalls").asInt(25));
            maskPatterns.setText(s.path("maskPatterns").asText(""));
            setSaveEnabled(false);
        } finally {
            loading = false;
        }
        refreshSpend.setEnabled(s.path("adminApiKeySet").asBoolean());
        if (s.path("adminApiKeySet").asBoolean()) {
            loadSpend(false);
        } else {
            showSpend(null, "Set an Admin API key to show the spend this month.");
        }
    }

    private void loadSpend(boolean refresh) {
        spendOrganization.setText("Loading…");
        spendNote.setText(" ");
        refreshSpend.setEnabled(false);
        new SwingWorker<JsonNode, Void>() {
            @Override
            protected JsonNode doInBackground() throws Exception {
                return ClaudeApi.spend(refresh);
            }

            @Override
            protected void done() {
                refreshSpend.setEnabled(true);
                try {
                    showSpend(get(), null);
                } catch (Exception e) {
                    showSpend(null, ClaudeApi.message(e));
                }
            }
        }.execute();
    }

    private void showSpend(JsonNode spend, String problem) {
        if (spend == null) {
            spendOrganization.setText("–");
            spendWorkspace.setText("");
            spendNote.setText(problem == null ? " " : "<html>" + Markdown.escape(problem) + "</html>");
            return;
        }
        spendOrganization.setText("$" + spend.path("organizationUsd").asText("0.00") + "  (organization, since " + spend.path("monthStart").asText() + ")");
        if (spend.has("workspaceUsd")) {
            spendWorkspace.setText("of which workspace '" + spend.path("workspaceName").asText("?") + "' (the plugin's API key): $" + spend.path("workspaceUsd").asText("0.00"));
        } else {
            spendWorkspace.setText("");
        }
        if (spend.has("workspaceUsd")) {
            spendNote.setText(" ");
        } else if (spend.has("pluginKeyName")) {
            spendNote.setText("The plugin's API key has no workspace of its own, so only the organization total is shown.");
        } else {
            spendNote.setText("The plugin's API key was not found in this organization, so only the organization total is shown.");
        }
    }

    private static JPanel leftRow(JComponent first, JComponent second) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setBackground(Color.WHITE);
        row.add(first);
        row.add(Box.createHorizontalStrut(12));
        row.add(second);
        return row;
    }

    private void openBilling() {
        try {
            Desktop.getDesktop().browse(new URI(BILLING_URL));
        } catch (Exception e) {
            getFrame().alertInformation(getFrame(), "Open this page in your browser to see the credit balance:\n" + BILLING_URL);
        }
    }
}
