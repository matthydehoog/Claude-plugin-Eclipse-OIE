package com.matthy.oie.claude.client;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
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
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton sendButton = new JButton("Send");
    private final JButton stopButton = new JButton("Stop");
    private final JButton newButton = new JButton("New conversation");
    private final JButton clearContextButton = new JButton("Clear context");
    private final Timer pollTimer = new Timer(POLL_MS, e -> poll());

    /** HTML fragments of the transcript, in order. */
    private final List<String> entries = new ArrayList<>();

    private String conversationId;
    private String context;
    private String jobId;
    private int lastSeq;
    private boolean polling;
    private String shownActionId;

    ChatDialog(JFrame owner) {
        super(owner, "Claude Assistant", false);
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
        css.addRule(".tool { color: #707070; font-size: 9pt; margin-left: 6px; }");
        css.addRule(".action { color: #1b6e20; margin: 4px 6px; }");
        css.addRule(".error { color: #b00020; margin: 4px 6px; }");
        css.addRule(".info { color: #8a5a00; margin: 4px 6px; }");
        transcript.setEditorKit(kit);
        transcript.setContentType("text/html");

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        contextLabel.setForeground(new Color(0x505050));
        top.add(contextLabel, BorderLayout.CENTER);
        JPanel topButtons = new JPanel();
        topButtons.add(clearContextButton);
        topButtons.add(newButton);
        top.add(topButtons, BorderLayout.EAST);

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
        setContext(null);
        setBusy(false);
    }

    /** Opens the window with a context from the Administrator and a suggested question. */
    void open(String newContext, String suggestion) {
        if (newContext != null) {
            setContext(newContext);
        }
        if (suggestion != null && input.getText().isBlank()) {
            input.setText(suggestion);
            input.selectAll();
        }
        setVisible(true);
        toFront();
        input.requestFocusInWindow();
    }

    private void setContext(String newContext) {
        context = newContext;
        contextLabel.setText(newContext == null ? "No context: Claude looks at the whole server." : "<html><b>Context:</b> " + Markdown.escape(newContext) + "</html>");
        clearContextButton.setEnabled(newContext != null);
    }

    private void showWelcome() {
        entries.clear();
        entries.add("<div class=\"info\">Ask a question about your OIE server, for example <i>Which channels have errors?</i> or <i>Why does this message fail?</i><br>"
                + "Message content is masked before it is sent to Claude. Actions such as deploying only run after you approve them.</div>");
        render();
    }

    private void newConversation() {
        if (jobId != null) {
            stop();
        }
        conversationId = null;
        showWelcome();
    }

    private void send() {
        String text = input.getText().trim();
        // The button is disabled from sending until the job finishes; Ctrl+Enter checks the same.
        if (text.isEmpty() || !sendButton.isEnabled()) {
            return;
        }
        String sentContext = context;
        add("<div class=\"user\"><b>You:</b> " + Markdown.escape(text).replace("\n", "<br>") + "</div>");
        input.setText("");
        setBusy(true);
        statusLabel.setText("Sending…");
        background(() -> ClaudeApi.chat(conversationId, text, sentContext), result -> {
            conversationId = result.path("conversationId").asText();
            jobId = result.path("jobId").asText();
            lastSeq = 0;
            shownActionId = null;
            statusLabel.setText("Claude is thinking…");
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
            add("<div class=\"error\">Lost connection to the server: " + Markdown.escape(error) + "</div>");
        });
    }

    private void handle(JsonNode job) {
        for (JsonNode e : job.path("events")) {
            lastSeq = Math.max(lastSeq, e.path("seq").asInt());
            String text = e.path("text").asText();
            switch (e.path("type").asText()) {
                case "text":
                    add("<div class=\"claude\">" + Markdown.toHtml(text) + "</div>");
                    break;
                case "tool":
                    add("<div class=\"tool\">&#8594; " + Markdown.escape(text) + "</div>");
                    statusLabel.setText("Claude is querying the server…");
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
        panel.add(new JLabel("<html>Claude wants to run the following action on server <b>" + Markdown.escape(String.valueOf(PlatformUI.SERVER_NAME)) + "</b>:</html>"), BorderLayout.NORTH);
        panel.add(new JScrollPane(detail), BorderLayout.CENTER);
        Object[] options = { "Run", "Reject" };
        int choice = JOptionPane.showOptionDialog(this, panel, "Approve: " + pending.path("title").asText(), JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        boolean approved = choice == 0;
        statusLabel.setText(approved ? "Running action…" : "Claude continues…");
        background(() -> ClaudeApi.confirm(id, actionId, approved), r -> statusLabel.setText("Claude continues…"), error -> add("<div class=\"error\">Approval failed: " + Markdown.escape(error) + "</div>"));
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
        panel.add(new JLabel("<html>This is exactly what will be sent to Claude (Anthropic) from server <b>" + Markdown.escape(String.valueOf(PlatformUI.SERVER_NAME))
                + "</b>, after masking.<br>Edit it to remove anything Claude should not see; edited text is masked again before sending.</html>"), BorderLayout.NORTH);
        panel.add(new JScrollPane(text), BorderLayout.CENTER);
        panel.add(new JLabel(original.length() + " characters. Closing this dialog sends nothing."), BorderLayout.SOUTH);
        Object[] options = { "Send to Claude", "Don't send" };
        int choice = JOptionPane.showOptionDialog(this, panel, "Review before sending: " + review.path("title").asText(), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        boolean approved = choice == 0;
        String edited = text.getText();
        statusLabel.setText("Claude continues…");
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
        transcript.setText("<html><body>" + String.join("", entries) + "</body></html>");
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
