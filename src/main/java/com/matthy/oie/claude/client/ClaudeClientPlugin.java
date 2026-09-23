package com.matthy.oie.claude.client;

import java.util.ArrayList;
import java.util.List;

import javax.swing.ImageIcon;

import com.matthy.oie.claude.shared.ClaudeServletInterface;
import com.mirth.connect.client.ui.Frame;
import com.mirth.connect.client.ui.PlatformUI;
import com.mirth.connect.client.ui.browsers.message.MessageBrowser;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.DashboardStatus;
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
        parent.addTask("claudeOpen", "Claude Assistant", "Ask Claude a question about this OIE server.", "", icon, parent.otherPane, null, this);
        parent.addTask("claudeDashboard", "Ask Claude", "Let Claude analyze the selected channel(s).", "", icon, parent.dashboardTasks, parent.dashboardPopupMenu, this);
        parent.addTask("claudeMessage", "Ask Claude", "Let Claude find out what happened to the selected message.", "", icon, parent.messageTasks, parent.messagePopupMenu, this);
        parent.addTask("claudeChannelEdit", "Ask Claude", "Let Claude explain or improve this channel and its scripts.", "", icon, parent.channelEditTasks, parent.channelEditPopupMenu, this);
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
                + " Claude sees the saved version; unsaved changes in the editor are not visible.";
        dialog().open(context, "Explain what this channel does and point out weak spots in its scripts.");
    }

    private ChatDialog dialog() {
        if (dialog == null) {
            dialog = new ChatDialog(parent);
        }
        return dialog;
    }
}
