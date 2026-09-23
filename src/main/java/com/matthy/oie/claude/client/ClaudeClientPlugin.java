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
        parent.addTask("claudeOpen", "Claude-assistent", "Stel Claude een vraag over deze OIE-server.", "", icon, parent.otherPane, null, this);
        parent.addTask("claudeDashboard", "Vraag Claude", "Laat Claude de geselecteerde channel(s) analyseren.", "", icon, parent.dashboardTasks, parent.dashboardPopupMenu, this);
        parent.addTask("claudeMessage", "Vraag Claude", "Laat Claude uitzoeken wat er met het geselecteerde bericht gebeurde.", "", icon, parent.messageTasks, parent.messagePopupMenu, this);
        parent.addTask("claudeChannelEdit", "Vraag Claude", "Laat Claude deze channel en zijn scripts uitleggen of verbeteren.", "", icon, parent.channelEditTasks, parent.channelEditPopupMenu, this);
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
            dialog().open(null, "Welke channels hebben fouten, en wat is de oorzaak?");
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
        dialog().open("Geselecteerd in het dashboard: " + String.join(", ", parts), "Analyseer de status en de fouten van " + (selected.size() == 1 ? "deze channel." : "deze channels."));
    }

    public void claudeMessage() {
        MessageBrowser browser = parent.activeBrowser != null ? parent.activeBrowser : parent.messageBrowser;
        if (browser == null || browser.getSelectedMessageId() == null) {
            parent.alertInformation(parent, "Selecteer eerst een bericht.");
            return;
        }
        String channelId = browser.getSelectedMessageChannelId() != null ? browser.getSelectedMessageChannelId() : browser.getChannelId();
        String context = "Geselecteerd bericht in de message browser: channel-ID " + channelId + ", bericht-ID " + browser.getSelectedMessageId();
        if (browser.getSelectedMetaDataId() != null) {
            context += ", connector (metaDataId) " + browser.getSelectedMetaDataId();
        }
        dialog().open(context, "Wat is er met dit bericht gebeurd? Als het mislukt is: waarom, en hoe los ik het op?");
    }

    public void claudeChannelEdit() {
        Channel channel = parent.channelEditPanel == null ? null : parent.channelEditPanel.currentChannel;
        if (channel == null) {
            claudeOpen();
            return;
        }
        String context = "Channel open in de editor: '" + channel.getName() + "' (" + channel.getId() + ", revisie " + channel.getRevision() + ")."
                + " Claude ziet de opgeslagen versie; niet-opgeslagen wijzigingen in de editor zijn niet zichtbaar.";
        dialog().open(context, "Leg uit wat deze channel doet en wijs zwakke plekken in de scripts aan.");
    }

    private ChatDialog dialog() {
        if (dialog == null) {
            dialog = new ChatDialog(parent);
        }
        return dialog;
    }
}
