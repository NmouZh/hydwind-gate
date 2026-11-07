package me.hydwind.gate;

import me.hydwind.gate.config.PluginConfig;
import me.hydwind.gate.ui.Messages;
import org.bukkit.plugin.java.JavaPlugin;

public final class HydwindGatePlugin extends JavaPlugin {

    private PluginConfig pluginConfig;
    private Messages messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPluginConfig();
        getLogger().info("HydwindGate enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("HydwindGate disabled.");
    }

    public void reloadPluginConfig() {
        reloadConfig();
        pluginConfig = PluginConfig.from(getConfig());
        messages = Messages.from(getConfig());
        getLogger().info("Configuration loaded; website base URL: " + pluginConfig.websiteBaseUrl());
    }

    public PluginConfig pluginConfig() {
        return pluginConfig;
    }

    public Messages messages() {
        return messages;
    }
}
