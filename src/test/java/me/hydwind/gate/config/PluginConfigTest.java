package me.hydwind.gate.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PluginConfigTest {

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
    void environmentTokenOverwritesFileTokenOnlyWhenNotBlank() {
        Map<String, Object> root = Map.<String, Object>of(
                "website", Map.<String, Object>of("token", "file-token"));

        assertEquals("env-token", PluginConfig.from(root, "  env-token  ").pluginToken());
        assertEquals("file-token", PluginConfig.from(root, "   ").pluginToken());
    }

    @Test
    void missingSectionsFallBackToDefaults() {
        PluginConfig config = PluginConfig.from(Map.of(), null);

        assertEquals("", config.websiteBaseUrl());
        assertEquals("", config.pluginToken());
        assertEquals(PluginConfig.DEFAULT_HEALTH_PATH, config.healthPath());
        assertEquals(PluginConfig.DEFAULT_LOGIN_TIMEOUT, config.loginTimeout());
        assertEquals(PluginConfig.DEFAULT_RECONNECT_GRACE, config.reconnectGrace());
        assertEquals(PluginConfig.DEFAULT_PROMPT_INTERVAL, config.promptInterval());
        assertEquals(PluginConfig.DEFAULT_ADMIN_PERMISSION, config.adminPermission());
        assertEquals(PluginConfig.DEFAULT_LIMBO_WORLD, config.limboWorld());
        assertEquals(PluginConfig.DEFAULT_LIMBO_SPAWN, config.limboSpawn());
        assertEquals(PluginConfig.DEFAULT_MAIN_SERVER, config.mainServer());
        assertEquals(RestartSettings.DEFAULT.maxPerWindow(), config.restart().maxPerWindow());
    }

    @Test
    void numericStringsAndLegacyMaxPerWindowAliasAreAccepted() {
        Map<String, Object> root = Map.<String, Object>of(
                "website", Map.<String, Object>of(
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
    void unsafeOrUnparsableNumbersFallBackToSafeValues() {
        Map<String, Object> root = Map.<String, Object>of(
                "website", Map.<String, Object>of(
                        "connect-timeout-millis", "nope",
                        "read-timeout-millis", 0),
                "auth", Map.<String, Object>of(
                        "login-timeout-seconds", -10,
                        "session-grace-seconds", 0,
                        "prompt-interval-seconds", "abc"),
                "restart", Map.<String, Object>of(
                        "cooldown-seconds", 0,
                        "window-seconds", -5,
                        "max-per-hour", -3));

        PluginConfig config = PluginConfig.from(root, null);

        assertEquals(3000, config.connectTimeoutMillis(), "无法解析的超时应回到默认值");
        assertEquals(0, config.readTimeoutMillis(), "HTTP 客户端会把非正数换成默认值");
        assertEquals(Duration.ofSeconds(1), config.loginTimeout(), "超时至少 1 秒");
        assertEquals(Duration.ofSeconds(1), config.reconnectGrace(), "宽限至少 1 秒");
        assertEquals(PluginConfig.DEFAULT_PROMPT_INTERVAL, config.promptInterval());
        assertEquals(Duration.ofSeconds(1), config.restart().cooldown());
        assertEquals(Duration.ofSeconds(1), config.restart().window());
        assertEquals(0, config.restart().maxPerWindow(), "负的重启上限按 0 处理");
    }

    @Test
    void blankOptionalTextFallsBackToDefaults() {
        Map<String, Object> root = Map.<String, Object>of(
                "website", Map.<String, Object>of("health-path", "   "),
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

    @Test
    void constructorRejectsBlankPermissionOrHealthPathAndNonPositiveTimers() {
        assertThrows(IllegalArgumentException.class, () -> new PluginConfig(
                "http://example.test", "token", 100, 100,
                " ", PluginConfig.DEFAULT_LOGIN_TIMEOUT, PluginConfig.DEFAULT_RECONNECT_GRACE,
                PluginConfig.DEFAULT_PROMPT_INTERVAL, RestartSettings.DEFAULT,
                PluginConfig.DEFAULT_ADMIN_PERMISSION, PluginConfig.DEFAULT_LIMBO_WORLD,
                PluginConfig.DEFAULT_LIMBO_SPAWN, PluginConfig.DEFAULT_MAIN_SERVER));
        assertThrows(IllegalArgumentException.class, () -> new PluginConfig(
                "http://example.test", "token", 100, 100,
                PluginConfig.DEFAULT_HEALTH_PATH, PluginConfig.DEFAULT_LOGIN_TIMEOUT,
                PluginConfig.DEFAULT_RECONNECT_GRACE, PluginConfig.DEFAULT_PROMPT_INTERVAL,
                RestartSettings.DEFAULT, " ",
                PluginConfig.DEFAULT_LIMBO_WORLD, PluginConfig.DEFAULT_LIMBO_SPAWN,
                PluginConfig.DEFAULT_MAIN_SERVER));
        assertThrows(IllegalArgumentException.class, () -> new PluginConfig(
                "http://example.test", "token", 100, 100,
                PluginConfig.DEFAULT_HEALTH_PATH, Duration.ZERO,
                PluginConfig.DEFAULT_RECONNECT_GRACE, PluginConfig.DEFAULT_PROMPT_INTERVAL,
                RestartSettings.DEFAULT, PluginConfig.DEFAULT_ADMIN_PERMISSION,
                PluginConfig.DEFAULT_LIMBO_WORLD, PluginConfig.DEFAULT_LIMBO_SPAWN,
                PluginConfig.DEFAULT_MAIN_SERVER));
    }
}