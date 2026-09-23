package com.matthy.oie.claude.server;

import static com.matthy.oie.claude.shared.ClaudeServletInterface.PERMISSION_ACTIONS;
import static com.matthy.oie.claude.shared.ClaudeServletInterface.PERMISSION_SETTINGS;
import static com.matthy.oie.claude.shared.ClaudeServletInterface.PERMISSION_USE;
import static com.matthy.oie.claude.shared.ClaudeServletInterface.PLUGIN_POINT;

import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
        service = new AssistantService(load(properties));
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
                new ExtensionPermission(PLUGIN_POINT, PERMISSION_USE, "Chat with Claude about channels, messages and server status (read only).", OperationUtil.getOperationNamesForPermission(PERMISSION_USE, ClaudeServletInterface.class), new String[] {}),
                new ExtensionPermission(PLUGIN_POINT, PERMISSION_ACTIONS, "Approve actions Claude proposes: deploy, start/stop, reprocess, send messages, change scripts.", OperationUtil.getOperationNamesForPermission(PERMISSION_ACTIONS, ClaudeServletInterface.class), new String[] {}),
                new ExtensionPermission(PLUGIN_POINT, PERMISSION_SETTINGS, "Change the Anthropic API key, model and masking settings.", OperationUtil.getOperationNamesForPermission(PERMISSION_SETTINGS, ClaudeServletInterface.class), new String[] {}) };
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
            logger.error("Claude Assistant: could not read settings, starting without API key", e);
            return Settings.fromProperties(Settings.defaults(), null);
        }
    }

    private static Encryptor encryptor() {
        return ControllerFactory.getFactory().createConfigurationController().getEncryptor();
    }
}
