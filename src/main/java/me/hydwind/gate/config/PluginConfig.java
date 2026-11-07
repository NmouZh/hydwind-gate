package me.hydwind.gate.config;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 插件配置快照。由 {@code config.yml}（SnakeYAML）读取，热重载时整体替换。
 */
public record PluginConfig(
        String websiteBaseUrl,
        String pluginToken,
        int connectTimeoutMillis,
        int readTimeoutMillis,
        String healthPath,
        Duration loginTimeout,
        Duration reconnectGrace,
        Duration promptInterval,
        RestartSettings restart,
        String adminPermission,
        String limboWorld,
        String limboSpawn,
        String mainServer
) {

    /** 环境变量优先于配置文件，便于部署时不把令牌写进仓库。 */
    public static final String TOKEN_ENV = "HYDWIND_PLUGIN_TOKEN";
    public static final String DEFAULT_HEALTH_PATH = "/api/health";
    public static final String DEFAULT_ADMIN_PERMISSION = "hydwind.admin";
    public static final Duration DEFAULT_LOGIN_TIMEOUT = Duration.ofMinutes(5);
    public static final Duration DEFAULT_RECONNECT_GRACE = Duration.ofMinutes(5);
    public static final Duration DEFAULT_PROMPT_INTERVAL = Duration.ofSeconds(10);
    public static final String DEFAULT_LIMBO_WORLD = "hydwind_limbo";
    public static final String DEFAULT_LIMBO_SPAWN = "0, 64, 0";
    public static final String DEFAULT_MAIN_SERVER = "main";

    /** 兼容测试与最小配置的四参数构造器，其余字段使用默认值。 */
    public PluginConfig(String websiteBaseUrl,
                        String pluginToken,
                        int connectTimeoutMillis,
                        int readTimeoutMillis) {
        this(websiteBaseUrl, pluginToken, connectTimeoutMillis, readTimeoutMillis,
                DEFAULT_HEALTH_PATH, DEFAULT_LOGIN_TIMEOUT, DEFAULT_RECONNECT_GRACE,
                DEFAULT_PROMPT_INTERVAL, RestartSettings.DEFAULT, DEFAULT_ADMIN_PERMISSION,
                DEFAULT_LIMBO_WORLD, DEFAULT_LIMBO_SPAWN, DEFAULT_MAIN_SERVER);
    }

    public PluginConfig {
        Objects.requireNonNull(websiteBaseUrl, "websiteBaseUrl");
        Objects.requireNonNull(pluginToken, "pluginToken");
        Objects.requireNonNull(healthPath, "healthPath");
        Objects.requireNonNull(loginTimeout, "loginTimeout");
        Objects.requireNonNull(reconnectGrace, "reconnectGrace");
        Objects.requireNonNull(promptInterval, "promptInterval");
        Objects.requireNonNull(restart, "restart");
        Objects.requireNonNull(adminPermission, "adminPermission");
        Objects.requireNonNull(limboWorld, "limboWorld");
        Objects.requireNonNull(limboSpawn, "limboSpawn");
        Objects.requireNonNull(mainServer, "mainServer");
        if (healthPath.isBlank()) {
            throw new IllegalArgumentException("healthPath must not be blank");
        }
        if (adminPermission.isBlank()) {
            throw new IllegalArgumentException("adminPermission must not be blank");
        }
        if (loginTimeout.isNegative() || loginTimeout.isZero()) {
            throw new IllegalArgumentException("loginTimeout must be positive");
        }
        if (reconnectGrace.isNegative() || reconnectGrace.isZero()) {
            throw new IllegalArgumentException("reconnectGrace must be positive");
        }
        if (promptInterval.isNegative() || promptInterval.isZero()) {
            throw new IllegalArgumentException("promptInterval must be positive");
        }
    }

    public static PluginConfig from(Map<String, Object> root) {
        return from(root, System.getenv(TOKEN_ENV));
    }

    public static PluginConfig from(Map<String, Object> root, String environmentToken) {
        Objects.requireNonNull(root, "root");

        Map<String, Object> website = YamlMaps.section(root, "website");
        Map<String, Object> auth = YamlMaps.section(root, "auth");
        Map<String, Object> limbo = YamlMaps.section(root, "limbo");
        Map<String, Object> server = YamlMaps.section(root, "server");
        Map<String, Object> restartSection = YamlMaps.section(root, "restart");
        Map<String, Object> admin = YamlMaps.section(root, "admin");

        String configToken = YamlMaps.rawString(website, "token", "").trim();
        String token = environmentToken != null && !environmentToken.isBlank()
                ? environmentToken.trim()
                : configToken;

        RestartSettings defaults = RestartSettings.DEFAULT;
        RestartSettings restart = new RestartSettings(
                java.nio.file.Path.of(YamlMaps.text(restartSection, "request-path",
                        defaults.requestFile().toString())),
                Duration.ofSeconds(Math.max(1L, YamlMaps.longValue(restartSection,
                        "cooldown-seconds", defaults.cooldown().toSeconds()))),
                Duration.ofSeconds(Math.max(1L, YamlMaps.longValue(restartSection,
                        "window-seconds", defaults.window().toSeconds()))),
                Math.max(0, YamlMaps.intValue(restartSection, "max-per-hour",
                        YamlMaps.intValue(restartSection, "max-per-window", defaults.maxPerWindow())))
        );

        return new PluginConfig(
                YamlMaps.rawString(website, "base-url", "").trim(),
                token,
                YamlMaps.intValue(website, "connect-timeout-millis", 3000),
                YamlMaps.intValue(website, "read-timeout-millis", 5000),
                YamlMaps.text(website, "health-path", DEFAULT_HEALTH_PATH),
                Duration.ofSeconds(Math.max(1L, YamlMaps.longValue(auth,
                        "login-timeout-seconds", DEFAULT_LOGIN_TIMEOUT.toSeconds()))),
                Duration.ofSeconds(Math.max(1L, YamlMaps.longValue(auth,
                        "session-grace-seconds", DEFAULT_RECONNECT_GRACE.toSeconds()))),
                Duration.ofSeconds(Math.max(1L, YamlMaps.longValue(auth,
                        "prompt-interval-seconds", DEFAULT_PROMPT_INTERVAL.toSeconds()))),
                restart,
                YamlMaps.text(admin, "permission", DEFAULT_ADMIN_PERMISSION),
                YamlMaps.text(limbo, "world", DEFAULT_LIMBO_WORLD),
                YamlMaps.text(limbo, "spawn", DEFAULT_LIMBO_SPAWN),
                YamlMaps.text(server, "main", DEFAULT_MAIN_SERVER)
        );
    }
}