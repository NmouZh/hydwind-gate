package me.hydwind.gate.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void unparsableNumericStringsFailLoadingInsteadOfFallingBackToDefaults() {
        Map<String, Object> website = Map.of(
                "base-url", "http://example.test",
                "connect-timeout-millis", "nope");
        IllegalArgumentException timeout = assertThrows(IllegalArgumentException.class,
                () -> PluginConfig.from(Map.of("website", website), null));
        assertEquals("config value 'connect-timeout-millis' is not a number: nope",
                timeout.getMessage(), "以前会静默回退默认值，现在必须让加载失败");

        Map<String, Object> auth = Map.of("prompt-interval-seconds", "abc");
        IllegalArgumentException interval = assertThrows(IllegalArgumentException.class,
                () -> PluginConfig.from(Map.of(
                        "website", Map.of("base-url", "http://example.test"),
                        "auth", auth), null));
        assertEquals("config value 'prompt-interval-seconds' is not a number: abc",
                interval.getMessage());

        IllegalArgumentException loginTimeout = assertThrows(IllegalArgumentException.class,
                () -> PluginConfig.from(Map.of(
                        "website", Map.of("base-url", "http://example.test"),
                        "auth", Map.of("login-timeout-seconds", "600s")), null));
        assertTrue(loginTimeout.getMessage().contains("'login-timeout-seconds'"),
                "600s 之类的时长写法必须在加载时报错，而不是变成 300 秒");
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
    void websiteSettingsRejectMalformedOrNonHttpBaseUrls() {
        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
                () -> new WebsiteSettings("not a uri", "token", 100, 100, "/api/health"));
        assertTrue(malformed.getMessage().startsWith("website.base-url is not a valid URI"),
                "语法错误必须在加载时暴露，而不是等到每次请求变成契约不匹配");

        assertThrows(IllegalArgumentException.class,
                () -> new WebsiteSettings("example.test", "token", 100, 100, "/api/health"),
                "缺少 scheme 不是合法的网站根地址");
        assertThrows(IllegalArgumentException.class,
                () -> new WebsiteSettings("ftp://example.test", "token", 100, 100, "/api/health"),
                "只接受 http 与 https");
        assertThrows(IllegalArgumentException.class,
                () -> PluginConfig.from(Map.of("website", Map.of(
                        "base-url", "not a uri")), null),
                "PluginConfig.from 也会在加载时失败");

        // 现有的本地回环地址必须继续通过。
        assertEquals("http://127.0.0.1:1",
                new WebsiteSettings("http://127.0.0.1:1", "token", 100, 100, "/api/health")
                        .baseUrl());
        assertEquals("HTTPS://example.test",
                new WebsiteSettings("HTTPS://example.test", "token", 100, 100, "/api/health")
                        .baseUrl(), "scheme 比较忽略大小写，保存原始文本");
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

    // ---------------------------------------------------------------------
    // 回归：旧别名惰性读取与 base-url 的 query/fragment 校验。
    // ---------------------------------------------------------------------

    @Test
    void unparsableLegacyMaxPerWindowIsIgnoredWhenMaxPerHourIsValid() {
        Map<String, Object> root = Map.<String, Object>of(
                "website", Map.<String, Object>of("base-url", "http://example.test"),
                "restart", Map.<String, Object>of(
                        "max-per-hour", 5,
                        "max-per-window", "2 次"));

        PluginConfig config = PluginConfig.from(root, null);

        assertEquals(5, config.restart().maxPerWindow(),
                "max-per-hour 合法时，未被读取的旧别名即使无法解析也不应让启动失败");
    }

    @Test
    void validLegacyMaxPerWindowStillAppliesWhenMaxPerHourIsAbsent() {
        Map<String, Object> root = Map.<String, Object>of(
                "website", Map.<String, Object>of("base-url", "http://example.test"),
                "restart", Map.<String, Object>of("max-per-window", 4));

        assertEquals(4, PluginConfig.from(root, null).restart().maxPerWindow(),
                "只有旧别名时仍必须按旧别名生效");

        // 边界：旧别名是新键缺项时唯一的来源，无法解析时仍应按原语义让加载失败，
        // 而不是被惰性读取一并吞掉。
        assertThrows(IllegalArgumentException.class, () -> PluginConfig.from(
                Map.of("website", Map.of("base-url", "http://example.test"),
                        "restart", Map.of("max-per-window", "2 次")), null),
                "没有合法的新键可依据时，无法解析的旧别名仍必须报错");
    }

    @Test
    void websiteBaseUrlRejectsQueryAndFragmentButAcceptsPlainPaths() {
        IllegalArgumentException query = assertThrows(IllegalArgumentException.class,
                () -> new WebsiteSettings("https://example.com/mc?tenant=x", "token", 100, 100, "/api/health"),
                "query 会让后续拼接的 /api 路径失效，必须在加载时拒绝");
        assertTrue(query.getMessage().contains("query"),
                "错误信息应明确指出 query 问题");

        IllegalArgumentException fragment = assertThrows(IllegalArgumentException.class,
                () -> new WebsiteSettings("https://example.com/mc#frag", "token", 100, 100, "/api/health"),
                "fragment 不应出现在网站根地址中，必须在加载时拒绝");
        assertTrue(fragment.getMessage().contains("fragment"),
                "错误信息应明确指出 fragment 问题");

        assertEquals("https://example.com/mc",
                new WebsiteSettings("https://example.com/mc", "token", 100, 100, "/api/health").baseUrl(),
                "不带尾斜杠的普通路径必须通过");
        assertEquals("https://example.com/mc/",
                new WebsiteSettings("https://example.com/mc/", "token", 100, 100, "/api/health").baseUrl(),
                "带尾斜杠的普通路径必须通过");

        assertThrows(IllegalArgumentException.class, () -> PluginConfig.from(
                Map.of("website", Map.of("base-url", "https://example.com/mc?tenant=x")), null),
                "PluginConfig.from 加载带 query 的 base-url 也必须失败");
    }

    // ---------------------------------------------------------------------
    // 回归：base-url 必须有 host，否则每次请求都会失败并被误判为契约不匹配。
    // ---------------------------------------------------------------------

    @Test
    void websiteBaseUrlMustIncludeAHost() {
        IllegalArgumentException emptyAuthority = assertThrows(IllegalArgumentException.class,
                () -> new WebsiteSettings("http:///", "token", 100, 100, "/api/health"),
                "http:/// 语法合法但没有 host，必须在加载时拒绝");
        assertTrue(emptyAuthority.getMessage().contains("host"),
                "错误信息应明确指出缺少 host");

        assertThrows(IllegalArgumentException.class,
                () -> new WebsiteSettings("https://", "token", 100, 100, "/api/health"),
                "https:// 没有 host，必须在加载时拒绝");

        assertEquals("https://example.com/mc",
                new WebsiteSettings("https://example.com/mc", "token", 100, 100, "/api/health")
                        .baseUrl(),
                "带 host 与路径的 https 地址必须通过");

        assertThrows(IllegalArgumentException.class, () -> PluginConfig.from(
                Map.of("website", Map.of("base-url", "http:///")), null),
                "PluginConfig.from 加载无 host 的 base-url 也必须失败");
    }
}
