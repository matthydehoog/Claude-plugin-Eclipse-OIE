/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.client;

import com.matthy.oie.claude.shared.ClaudeServletInterface;
import com.mirth.connect.client.ui.AbstractSettingsPanel;
import com.mirth.connect.plugins.SettingsPanelPlugin;

/** Adds the "Claude Assistant" tab under Settings. */
public class ClaudeSettingsPlugin extends SettingsPanelPlugin {

    private final ClaudeSettingsPanel panel;

    public ClaudeSettingsPlugin(String name) {
        super(name);
        panel = new ClaudeSettingsPanel("Claude Assistant");
    }

    @Override
    public AbstractSettingsPanel getSettingsPanel() {
        return panel;
    }

    @Override
    public String getPluginPointName() {
        return ClaudeServletInterface.PLUGIN_POINT;
    }

    @Override
    public void start() {}

    @Override
    public void stop() {}

    @Override
    public void reset() {}
}
