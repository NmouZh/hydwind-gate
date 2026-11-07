package me.hydwind.gate.config;

import java.time.Duration;
import java.util.Objects;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * 插件配置快照。由 {@code config.yml} 读取，热重载时整体替换。
 */
public record PluginConfig(
        String websiteBaseUrl,
        String pluginToken,
        int connectTimeoutMillis,
        int readTimeoutMillis,
        Duration loginTimeout,
        Duration reconnectGrace,
        SpawnPoint spawn
) {

    /** 环境变量优先于配置文件，便于部署时不把令牌写进仓库。 */
    public static final String TOKEN_ENV = "HYDWIND_PLUGIN_TOKEN";

    /** 兼容 G3 时期的四参数构造器，使用默认的登录超时、宽限与出生点。 */
    public PluginConfig(String websiteBaseUrl,
                        String pluginToken,
                        int connectTimeoutMillis,
                        int readTimeoutMillis) {
        this(websiteBaseUrl, pluginToken, connectTimeoutMillis, readTimeoutMillis,
                Duration.ofMinutes(5), Duration.ofMinutes(5), SpawnPoint.DEFAULT);
    }

    public PluginConfig {
        Objects.requireNonNull(websiteBaseUrl, "websiteBaseUrl");
        Objects.requireNonNull(pluginToken, "pluginToken");
        Objects.requireNonNull(loginTimeout, "loginTimeout");
        Objects.requireNonNull(reconnectGrace, "reconnectGrace");
        Objects.requireNonNull(spawn, "spawn");
    }

    public static PluginConfig from(FileConfiguration config) {
        return from(config, System.getenv(TOKEN_ENV));
    }

    public static PluginConfig from(FileConfiguration config, String environmentToken) {
        String configToken = config.getString("website.token",
                config.getString("website.plugin-token", ""));
        String token = environmentToken != null && !environmentToken.isBlank()
                ? environmentToken.trim()
                : configToken.trim();

        SpawnPoint spawn = new SpawnPoint(
                config.getString("spawn.world", SpawnPoint.DEFAULT.world()),
                config.getDouble("spawn.x", SpawnPoint.DEFAULT.x()),
                config.getDouble("spawn.y", SpawnPoint.DEFAULT.y()),
                config.getDouble("spawn.z", SpawnPoint.DEFAULT.z()),
                (float) config.getDouble("spawn.yaw", SpawnPoint.DEFAULT.yaw()),
                (float) config.getDouble("spawn.pitch", SpawnPoint.DEFAULT.pitch())
        );

        return new PluginConfig(
                config.getString("website.base-url", "").trim(),
                token,
                config.getInt("website.connect-timeout-millis", 3000),
                config.getInt("website.read-timeout-millis", 5000),
                Duration.ofSeconds(Math.max(1L, config.getLong("login.timeout-seconds", 300L))),
                Duration.ofSeconds(Math.max(1L, config.getLong("login.reconnect-grace-seconds", 300L))),
                spawn
        );
    }
}