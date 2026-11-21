/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.client;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ImageIcon;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import org.jdesktop.swingx.JXTaskPane;

import com.matthy.oie.claude.shared.ClaudeServletInterface;
import com.mirth.connect.client.ui.AuthorizationControllerFactory;
import com.mirth.connect.client.ui.Frame;
import com.mirth.connect.client.ui.PlatformUI;
import com.mirth.connect.client.ui.browsers.message.MessageBrowser;
import com.mirth.connect.client.ui.codetemplate.CodeTemplatePanel;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.DashboardStatus;
import com.mirth.connect.model.codetemplates.CodeTemplate;
import com.mirth.connect.model.codetemplates.CodeTemplateLibrary;
import com.mirth.connect.plugins.ClientPlugin;

/**
 * Adds "Claude" tasks to the Administrator: a general one under Other, and context tasks on the
 * dashboard, in the message browser and in the channel editor. All of them open the chat window.
 */
public class ClaudeClientPlugin extends ClientPlugin {

    private ChatDialog dialog;

    public ClaudeClientPlugin(String name) {
        super(name);
    }

    @Override
    public String getPluginPointName() {
        return ClaudeServletInterface.PLUGIN_POINT;
    }

    @Override
    public void start() {
        parent = PlatformUI.MIRTH_FRAME;
        ImageIcon icon = new ImageIcon(Frame.class.getResource("images/help.png"));
        // Frame.addTask does not ask the AuthorizationController, so tasks the user's role does not
        // grant ("Use AI Assistant", see ClaudeServletInterface.USE_TASKS) are left out here.
        addTask("claudeOpen", "AI Assistant", "Ask the AI Assistant a question about this OIE server.", icon, parent.otherPane, null);
        // The Dashboard hides every task after its own ones whenever the selection changes
        // (DashboardPanel.updatePopupMenu), so these two show themselves again.
        keepVisible(addTask("claudeDashboard", "Ask AI Assistant", "Let the AI Assistant analyze the selected channel(s).", icon, parent.dashboardTasks, parent.dashboardPopupMenu), parent.dashboardTasks, parent.dashboardPopupMenu);
        keepVisible(addTask("claudeQueues", "Check queues and storage", "Let the AI Assistant look for stuck queues and channels whose stored messages keep growing.", icon, parent.dashboardTasks, parent.dashboardPopupMenu), parent.dashboardTasks, parent.dashboardPopupMenu);
        addTask("claudeMessage", "Ask AI Assistant", "Let the AI Assistant find out what happened to the selected message.", icon, parent.messageTasks, parent.messagePopupMenu);
        addTask("claudeChannelEdit", "Ask AI Assistant", "Let the AI Assistant explain or improve this channel and its scripts.", icon, parent.channelEditTasks, parent.channelEditPopupMenu);
        addTask("claudeDocumentChannel", "Document channel", "Let the AI Assistant write documentation for this channel (Markdown; save it with Save answer).", icon, parent.channelEditTasks, parent.channelEditPopupMenu);

        addTask("claudeGlobalScripts", "Ask AI Assistant", "Let the AI Assistant explain or improve the global scripts.", icon, parent.globalScriptsTasks, parent.globalScriptsPopupMenu);

        // The code template panel has its own hook; the task shows only when one template is selected.
        if (parent.codeTemplatePanel != null && allowed("codeTemplate", "claudeCodeTemplate")) {
            AbstractAction askAboutTemplate = new AbstractAction("Ask AI Assistant", icon) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    claudeCodeTemplate();
                }
            };
            askAboutTemplate.putValue(Action.SHORT_DESCRIPTION, "Let the AI Assistant explain or improve the selected code template.");
            parent.codeTemplatePanel.addAction(askAboutTemplate, Collections.singleton(CodeTemplatePanel.OPTION_ONLY_SINGLE_CODE_TEMPLATES), "claudeCodeTemplate");
        }
    }

    @Override
    public void stop() {
        reset();
    }

    @Override
    public void reset() {
        // A new login may be another user; start with a fresh window.
        if (dialog != null) {
            dialog.dispose();
            dialog = null;
        }
    }

    // ------------------------------------------------------------------ task callbacks (called by name)

    public void claudeOpen() {
        dialog().open(null, null);
    }

    public void claudeDashboard() {
        List<DashboardStatus> selected = parent.dashboardPanel == null ? null : parent.dashboardPanel.getSelectedStatuses();
        if (selected == null || selected.isEmpty()) {
            dialog().open(null, "Which channels have errors, and what is the cause?");
            return;
        }
        List<String> parts = new ArrayList<>();
        for (DashboardStatus s : selected) {
            String part = "'" + s.getName() + "' (" + s.getChannelId() + ", " + s.getState() + ")";
            if (s.getMetaDataId() != null && s.getMetaDataId() > 0) {
                part = "connector " + s.getMetaDataId() + " " + part;
            }
            parts.add(part);
        }
        dialog().open("Selected in the dashboard: " + String.join(", ", parts), "Analyze the status and errors of " + (selected.size() == 1 ? "this channel." : "these channels."));
    }

    public void claudeQueues() {
        dialog().open(null, QUEUES_SUGGESTION);
    }

    /** Same text as QUEUES_SUGGESTION in webadmin/web/plugin.js. */
    static final String QUEUES_SUGGESTION = "Check the queues and message storage: which destinations are stuck, and which channels need pruning?";

    public void claudeMessage() {
        MessageBrowser browser = parent.activeBrowser != null ? parent.activeBrowser : parent.messageBrowser;
        if (browser == null || browser.getSelectedMessageId() == null) {
            parent.alertInformation(parent, "Select a message first.");
            return;
        }
        String channelId = browser.getSelectedMessageChannelId() != null ? browser.getSelectedMessageChannelId() : browser.getChannelId();
        String context = "Selected message in the message browser: channel ID " + channelId + ", message ID " + browser.getSelectedMessageId();
        if (browser.getSelectedMetaDataId() != null) {
            context += ", connector (metaDataId) " + browser.getSelectedMetaDataId();
        }
        dialog().open(context, "What happened to this message? If it failed: why, and how do I fix it?");
    }

    public void claudeChannelEdit() {
        Channel channel = parent.channelEditPanel == null ? null : parent.channelEditPanel.currentChannel;
        if (channel == null) {
            claudeOpen();
            return;
        }
        String context = "Channel open in the editor: '" + channel.getName() + "' (" + channel.getId() + ", revision " + channel.getRevision() + ")."
                + " The AI Assistant sees the saved version; unsaved changes in the editor are not visible.";
        dialog().open(context, "Explain what this channel does and point out weak spots in its scripts.");
    }

    public void claudeDocumentChannel() {
        Channel channel = parent.channelEditPanel == null ? null : parent.channelEditPanel.currentChannel;
        if (channel == null) {
            claudeOpen();
            return;
        }
        String context = "Channel open in the editor: '" + channel.getName() + "' (" + channel.getId() + ", revision " + channel.getRevision() + ")."
                + " The AI Assistant sees the saved version; unsaved changes in the editor are not visible.";
        dialog().open(context, "Write documentation for this channel as Markdown: purpose, source, filter, destinations, field mappings, scripts and code templates, Configuration Map keys and open points.");
    }

    public void claudeGlobalScripts() {
        dialog().open("Global Scripts (deploy, undeploy, preprocessor and postprocessor). The AI Assistant sees the saved version; unsaved changes in the Global Scripts editor are not visible.",
                "Explain what the global scripts do and point out weak spots.");
    }

    public void claudeCodeTemplate() {
        CodeTemplatePanel panel = parent.codeTemplatePanel;
        String id = panel == null ? null : panel.getCurrentSelectedId();
        CodeTemplate template = id == null ? null : panel.getCachedCodeTemplates().get(id);
        if (template == null) {
            parent.alertInformation(parent, "Select a code template first.");
            return;
        }
        String library = null;
        for (CodeTemplateLibrary lib : panel.getCachedCodeTemplateLibraries().values()) {
            if (lib.getCodeTemplates() != null) {
                for (CodeTemplate t : lib.getCodeTemplates()) {
                    if (id.equals(t.getId())) {
                        library = lib.getName();
                    }
                }
            }
        }
        String context = "Selected code template: '" + template.getName() + "' (" + id + ")" + (library == null ? "" : " in library '" + library + "'")
                + ". The AI Assistant sees the saved version; unsaved changes in the code template editor are not visible.";
        dialog().open(context, "Explain what this code template does, which channels use it, and point out weak spots.");
    }

    /** Adds the task when the user's role allows it; returns its index in the pane and menu, or -1. */
    private int addTask(String callback, String name, String toolTip, ImageIcon icon, JXTaskPane pane, JPopupMenu menu) {
        if (allowed(pane.getName(), callback)) {
            return parent.addTask(callback, name, toolTip, "", icon, pane, menu, this);
        }
        return -1;
    }

    /** Shows the task at this index in the pane and its menu again whenever OIE hides it. */
    static void keepVisible(int index, JXTaskPane pane, JPopupMenu menu) {
        if (index < 0) {
            return;
        }
        if (index < pane.getContentPane().getComponentCount()) {
            keepVisible(pane.getContentPane().getComponent(index));
        }
        if (menu != null && index < menu.getComponentCount()) {
            keepVisible(menu.getComponent(index));
        }
    }

    static void keepVisible(Component component) {
        component.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentHidden(ComponentEvent e) {
                SwingUtilities.invokeLater(() -> component.setVisible(true));
            }
        });
    }

    private static boolean allowed(String taskGroup, String task) {
        return AuthorizationControllerFactory.getAuthorizationController().checkTask(taskGroup, task);
    }

    private ChatDialog dialog() {
        if (dialog == null) {
            dialog = new ChatDialog(parent);
        }
        return dialog;
    }
}
