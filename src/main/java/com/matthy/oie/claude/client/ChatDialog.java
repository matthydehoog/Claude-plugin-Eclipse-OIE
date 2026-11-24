/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.client;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;

import com.fasterxml.jackson.databind.JsonNode;
import com.mirth.connect.client.ui.PlatformUI;

/** Non-modal chat window. One instance per Administrator session. */
class ChatDialog extends JDialog {

    private static final int POLL_MS = 800;

    private final JEditorPane transcript = new JEditorPane();
    private final JTextArea input = new JTextArea(4, 60);
    private final JLabel contextLabel = new JLabel();
    /** Suggested questions for the context, until the first question of the conversation. */
    private final JPanel suggestionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    /** Whether the current conversation has a question; suggestions only show before the first. */
    private boolean asked;
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton sendButton = new JButton("Send");
    private final JButton stopButton = new JButton("Stop");
    private final JButton newButton = new JButton("New conversation");
    private final JButton clearContextButton = new JButton("Clear context");
    private final JButton exportButton = new JButton("Export…");
    private final JButton saveAnswerButton = new JButton("Save answer…");
    private final JButton conversationsButton = new JButton("Conversations…");
    private final Timer pollTimer = new Timer(POLL_MS, e -> poll());

    /** HTML fragments of the transcript, in order. */
    private final List<String> entries = new ArrayList<>();
    /** Code blocks of the finished answers in the transcript, by the index in their Copy link. */
    private final List<String> codeBlocks = new ArrayList<>();
    /** Claude's answer while it is being written, shown after the entries. */
    private String partial = "";
    /** Claude's last complete answer, as Markdown, for Save answer. */
    private String lastAnswer;

    private String conversationId;
    private String context;
    private String jobId;
    private int lastSeq;
    private boolean polling;
    private String shownActionId;

    ChatDialog(JFrame owner) {
        super(owner, "AI Assistant", false);
        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        buildUi();
        setSize(new Dimension(900, 720));
        setLocationRelativeTo(owner);
        showWelcome();
    }

    private void buildUi() {
        transcript.setEditable(false);
        HTMLEditorKit kit = new HTMLEditorKit();
        StyleSheet css = kit.getStyleSheet();
        Font font = UIManager.getFont("Label.font");
        String family = font == null ? "sans-serif" : font.getFamily();
        css.addRule("body { font-family: " + family + "; font-size: 11pt; margin: 6px; }");
        css.addRule("pre { font-family: monospaced; font-size: 10pt; background-color: #f4f4f4; padding: 4px; }");
        css.addRule("code { font-family: monospaced; background-color: #f0f0f0; }");
        css.addRule("table { border-collapse: collapse; }");
        css.addRule("th { background-color: #e8e8e8; text-align: left; }");
        css.addRule(".user { background-color: #e9f1fb; padding: 6px; margin-top: 10px; }");
        css.addRule(".claude { padding: 2px 6px; }");
        css.addRule(".copy { text-align: right; font-size: 9pt; margin-top: 4px; }");
        css.addRule(".tool { color: #707070; font-size: 9pt; margin-left: 6px; }");
        css.addRule(".action { color: #1b6e20; margin: 4px 6px; }");
        css.addRule(".error { color: #b00020; margin: 4px 6px; }");
        css.addRule(".info { color: #8a5a00; margin: 4px 6px; }");
        transcript.setEditorKit(kit);
        transcript.setContentType("text/html");
        transcript.addHyperlinkListener(e -> {
            if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED && e.getDescription() != null && e.getDescription().startsWith(Markdown.COPY_LINK)) {
                copyCode(e.getDescription().substring(Markdown.COPY_LINK.length()));
            }
        });

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        contextLabel.setForeground(new Color(0x505050));
        top.add(contextLabel, BorderLayout.CENTER);
        JPanel topButtons = new JPanel();
        topButtons.add(clearContextButton);
        topButtons.add(conversationsButton);
        topButtons.add(exportButton);
        topButtons.add(saveAnswerButton);
        topButtons.add(newButton);
        top.add(topButtons, BorderLayout.EAST);
        suggestionsPanel.setVisible(false);
        top.add(suggestionsPanel, BorderLayout.SOUTH);

        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "send");
        input.getActionMap().put("send", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                send();
            }
        });

        JPanel bottom = new JPanel(new BorderLayout(6, 6));
        bottom.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        bottom.add(new JScrollPane(input), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new BorderLayout(0, 6));
        buttons.add(sendButton, BorderLayout.NORTH);
        buttons.add(stopButton, BorderLayout.SOUTH);
        bottom.add(buttons, BorderLayout.EAST);
        statusLabel.setForeground(new Color(0x707070));
        bottom.add(statusLabel, BorderLayout.SOUTH);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(new JScrollPane(transcript), BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);

        sendButton.setToolTipText("Send (Ctrl+Enter)");
        sendButton.addActionListener(e -> send());
        stopButton.addActionListener(e -> stop());
        newButton.addActionListener(e -> newConversation());
        clearContextButton.addActionListener(e -> setContext(null));
        exportButton.setToolTipText("Save this conversation as a Markdown file");
        exportButton.addActionListener(e -> exportConversation());
        saveAnswerButton.setToolTipText("Save only the AI Assistant's last answer as a Markdown file, for example a channel's documentation");
        saveAnswerButton.addActionListener(e -> saveAnswer());
        conversationsButton.setToolTipText("Continue one of your earlier conversations");
        conversationsButton.addActionListener(e -> chooseConversation());
        setContext(null);
        setBusy(false);
        setLastAnswer(null);
    }

    /** Opens the window with a context from the Administrator and a suggested question. */
    void open(String newContext, String suggestion) {
        open(newContext, suggestion, false);
    }

    /**
     * Opens the window with a context and a suggested question. The suggestion fills an empty
     * question box, or with replace (an explicit request such as Explain with AI Assistant) any.
     */
    void open(String newContext, String suggestion, boolean replace) {
        if (newContext != null) {
            setContext(newContext);
        }
        if (suggestion != null && (replace || input.getText().isBlank())) {
            input.setText(suggestion);
            input.setCaretPosition(0);
            if (!replace) {
                input.selectAll();
            }
        }
        setVisible(true);
        toFront();
        input.requestFocusInWindow();
    }

    private void setContext(String newContext) {
        context = newContext;
        contextLabel.setText(newContext == null ? "No context: the AI Assistant looks at the whole server." : "<html><b>Context:</b> " + Markdown.escape(newContext) + "</html>");
        clearContextButton.setEnabled(newContext != null);
        updateSuggestions();
    }

    /** Fixed suggested questions for the context (from the server, in the response language), before the first question. */
    private void updateSuggestions() {
        String forContext = context;
        if (forContext == null || asked) {
            showSuggestions(List.of());
            return;
        }
        background(() -> ClaudeApi.suggestions(forContext), result -> {
            if (forContext.equals(context) && !asked) {
                List<String> questions = new ArrayList<>();
                result.path("suggestions").forEach(q -> questions.add(q.asText()));
                showSuggestions(questions);
            }
        }, error -> showSuggestions(List.of()));
    }

    private void showSuggestions(List<String> questions) {
        suggestionsPanel.removeAll();
        if (!questions.isEmpty()) {
            suggestionsPanel.add(new JLabel("Ask:"));
        }
        for (String question : questions) {
            JButton button = new JButton(question);
            button.setToolTipText("Send this question");
            button.setMargin(new java.awt.Insets(1, 6, 1, 6));
            button.addActionListener(e -> {
                if (sendButton.isEnabled()) {
                    input.setText(question);
                    send();
                }
            });
            suggestionsPanel.add(button);
        }
        suggestionsPanel.setVisible(!questions.isEmpty());
        suggestionsPanel.revalidate();
        suggestionsPanel.repaint();
    }

    /** Copies a code block of the transcript exactly, without the Markdown fences. */
    private void copyCode(String index) {
        try {
            String code = codeBlocks.get(Integer.parseInt(index));
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(code), null);
            int lines = code.isEmpty() ? 0 : code.split("\n", -1).length;
            statusLabel.setText("Copied " + lines + (lines == 1 ? " line" : " lines") + " to the clipboard.");
        } catch (RuntimeException e) {
            statusLabel.setText("Could not copy the code block.");
        }
    }

    private void showWelcome() {
        entries.clear();
        codeBlocks.clear();
        entries.add("<div class=\"info\">Ask a question about your OIE server, for example <i>Which channels have errors?</i> or <i>Why does this message fail?</i><br>"
                + "Message content is masked before it is sent to the AI model. Actions such as deploying only run after you approve them.</div>");
        render();
    }

    private void newConversation() {
        if (jobId != null) {
            stop();
        }
        conversationId = null;
        partial = "";
        setLastAnswer(null);
        showWelcome();
        asked = false;
        updateSuggestions();
    }

    /** Saves the conversation as Markdown, as the server has it: what Claude received (masked) and answered. */
    private void exportConversation() {
        String id = conversationId;
        if (id == null) {
            JOptionPane.showMessageDialog(this, "There is no conversation to export yet.", "Export conversation", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        background(() -> ClaudeApi.exportConversation(id), result -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Export conversation");
            chooser.setSelectedFile(new File(result.path("fileName").asText("claude-conversation.md")));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            File file = chooser.getSelectedFile();
            if (file.exists() && JOptionPane.showConfirmDialog(this, file.getName() + " already exists. Replace it?", "Export conversation", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                return;
            }
            try {
                Files.write(file.toPath(), result.path("markdown").asText().getBytes(StandardCharsets.UTF_8));
                statusLabel.setText("Exported to " + file.getName());
            } catch (IOException e) {
                add("<div class=\"error\">Export failed: " + Markdown.escape(String.valueOf(e.getMessage())) + "</div>");
            }
        }, error -> add("<div class=\"error\">Export failed: " + Markdown.escape(error) + "</div>"));
    }

    /** Saves only Claude's last answer as a Markdown file, named after its first heading. */
    private void saveAnswer() {
        String answer = lastAnswer;
        if (answer == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save answer");
        chooser.setSelectedFile(new File(Markdown.fileName(answer, "claude-answer.md")));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().contains(".")) {
            file = new File(file.getParentFile(), file.getName() + ".md");
        }
        if (file.exists() && JOptionPane.showConfirmDialog(this, file.getName() + " already exists. Replace it?", "Save answer", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            Files.write(file.toPath(), (answer.trim() + "\n").getBytes(StandardCharsets.UTF_8));
            statusLabel.setText("Saved to " + file.getName());
        } catch (IOException e) {
            add("<div class=\"error\">Save failed: " + Markdown.escape(String.valueOf(e.getMessage())) + "</div>");
        }
    }

    private void setLastAnswer(String answer) {
        lastAnswer = answer;
        saveAnswerButton.setEnabled(answer != null);
    }

    /** Lists the user's conversations (current and saved) and continues the chosen one. */
    private void chooseConversation() {
        if (jobId != null) {
            JOptionPane.showMessageDialog(this, "Wait until the AI Assistant has finished, or click Stop.", "Conversations", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        background(ClaudeApi::conversations, result -> {
            List<String> ids = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            for (JsonNode c : result.path("conversations")) {
                ids.add(c.path("id").asText());
                labels.add(Instant.ofEpochMilli(c.path("lastUsed").asLong()).atZone(ZoneId.systemDefault()).format(format) + "   " + c.path("title").asText()
                        + (c.path("id").asText().equals(conversationId) ? "   (open)" : ""));
            }
            int keepDays = result.path("keepDays").asInt();
            String note = keepDays > 0 ? "Conversations are saved on the server for " + keepDays + " days." : "Conversations are not saved on the server: they are kept for 4 hours and lost on a restart (Settings > AI Assistant).";
            if (ids.isEmpty()) {
                JOptionPane.showMessageDialog(this, "You have no earlier conversations.\n" + note, "Conversations", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            JList<String> list = new JList<>(labels.toArray(new String[0]));
            list.setSelectedIndex(0);
            list.setVisibleRowCount(Math.min(12, labels.size()));
            JPanel panel = new JPanel(new BorderLayout(0, 8));
            panel.add(new JScrollPane(list), BorderLayout.CENTER);
            panel.add(new JLabel(note), BorderLayout.SOUTH);
            Object[] options = { "Continue", "Cancel" };
            int choice = JOptionPane.showOptionDialog(this, panel, "Conversations", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            if (choice != 0 || list.getSelectedIndex() < 0) {
                return;
            }
            String id = ids.get(list.getSelectedIndex());
            background(() -> ClaudeApi.resumeConversation(id), resumed -> showConversation(resumed), error -> add("<div class=\"error\">" + Markdown.escape(error) + "</div>"));
        }, error -> add("<div class=\"error\">" + Markdown.escape(error) + "</div>"));
    }

    private void showConversation(JsonNode resumed) {
        conversationId = resumed.path("conversationId").asText();
        entries.clear();
        codeBlocks.clear();
        setLastAnswer(null);
        for (JsonNode e : resumed.path("log")) {
            String text = e.path("text").asText();
            switch (e.path("type").asText()) {
                case "question":
                    entries.add("<div class=\"user\"><b>You:</b> " + Markdown.escape(text).replace("\n", "<br>") + "</div>");
                    break;
                case "text":
                    entries.add("<div class=\"claude\">" + Markdown.toHtml(text, codeBlocks) + "</div>");
                    setLastAnswer(text);
                    break;
                case "tool":
                    entries.add("<div class=\"tool\">&#8594; " + Markdown.escape(text) + "</div>");
                    break;
                case "action":
                    entries.add("<div class=\"action\"><b>Done:</b> " + Markdown.escape(text) + "</div>");
                    break;
                case "error":
                    entries.add("<div class=\"error\">" + Markdown.escape(text) + "</div>");
                    break;
                default:
                    entries.add("<div class=\"info\">" + Markdown.escape(text) + "</div>");
            }
        }
        entries.add("<div class=\"info\">Continued conversation. Ask your next question.</div>");
        render();
        asked = true;
        showSuggestions(List.of());
    }

    private void send() {
        String text = input.getText().trim();
        // The button is disabled from sending until the job finishes; Ctrl+Enter checks the same.
        if (text.isEmpty() || !sendButton.isEnabled()) {
            return;
        }
        String sentContext = context;
        asked = true;
        showSuggestions(List.of());
        add("<div class=\"user\"><b>You:</b> " + Markdown.escape(text).replace("\n", "<br>") + "</div>");
        input.setText("");
        setBusy(true);
        statusLabel.setText("Sending…");
        background(() -> ClaudeApi.chat(conversationId, text, sentContext), result -> {
            conversationId = result.path("conversationId").asText();
            jobId = result.path("jobId").asText();
            lastSeq = 0;
            shownActionId = null;
            statusLabel.setText("AI Assistant is thinking…");
            pollTimer.start();
        }, error -> {
            add("<div class=\"error\">" + Markdown.escape(error) + "</div>");
            setBusy(false);
        });
    }

    private void stop() {
        String id = jobId;
        if (id == null) {
            return;
        }
        pollTimer.stop();
        jobId = null;
        setBusy(false);
        partial = "";
        add("<div class=\"info\">Stopped.</div>");
        background(() -> {
            ClaudeApi.cancel(id);
            return null;
        }, r -> {}, e -> {});
    }

    private void poll() {
        if (polling || jobId == null) {
            return;
        }
        polling = true;
        String id = jobId;
        background(() -> ClaudeApi.job(id, lastSeq), job -> {
            polling = false;
            if (!id.equals(jobId)) {
                return;
            }
            handle(job);
        }, error -> {
            polling = false;
            pollTimer.stop();
            jobId = null;
            setBusy(false);
            partial = "";
            add("<div class=\"error\">Lost connection to the server: " + Markdown.escape(error) + "</div>");
        });
    }

    private void handle(JsonNode job) {
        for (JsonNode e : job.path("events")) {
            lastSeq = Math.max(lastSeq, e.path("seq").asInt());
            String text = e.path("text").asText();
            switch (e.path("type").asText()) {
                case "text":
                    add("<div class=\"claude\">" + Markdown.toHtml(text, codeBlocks) + "</div>");
                    setLastAnswer(text);
                    break;
                case "tool":
                    add("<div class=\"tool\">&#8594; " + Markdown.escape(text) + "</div>");
                    statusLabel.setText("AI Assistant is querying the server…");
                    break;
                case "action":
                    add("<div class=\"action\"><b>Done:</b> " + Markdown.escape(text) + "</div>");
                    break;
                case "error":
                    add("<div class=\"error\">" + Markdown.escape(text) + "</div>");
                    break;
                default:
                    add("<div class=\"info\">" + Markdown.escape(text) + "</div>");
            }
        }
        String newPartial = job.path("partial").asText("");
        if (!newPartial.equals(partial)) {
            partial = newPartial;
            if (!partial.isEmpty()) {
                statusLabel.setText("AI Assistant is writing…");
            }
            render();
        }
        String state = job.path("state").asText();
        JsonNode pending = job.path("pendingAction");
        if ("WAITING".equals(state) && pending.isObject() && !pending.path("id").asText().equals(shownActionId)) {
            shownActionId = pending.path("id").asText();
            statusLabel.setText("Waiting for your approval…");
            SwingUtilities.invokeLater(() -> askConfirmation(pending));
        }
        JsonNode review = job.path("pendingReview");
        if ("WAITING".equals(state) && review.isObject() && !review.path("id").asText().equals(shownActionId)) {
            shownActionId = review.path("id").asText();
            statusLabel.setText("Waiting for your review…");
            SwingUtilities.invokeLater(() -> askReview(review));
        }
        if ("DONE".equals(state) || "ERROR".equals(state) || "CANCELLED".equals(state)) {
            pollTimer.stop();
            jobId = null;
            setBusy(false);
        }
    }

    private void askConfirmation(JsonNode pending) {
        String id = jobId;
        if (id == null) {
            return;
        }
        String actionId = pending.path("id").asText();
        JTextArea detail = new JTextArea(pending.path("detail").asText(), 18, 80);
        detail.setEditable(false);
        detail.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        detail.setCaretPosition(0);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("<html>The AI Assistant wants to run the following action on server <b>" + Markdown.escape(String.valueOf(PlatformUI.SERVER_NAME)) + "</b>:</html>"), BorderLayout.NORTH);
        panel.add(approvalBody(pending.path("changes").asText(null), detail), BorderLayout.CENTER);
        Object[] options = { "Run", "Reject" };
        int choice = JOptionPane.showOptionDialog(this, panel, "Approve: " + pending.path("title").asText(), JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        boolean approved = choice == 0;
        statusLabel.setText(approved ? "Running action…" : "AI Assistant continues…");
        background(() -> ClaudeApi.confirm(id, actionId, approved), r -> statusLabel.setText("AI Assistant continues…"), error -> add("<div class=\"error\">Approval failed: " + Markdown.escape(error) + "</div>"));
    }

    /**
     * For a script, filter or transformer change: the diff (Changes, the default) with a toggle to the
     * full details. Otherwise the details alone.
     */
    private static JComponent approvalBody(String changes, JTextArea detail) {
        JScrollPane full = new JScrollPane(detail);
        if (changes == null) {
            return full;
        }
        JScrollPane diff = new JScrollPane(diffPane(changes));
        diff.setPreferredSize(full.getPreferredSize());
        CardLayout cards = new CardLayout();
        JPanel views = new JPanel(cards);
        views.add(diff, "changes");
        views.add(full, "full");
        JToggleButton changesButton = new JToggleButton("Changes", true);
        JToggleButton fullButton = new JToggleButton("Full details");
        ButtonGroup group = new ButtonGroup();
        group.add(changesButton);
        group.add(fullButton);
        changesButton.addActionListener(e -> cards.show(views, "changes"));
        fullButton.addActionListener(e -> cards.show(views, "full"));
        JPanel toggle = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        toggle.add(changesButton);
        toggle.add(fullButton);
        JPanel body = new JPanel(new BorderLayout(0, 4));
        body.add(toggle, BorderLayout.NORTH);
        body.add(views, BorderLayout.CENTER);
        return body;
    }

    /** The diff with added lines green, removed lines red and hunk headers grey (from the first "=== " block on). */
    private static JTextPane diffPane(String changes) {
        JTextPane pane = new JTextPane();
        pane.setEditable(false);
        pane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        StyledDocument doc = pane.getStyledDocument();
        SimpleAttributeSet plain = new SimpleAttributeSet();
        SimpleAttributeSet added = new SimpleAttributeSet();
        StyleConstants.setBackground(added, new Color(0xD4F4DC));
        SimpleAttributeSet removed = new SimpleAttributeSet();
        StyleConstants.setBackground(removed, new Color(0xFADBD8));
        SimpleAttributeSet hunk = new SimpleAttributeSet();
        StyleConstants.setForeground(hunk, Color.GRAY);
        SimpleAttributeSet block = new SimpleAttributeSet();
        StyleConstants.setBold(block, true);
        boolean inBlocks = false;
        try {
            for (String line : changes.split("\n", -1)) {
                inBlocks |= line.startsWith("=== ");
                SimpleAttributeSet style = !inBlocks ? plain : line.startsWith("=== ") ? block : line.startsWith("@@ ") ? hunk
                        : line.startsWith("+ ") ? added : line.startsWith("- ") ? removed : plain;
                doc.insertString(doc.getLength(), line + "\n", style);
            }
        } catch (BadLocationException e) {
            pane.setText(changes);
        }
        pane.setCaretPosition(0);
        return pane;
    }

    /** Shows exactly what Claude would receive; the user can edit it, send it or withhold it. */
    private void askReview(JsonNode review) {
        String id = jobId;
        if (id == null) {
            return;
        }
        String reviewId = review.path("id").asText();
        String original = review.path("detail").asText();
        JTextArea text = new JTextArea(original, 20, 90);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        text.setCaretPosition(0);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("<html>This is exactly what will be sent to the AI model from server <b>" + Markdown.escape(String.valueOf(PlatformUI.SERVER_NAME))
                + "</b>, after masking.<br>Edit it to remove anything the AI model should not see; edited text is masked again before sending.</html>"), BorderLayout.NORTH);
        panel.add(new JScrollPane(text), BorderLayout.CENTER);
        panel.add(new JLabel(original.length() + " characters. Closing this dialog sends nothing."), BorderLayout.SOUTH);
        Object[] options = { "Send to the AI model", "Don't send" };
        int choice = JOptionPane.showOptionDialog(this, panel, "Review before sending: " + review.path("title").asText(), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        boolean approved = choice == 0;
        String edited = text.getText();
        statusLabel.setText("AI Assistant continues…");
        background(() -> {
            ClaudeApi.review(id, reviewId, approved, edited);
            return null;
        }, r -> {}, error -> add("<div class=\"error\">Review failed: " + Markdown.escape(error) + "</div>"));
    }

    private void setBusy(boolean busy) {
        sendButton.setEnabled(!busy);
        stopButton.setEnabled(busy);
        if (!busy) {
            statusLabel.setText(" ");
        }
    }

    private void add(String html) {
        entries.add(html);
        render();
    }

    private void render() {
        String writing = partial.isEmpty() ? "" : "<div class=\"claude\">" + Markdown.toHtml(partial) + "</div>";
        transcript.setText("<html><body>" + String.join("", entries) + writing + "</body></html>");
        SwingUtilities.invokeLater(() -> transcript.setCaretPosition(transcript.getDocument().getLength()));
    }

    private interface Call<T> {
        T call() throws Exception;
    }

    /** Runs a server call off the event thread and hands the result (or error text) back on it. */
    private static <T> void background(Call<T> call, Consumer<T> onSuccess, Consumer<String> onError) {
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return call.call();
            }

            @Override
            protected void done() {
                try {
                    onSuccess.accept(get());
                } catch (Exception e) {
                    onError.accept(ClaudeApi.message(e));
                }
            }
        }.execute();
    }
}
