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
        SpawnPoint spawn,
        String healthPath,
        RestartSettings restart,
        String adminPermission
) {

    /** 环境变量优先于配置文件，便于部署时不把令牌写进仓库。 */
    public static final String TOKEN_ENV = "HYDWIND_PLUGIN_TOKEN";
    public static final String DEFAULT_HEALTH_PATH = "/api/health";
    public static final String DEFAULT_ADMIN_PERMISSION = "hydwind.admin";

    /** 兼容 G3 时期的四参数构造器，使用默认的登录超时、宽限与出生点。 */
    public PluginConfig(String websiteBaseUrl,
                        String pluginToken,
                        int connectTimeoutMillis,
                        int readTimeoutMillis) {
        this(websiteBaseUrl, pluginToken, connectTimeoutMillis, readTimeoutMillis,
                Duration.ofMinutes(5), Duration.ofMinutes(5), SpawnPoint.DEFAULT);
    }

    /** 兼容 G5 时期的七参数构造器。 */
    public PluginConfig(String websiteBaseUrl,
                        String pluginToken,
                        int connectTimeoutMillis,
                        int readTimeoutMillis,
                        Duration loginTimeout,
                        Duration reconnectGrace,
                        SpawnPoint spawn) {
        this(websiteBaseUrl, pluginToken, connectTimeoutMillis, readTimeoutMillis,
                loginTimeout, reconnectGrace, spawn,
                DEFAULT_HEALTH_PATH, RestartSettings.DEFAULT, DEFAULT_ADMIN_PERMISSION);
    }

    public PluginConfig {
        Objects.requireNonNull(websiteBaseUrl, "websiteBaseUrl");
        Objects.requireNonNull(pluginToken, "pluginToken");
        Objects.requireNonNull(loginTimeout, "loginTimeout");
        Objects.requireNonNull(reconnectGrace, "reconnectGrace");
        Objects.requireNonNull(spawn, "spawn");
        Objects.requireNonNull(healthPath, "healthPath");
        Objects.requireNonNull(restart, "restart");
        Objects.requireNonNull(adminPermission, "adminPermission");
        if (healthPath.isBlank()) {
            throw new IllegalArgumentException("healthPath must not be blank");
        }
        if (adminPermission.isBlank()) {
            throw new IllegalArgumentException("adminPermission must not be blank");
        }
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

        RestartSettings defaults = RestartSettings.DEFAULT;
        RestartSettings restart = new RestartSettings(
                java.nio.file.Path.of(config.getString("restart.request-file",
                        defaults.requestFile().toString())),
                Duration.ofSeconds(Math.max(1L, config.getLong("restart.cooldown-seconds",
                        defaults.cooldown().toSeconds()))),
                Duration.ofSeconds(Math.max(1L, config.getLong("restart.window-seconds",
                        defaults.window().toSeconds()))),
                Math.max(0, config.getInt("restart.max-per-window", defaults.maxPerWindow()))
        );

        return new PluginConfig(
                config.getString("website.base-url", "").trim(),
                token,
                config.getInt("website.connect-timeout-millis", 3000),
                config.getInt("website.read-timeout-millis", 5000),
                Duration.ofSeconds(Math.max(1L, config.getLong("login.timeout-seconds", 300L))),
                Duration.ofSeconds(Math.max(1L, config.getLong("login.reconnect-grace-seconds", 300L))),
                spawn,
                config.getString("website.health-path", DEFAULT_HEALTH_PATH).trim(),
                restart,
                config.getString("admin.permission", DEFAULT_ADMIN_PERMISSION).trim()
        );
    }
}