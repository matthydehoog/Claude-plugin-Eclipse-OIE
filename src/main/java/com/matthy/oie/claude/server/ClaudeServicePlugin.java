/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import static com.matthy.oie.claude.shared.ClaudeServletInterface.PERMISSION_ACTIONS;
import static com.matthy.oie.claude.shared.ClaudeServletInterface.PERMISSION_SETTINGS;
import static com.matthy.oie.claude.shared.ClaudeServletInterface.PERMISSION_USE;
import static com.matthy.oie.claude.shared.ClaudeServletInterface.PLUGIN_POINT;

import java.util.Properties;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;

import com.matthy.oie.claude.shared.ClaudeServletInterface;
import com.mirth.commons.encryption.Encryptor;
import com.mirth.connect.client.core.api.util.OperationUtil;
import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.plugins.ServicePlugin;
import com.mirth.connect.server.controllers.ControllerFactory;

/** Server side of the Claude Assistant: owns the settings and the {@link AssistantService}. */
public class ClaudeServicePlugin implements ServicePlugin {

    private static final Logger logger = LogManager.getLogger(ClaudeServicePlugin.class);

    private volatile AssistantService service;

    @Override
    public String getPluginPointName() {
        return PLUGIN_POINT;
    }

    @Override
    public void init(Properties properties) {
        // OIE's root logger is at ERROR; raise only this plugin to INFO so the per-call usage
        // lines (tokens, cache reads/writes) show up in Dashboard > Server Log.
        Configurator.setLevel("com.matthy.oie.claude", Level.INFO);
        service = new AssistantService(load(stored(properties)));
    }

    /**
     * OIE hands init() the properties stored under the plugin's display name (plugin.xml),
     * which is "AI Assistant" since 1.3.0. The settings themselves are saved under the plugin
     * point "Claude Assistant", so read them from there; otherwise every restart or reinstall
     * would start with the defaults.
     */
    private static Properties stored(Properties given) {
        try {
            Properties saved = ControllerFactory.getFactory().createExtensionController().getPluginProperties(PLUGIN_POINT);
            if (saved != null && !saved.isEmpty()) {
                return saved;
            }
        } catch (Exception e) {
            logger.warn("AI Assistant: could not read the saved settings", e);
        }
        return given;
    }

    @Override
    public void update(Properties properties) {
        service.configure(load(properties));
    }

    @Override
    public void start() {}

    @Override
    public void stop() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Override
    public Properties getDefaultProperties() {
        return Settings.defaults();
    }

    @Override
    public ExtensionPermission[] getExtensionPermissions() {
        return new ExtensionPermission[] {
                new ExtensionPermission(PLUGIN_POINT, PERMISSION_USE, "Chat with the AI Assistant about channels, messages and server status (read only).", OperationUtil.getOperationNamesForPermission(PERMISSION_USE, ClaudeServletInterface.class), ClaudeServletInterface.USE_TASKS),
                new ExtensionPermission(PLUGIN_POINT, PERMISSION_ACTIONS, "Approve actions the AI Assistant proposes: deploy, start/stop, reprocess, send messages, change scripts.", OperationUtil.getOperationNamesForPermission(PERMISSION_ACTIONS, ClaudeServletInterface.class), new String[] {}),
                new ExtensionPermission(PLUGIN_POINT, PERMISSION_SETTINGS, "Change the Anthropic API key, model and masking settings.", OperationUtil.getOperationNamesForPermission(PERMISSION_SETTINGS, ClaudeServletInterface.class), ClaudeServletInterface.SETTINGS_TASKS) };
    }

    AssistantService service() {
        return service;
    }

    /** Stores new settings as extension properties and applies them. */
    void save(Settings settings) throws Exception {
        ControllerFactory.getFactory().createExtensionController().setPluginProperties(PLUGIN_POINT, settings.toProperties(encryptor()));
        service.configure(settings);
    }

    private Settings load(Properties properties) {
        try {
            return Settings.fromProperties(properties, encryptor());
        } catch (Exception e) {
            logger.error("AI Assistant: could not read settings, starting without API key", e);
            return Settings.fromProperties(Settings.defaults(), null);
        }
    }

    private static Encryptor encryptor() {
        return ControllerFactory.getFactory().createConfigurationController().getEncryptor();
    }
}
