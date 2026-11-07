package me.hydwind.gate.config;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 插件配置快照。由 {@code config.yml}（SnakeYAML）读取，热重载时整体替换。
 *
 * <p>内部按配置段拆成五个窄设置 record；本类型保留原有的 13 个扁平访问器，
 * 它们全部委托给窄设置，便于调用方逐步迁移到 {@link #website()} / {@link #auth()} /
 * {@link #limbo()} / {@link #admin()} / {@link #server()}。</p>
 *
 * <p>取值校验集中在窄设置 record 中，本 record 的构造器只检查非 null。</p>
 *
 * @param website 网站连接与鉴权设置
 * @param auth    登录会话时限设置
 * @param limbo   LimboAPI 虚拟世界设置
 * @param admin   管理命令设置
 * @param server  转服设置
 * @param restart 受控重启设置
 */
public record PluginConfig(
        WebsiteSettings website,
        AuthSettings auth,
        LimboSettings limbo,
        AdminSettings admin,
        ServerSettings server,
        RestartSettings restart
) {

    /** 环境变量优先于配置文件，便于部署时不把令牌写进仓库。 */
    public static final String TOKEN_ENV = "HYDWIND_PLUGIN_TOKEN";
    public static final String DEFAULT_HEALTH_PATH = "/api/health";
    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 3000;
    public static final int DEFAULT_READ_TIMEOUT_MILLIS = 5000;
    public static final String DEFAULT_ADMIN_PERMISSION = "hydwind.admin";
    public static final Duration DEFAULT_LOGIN_TIMEOUT = Duration.ofMinutes(5);
    public static final Duration DEFAULT_RECONNECT_GRACE = Duration.ofMinutes(5);
    public static final Duration DEFAULT_PROMPT_INTERVAL = Duration.ofSeconds(10);
    public static final String DEFAULT_LIMBO_WORLD = "hydwind_limbo";
    public static final String DEFAULT_LIMBO_SPAWN = "0, 64, 0";
    public static final String DEFAULT_MAIN_SERVER = "main";

    public PluginConfig {
        Objects.requireNonNull(website, "website");
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(limbo, "limbo");
        Objects.requireNonNull(admin, "admin");
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(restart, "restart");
    }

    // ---------------------------------------------------------------------
    // 兼容访问器：保留旧调用点，内部委托给窄设置。
    // ---------------------------------------------------------------------

    public String websiteBaseUrl() {
        return website.baseUrl();
    }

    public String pluginToken() {
        return website.token();
    }

    public int connectTimeoutMillis() {
        return website.connectTimeoutMillis();
    }

    public int readTimeoutMillis() {
        return website.readTimeoutMillis();
    }

    public String healthPath() {
        return website.healthPath();
    }

    public Duration loginTimeout() {
        return auth.loginTimeout();
    }

    public Duration reconnectGrace() {
        return auth.reconnectGrace();
    }

    public Duration promptInterval() {
        return auth.promptInterval();
    }

    public String adminPermission() {
        return admin.permission();
    }

    public String limboWorld() {
        return limbo.world();
    }

    public String limboSpawn() {
        return limbo.spawn();
    }

    public String mainServer() {
        return server.mainServer();
    }

    // restart() 由 record 组件直接提供，无需委托。

    public static PluginConfig from(Map<String, Object> root) {
        return from(root, System.getenv(TOKEN_ENV));
    }

    /**
     * 解析配置。
     *
     * <p>校验失败一律抛出 {@link IllegalArgumentException}，不做静默回退：
     * {@code website.base-url} 为空或非法、超时非正、auth 时限非正、重启参数非法、
     * 数值字段写成无法解析的字符串，都会让本次加载失败。只有“缺项”、
     * {@code null} 或非字符串非数字类型才使用默认值；空白文本字段回退默认值。</p>
     */
    public static PluginConfig from(Map<String, Object> root, String environmentToken) {
        Objects.requireNonNull(root, "root");

        Map<String, Object> websiteSection = YamlMaps.section(root, "website");
        Map<String, Object> authSection = YamlMaps.section(root, "auth");
        Map<String, Object> limboSection = YamlMaps.section(root, "limbo");
        Map<String, Object> serverSection = YamlMaps.section(root, "server");
        Map<String, Object> restartSection = YamlMaps.section(root, "restart");
        Map<String, Object> adminSection = YamlMaps.section(root, "admin");

        String configToken = YamlMaps.rawString(websiteSection, "token", "").trim();
        String token = environmentToken != null && !environmentToken.isBlank()
                ? environmentToken.trim()
                : configToken;

        WebsiteSettings website = new WebsiteSettings(
                YamlMaps.rawString(websiteSection, "base-url", "").trim(),
                token,
                YamlMaps.intValue(websiteSection, "connect-timeout-millis",
                        DEFAULT_CONNECT_TIMEOUT_MILLIS),
                YamlMaps.intValue(websiteSection, "read-timeout-millis",
                        DEFAULT_READ_TIMEOUT_MILLIS),
                YamlMaps.text(websiteSection, "health-path", DEFAULT_HEALTH_PATH));

        AuthSettings auth = new AuthSettings(
                seconds(authSection, "login-timeout-seconds", DEFAULT_LOGIN_TIMEOUT),
                seconds(authSection, "session-grace-seconds", DEFAULT_RECONNECT_GRACE),
                seconds(authSection, "prompt-interval-seconds", DEFAULT_PROMPT_INTERVAL));

        RestartSettings restartDefaults = RestartSettings.DEFAULT;
        RestartSettings restart = new RestartSettings(
                java.nio.file.Path.of(YamlMaps.text(restartSection, "request-path",
                        restartDefaults.requestFile().toString())),
                seconds(restartSection, "cooldown-seconds", restartDefaults.cooldown()),
                seconds(restartSection, "window-seconds", restartDefaults.window()),
                maxPerWindow(restartSection, restartDefaults.maxPerWindow()));

        return new PluginConfig(
                website,
                auth,
                new LimboSettings(
                        YamlMaps.text(limboSection, "world", DEFAULT_LIMBO_WORLD),
                        YamlMaps.text(limboSection, "spawn", DEFAULT_LIMBO_SPAWN)),
                new AdminSettings(
                        YamlMaps.text(adminSection, "permission", DEFAULT_ADMIN_PERMISSION)),
                new ServerSettings(
                        YamlMaps.text(serverSection, "main", DEFAULT_MAIN_SERVER)),
                restart);
    }

    /**
     * 读取滚动窗口内的重启上限。
     *
     * <p>优先 {@code max-per-hour}，只有它缺项时才读取旧别名 {@code max-per-window}；
     * 旧别名如果存在但无法解析，不应让一个已合法配置的新键启动失败。</p>
     */
    private static int maxPerWindow(Map<String, Object> section, int fallback) {
        if (section.containsKey("max-per-hour")) {
            return YamlMaps.intValue(section, "max-per-hour", fallback);
        }
        return YamlMaps.intValue(section, "max-per-window", fallback);
    }

    /** 读取秒数并转成 {@link Duration}；缺项或无法解析时用 {@code fallback}。 */
    private static Duration seconds(Map<String, Object> section, String key, Duration fallback) {
        return Duration.ofSeconds(
                YamlMaps.longValue(section, key, fallback.toSeconds()));
    }
}
