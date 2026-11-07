package me.hydwind.gate.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PluginConfigTest {

    /** 合法的最小配置：窄设置要求 base-url 非空，缺了它就无法加载。 */
    private static Map<String, Object> minimalRoot() {
        return Map.<String, Object>of("website", Map.<String, Object>of(
                "base-url", "http://example.test"));
    }

    @Test
    void readsWebsiteAuthLimboServerAndRestartSections() {
        Map<String, Object> root = Map.<String, Object>of(
                "website", Map.<String, Object>of(
                        "base-url", "http://example.test/",
                        "token", "file-token",
                        "connect-timeout-millis", 1234,
                        "read-timeout-millis", 5678,
                        "health-path", "health"),
                "auth", Map.<String, Object>of(
                        "login-timeout-seconds", 120,
                        "session-grace-seconds", 60,
                        "prompt-interval-seconds", 5),
                "limbo", Map.<String, Object>of(
                        "world", "limbo_x",
                        "spawn", "1, 2, 3"),
                "server", Map.<String, Object>of("main", "lobby"),
                "restart", Map.<String, Object>of(
                        "request-path", "/tmp/restart-request",
                        "cooldown-seconds", 30,
                        "window-seconds", 120,
                        "max-per-hour", 3),
                "admin", Map.<String, Object>of("permission", "custom.admin"));

        PluginConfig config = PluginConfig.from(root, null);

        // 旧扁平访问器保持向后兼容。
        assertEquals("http://example.test/", config.websiteBaseUrl());
        assertEquals("file-token", config.pluginToken());
        assertEquals(1234, config.connectTimeoutMillis());
        assertEquals(5678, config.readTimeoutMillis());
        assertEquals("health", config.healthPath());
        assertEquals(Duration.ofSeconds(120), config.loginTimeout());
        assertEquals(Duration.ofSeconds(60), config.reconnectGrace());
        assertEquals(Duration.ofSeconds(5), config.promptInterval());
        assertEquals("limbo_x", config.limboWorld());
        assertEquals("1, 2, 3", config.limboSpawn());
        assertEquals("lobby", config.mainServer());
        assertEquals("custom.admin", config.adminPermission());
        assertEquals(Path.of("/tmp/restart-request"), config.restart().requestFile());
        assertEquals(Duration.ofSeconds(30), config.restart().cooldown());
        assertEquals(Duration.ofSeconds(120), config.restart().window());
        assertEquals(3, config.restart().maxPerWindow());
    }

    @Test
    void narrowViewsExposeTheSameValuesAsTheFlatAccessors() {
        Map<String, Object> root = Map.<String, Object>of(
                "website", Map.<String, Object>of(
                        "base-url", "http://example.test/",
                        "token", "file-token",
                        "connect-timeout-millis", 1234,
                        "read-timeout-millis", 5678,
                        "health-path", "health"),
                "auth", Map.<String, Object>of(
                        "login-timeout-seconds", 120,
                        "session-grace-seconds", 60,
                        "prompt-interval-seconds", 5),
                "limbo", Map.<String, Object>of(
                        "world", "limbo_x",
                        "spawn", "1, 2, 3"),
                "server", Map.<String, Object>of("main", "lobby"),
                "restart", Map.<String, Object>of(
                        "request-path", "/tmp/restart-request",
                        "cooldown-seconds", 30,
                        "window-seconds", 120,
                        "max-per-hour", 3),
                "admin", Map.<String, Object>of("permission", "custom.admin"));

        PluginConfig config = PluginConfig.from(root, null);

        assertEquals(new WebsiteSettings(
                        "http://example.test/", "file-token", 1234, 5678, "health"),
                config.website());
        assertEquals(new AuthSettings(
                        Duration.ofSeconds(120), Duration.ofSeconds(60), Duration.ofSeconds(5)),
                config.auth());
        assertEquals(new LimboSettings("limbo_x", "1, 2, 3"), config.limbo());
        assertEquals(new AdminSettings("custom.admin"), config.admin());
        assertEquals(new ServerSettings("lobby"), config.server());
        assertEquals(Path.of("/tmp/restart-request"), config.restart().requestFile());
    }

    @Test
    void environmentTokenOverwritesFileTokenOnlyWhenNotBlank() {
        Map<String, Object> root = Map.<String, Object>of(
                "website", Map.<String, Object>of(
                        "base-url", "http://example.test",
                        "token", "file-token"));

        assertEquals("env-token", PluginConfig.from(root, "  env-token  ").pluginToken());
        assertEquals("env-token", PluginConfig.from(root, "  env-token  ").website().token());
        assertEquals("file-token", PluginConfig.from(root, "   ").pluginToken());
    }

    @Test
    void blankEnvironmentTokenFallsBackToFileToken() {
        Map<String, Object> root = Map.<String, Object>of(
                "website", Map.<String, Object>of(
                        "base-url", "http://example.test",
                        "token", "file-token"));

        assertEquals("file-token", PluginConfig.from(root, "").pluginToken());
        assertEquals("file-token", PluginConfig.from(root, null).pluginToken());
    }

    @Test
    void missingSectionsFallBackToDefaults() {
        PluginConfig config = PluginConfig.from(minimalRoot(), null);

        assertEquals("", config.pluginToken());
        assertEquals(PluginConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMillis());
        assertEquals(PluginConfig.DEFAULT_READ_TIMEOUT_MILLIS, config.readTimeoutMillis());
        assertEquals(PluginConfig.DEFAULT_HEALTH_PATH, config.healthPath());
        assertEquals(PluginConfig.DEFAULT_LOGIN_TIMEOUT, config.loginTimeout());
        assertEquals(PluginConfig.DEFAULT_RECONNECT_GRACE, config.reconnectGrace());
        assertEquals(PluginConfig.DEFAULT_PROMPT_INTERVAL, config.promptInterval());
        assertEquals(PluginConfig.DEFAULT_ADMIN_PERMISSION, config.adminPermission());
        assertEquals(PluginConfig.DEFAULT_LIMBO_WORLD, config.limboWorld());
        assertEquals(PluginConfig.DEFAULT_LIMBO_SPAWN, config.limboSpawn());
        assertEquals(PluginConfig.DEFAULT_MAIN_SERVER, config.mainServer());
        assertEquals(RestartSettings.DEFAULT, config.restart());
    }

    @Test
    void numericStringsAndLegacyMaxPerWindowAliasAreAccepted() {
        Map<String, Object> root = Map.<String, Object>of(
                "website", Map.<String, Object>of(
                        "base-url", "http://example.test",
                        "connect-timeout-millis", "1500",
                        "read-timeout-millis", "2500"),
                "restart", Map.<String, Object>of(
                        "cooldown-seconds", "45",
                        "max-per-window", "4"));

        PluginConfig config = PluginConfig.from(root, null);

        assertEquals(1500, config.connectTimeoutMillis());
        assertEquals(2500, config.readTimeoutMillis());
        assertEquals(Duration.ofSeconds(45), config.restart().cooldown());
        assertEquals(4, config.restart().maxPerWindow());
    }

    @Test
    void maxPerHourWinsOverLegacyMaxPerWindowWhenBothArePresent() {
        Map<String, Object> root = Map.<String, Object>of(
                "website", Map.<String, Object>of("base-url", "http://example.test"),
                "restart", Map.<String, Object>of(
                        "max-per-hour", 6,
                        "max-per-window", 4));

        assertEquals(6, PluginConfig.from(root, null).restart().maxPerWindow());
    }

    @Test
    void unparsableNumbersFallBackToDefaults() {
        Map<String, Object> root = Map.<String, Object>of(
                "website", Map.<String, Object>of(
                        "base-url", "http://example.test",
                        "connect-timeout-millis", "nope",
                        "read-timeout-millis", "also-nope"),
                "auth", Map.<String, Object>of("prompt-interval-seconds", "abc"));

        PluginConfig config = PluginConfig.from(root, null);

        assertEquals(PluginConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMillis(),
                "无法解析的字符串按缺项处理");
        assertEquals(PluginConfig.DEFAULT_READ_TIMEOUT_MILLIS, config.readTimeoutMillis(),
                "无法解析的字符串按缺项处理");
        assertEquals(PluginConfig.DEFAULT_PROMPT_INTERVAL, config.promptInterval(),
                "无法解析的字符串按缺项处理");
    }

    @Test
    void blankOptionalTextFallsBackToDefaults() {
        Map<String, Object> root = Map.<String, Object>of(
                "website", Map.<String, Object>of(
                        "base-url", "http://example.test",
                        "health-path", "   "),
                "admin", Map.<String, Object>of("permission", " "),
                "limbo", Map.<String, Object>of("world", "", "spawn", "  "),
                "server", Map.<String, Object>of("main", ""));

        PluginConfig config = PluginConfig.from(root, null);

        assertEquals(PluginConfig.DEFAULT_HEALTH_PATH, config.healthPath());
        assertEquals(PluginConfig.DEFAULT_ADMIN_PERMISSION, config.adminPermission());
        assertEquals(PluginConfig.DEFAULT_LIMBO_WORLD, config.limboWorld());
        assertEquals(PluginConfig.DEFAULT_LIMBO_SPAWN, config.limboSpawn());
        assertEquals(PluginConfig.DEFAULT_MAIN_SERVER, config.mainServer());
    }

    // ---------------------------------------------------------------------
    // 集中校验：非法值一律抛 IllegalArgumentException，不再静默回退。
    // ---------------------------------------------------------------------

    @Test
    void blankOrMissingBaseUrlIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PluginConfig.from(Map.of(), null),
                "缺少 website.base-url 时不再允许启动");
        assertThrows(IllegalArgumentException.class, () -> PluginConfig.from(
                Map.of("website", Map.of("base-url", "   ")), null));
    }

    @Test
    void nonPositiveWebsiteTimeoutsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PluginConfig.from(
                Map.of("website", Map.of(
                        "base-url", "http://example.test",
                        "connect-timeout-millis", 0)), null));
        assertThrows(IllegalArgumentException.class, () -> PluginConfig.from(
                Map.of("website", Map.of(
                        "base-url", "http://example.test",
                        "connect-timeout-millis", -1)), null));
        assertThrows(IllegalArgumentException.class, () -> PluginConfig.from(
                Map.of("website", Map.of(
                        "base-url", "http://example.test",
                        "read-timeout-millis", 0)), null),
                "read-timeout-millis=0 以前会静默回退 3000，现在直接报错");
    }

    @Test
    void nonPositiveAuthDurationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PluginConfig.from(
                Map.of("website", Map.of("base-url", "http://example.test"),
                        "auth", Map.of("login-timeout-seconds", 0)), null));
        assertThrows(IllegalArgumentException.class, () -> PluginConfig.from(
                Map.of("website", Map.of("base-url", "http://example.test"),
                        "auth", Map.of("session-grace-seconds", -10)), null));
        assertThrows(IllegalArgumentException.class, () -> PluginConfig.from(
                Map.of("website", Map.of("base-url", "http://example.test"),
                        "auth", Map.of("prompt-interval-seconds", 0)), null));
    }

    @Test
    void nonPositiveRestartValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PluginConfig.from(
                Map.of("website", Map.of("base-url", "http://example.test"),
                        "restart", Map.of("cooldown-seconds", 0)), null));
        assertThrows(IllegalArgumentException.class, () -> PluginConfig.from(
                Map.of("website", Map.of("base-url", "http://example.test"),
                        "restart", Map.of("window-seconds", -5)), null));
        assertThrows(IllegalArgumentException.class, () -> PluginConfig.from(
                Map.of("website", Map.of("base-url", "http://example.test"),
                        "restart", Map.of("max-per-hour", -3)), null),
                "负的重启上限以前按 0 处理，现在直接报错");
    }

    @Test
    void websiteSettingsRejectInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new WebsiteSettings(" ", "token", 100, 100, "/api/health"));
        assertThrows(IllegalArgumentException.class,
                () -> new WebsiteSettings("http://example.test", "token", 0, 100, "/api/health"));
        assertThrows(IllegalArgumentException.class,
                () -> new WebsiteSettings("http://example.test", "token", 100, 0, "/api/health"));
        assertThrows(IllegalArgumentException.class,
                () -> new WebsiteSettings("http://example.test", "token", 100, 100, " "));
        assertThrows(NullPointerException.class,
                () -> new WebsiteSettings(null, "token", 100, 100, "/api/health"));
        assertThrows(NullPointerException.class,
                () -> new WebsiteSettings("http://example.test", null, 100, 100, "/api/health"));
    }

    @Test
    void authSettingsRejectNonPositiveDurations() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuthSettings(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthSettings(Duration.ofSeconds(1), Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthSettings(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthSettings(Duration.ofSeconds(-1), Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class,
                () -> new AuthSettings(null, Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    @Test
    void adminAndLimboSettingsRejectBlankText() {
        assertThrows(IllegalArgumentException.class, () -> new AdminSettings(" "));
        assertThrows(IllegalArgumentException.class, () -> new AdminSettings(""));
        assertThrows(NullPointerException.class, () -> new AdminSettings(null));
        assertThrows(IllegalArgumentException.class, () -> new LimboSettings(" ", "0, 64, 0"));
        assertThrows(NullPointerException.class, () -> new LimboSettings(null, "0, 64, 0"));
        assertThrows(NullPointerException.class, () -> new ServerSettings(null));
    }

    @Test
    void pluginConfigConstructorOnlyChecksForNulls() {
        WebsiteSettings website =
                new WebsiteSettings("http://example.test", "token", 100, 100, "/api/health");
        AuthSettings auth = new AuthSettings(
                Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofSeconds(10));
        LimboSettings limbo = new LimboSettings("hydwind_limbo", "0, 64, 0");
        AdminSettings admin = new AdminSettings("hydwind.admin");
        ServerSettings server = new ServerSettings("main");

        PluginConfig config = new PluginConfig(website, auth, limbo, admin, server,
                RestartSettings.DEFAULT);

        assertEquals(website, config.website());
        assertEquals(auth, config.auth());
        assertEquals(limbo, config.limbo());
        assertEquals(admin, config.admin());
        assertEquals(server, config.server());
        assertEquals("http://example.test", config.websiteBaseUrl(), "旧访问器委托给窄设置");

        assertThrows(NullPointerException.class,
                () -> new PluginConfig(null, auth, limbo, admin, server, RestartSettings.DEFAULT));
        assertThrows(NullPointerException.class,
                () -> new PluginConfig(website, null, limbo, admin, server, RestartSettings.DEFAULT));
        assertThrows(NullPointerException.class,
                () -> new PluginConfig(website, auth, null, admin, server, RestartSettings.DEFAULT));
        assertThrows(NullPointerException.class,
                () -> new PluginConfig(website, auth, limbo, null, server, RestartSettings.DEFAULT));
        assertThrows(NullPointerException.class,
                () -> new PluginConfig(website, auth, limbo, admin, null, RestartSettings.DEFAULT));
        assertThrows(NullPointerException.class,
                () -> new PluginConfig(website, auth, limbo, admin, server, null));
    }
}
