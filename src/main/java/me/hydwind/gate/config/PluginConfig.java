package me.hydwind.gate.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 插件配置快照。由 {@code config.yml} 读取，热重载时整体替换。
 */
public record PluginConfig(
        String websiteBaseUrl,
        String pluginToken,
        int connectTimeoutMillis,
        int readTimeoutMillis
) {

    public static PluginConfig from(FileConfiguration config) {
        return new PluginConfig(
                config.getString("website.base-url", "").trim(),
                config.getString("website.plugin-token", "").trim(),
                config.getInt("website.connect-timeout-millis", 3000),
                config.getInt("website.read-timeout-millis", 5000)
        );
    }
}
