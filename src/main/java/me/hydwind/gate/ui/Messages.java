package me.hydwind.gate.ui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.hydwind.gate.config.YamlMaps;

/**
 * 玩家可见文案。文案来自 {@code config.yml} 的 {@code messages} 段。
 */
public record Messages(
        String gateTitle,
        String gatePrompt,
        String loginSuccess,
        String loginSuccessAdmin,
        String serviceUnavailable,
        String serviceMisconfigured,
        String forbidden,
        String notFound,
        String sessionTimeout,
        String invalidCode,
        String expiredCode,
        String usedCode,
        String gameIdMismatch,
        String gameIdMismatchExpected,
        String codeTooLong,
        String rateLimited,
        String rateLimitedNoRetry,
        String rateLimitedWithAttempts,
        String rateLimitedNoRetryWithAttempts,
        String emptyCode,
        String verifying,
        String alreadyAuthenticated,
        String statusUnverified,
        AdminMessages admin
) {

    private static final String DEFAULT_GATE_TITLE = "氢风门禁";
    private static final String DEFAULT_GATE_PROMPT =
            "请在浏览器打开 {url} 登录账号，然后回到游戏输入 /login <登录码> (｡･ω･｡)";
    private static final String DEFAULT_LOGIN_SUCCESS = "登录成功，欢迎回来，{name}！(๑•̀ㅂ•́)و✧";
    private static final String DEFAULT_LOGIN_SUCCESS_ADMIN = "管理员 {name} 登录成功，欢迎回来！";
    private static final String DEFAULT_SERVICE_UNAVAILABLE = "认证服务暂时不可用，请稍后重试。";
    private static final String DEFAULT_SERVICE_MISCONFIGURED = "认证服务配置异常，请联系管理员。";
    private static final String DEFAULT_FORBIDDEN = "网站拒绝了本次登录请求，请联系管理员。";
    private static final String DEFAULT_NOT_FOUND = "登录接口不存在，请联系管理员检查网站配置。";
    private static final String DEFAULT_SESSION_TIMEOUT = "登录超时，请重新进服完成认证。";
    private static final String DEFAULT_INVALID_CODE =
            "登录码不存在，请检查后重新输入。如果没有账号，请到 QQ 群私聊机器人注册 游戏ID。";
    private static final String DEFAULT_EXPIRED_CODE = "登录码已过期，请回到网站重新获取。";
    private static final String DEFAULT_USED_CODE = "登录码已被使用，请回到网站重新获取。";
    private static final String DEFAULT_GAME_ID_MISMATCH = "网站账号名与你的游戏 ID 不一致，请使用与游戏 ID 相同的账号登录。";
    private static final String DEFAULT_GAME_ID_MISMATCH_EXPECTED =
            "此账号对应游戏 ID 为 {expectedGameId}，请使用该游戏 ID 对应的账号登录。";
    private static final String DEFAULT_CODE_TOO_LONG = "登录码过长，请检查后重新输入。";
    private static final String DEFAULT_RATE_LIMITED = "尝试过多，请 {seconds} 秒后再试。";
    private static final String DEFAULT_RATE_LIMITED_NO_RETRY = "尝试过多，请稍后再试。";
    private static final String DEFAULT_RATE_LIMITED_WITH_ATTEMPTS =
            "尝试过多，请 {seconds} 秒后再试，剩余 {attempts} 次。";
    private static final String DEFAULT_RATE_LIMITED_NO_RETRY_WITH_ATTEMPTS =
            "尝试过多，请稍后再试，剩余 {attempts} 次。";
    private static final String DEFAULT_EMPTY_CODE = "请先填写登录码，或使用 /login <登录码>。";
    private static final String DEFAULT_VERIFYING = "正在验证登录码，请稍候…";
    private static final String DEFAULT_ALREADY_AUTHENTICATED = "你已经完成认证，可以直接游戏。";
    private static final String DEFAULT_STATUS_UNVERIFIED = "当前状态：尚未认证。";

    /** 默认文案里的占位符形如 {@code {name}}。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    /** 默认文案，供测试与未加载配置的场景使用。 */
    public static Messages defaults() {
        return new Messages(
                DEFAULT_GATE_TITLE,
                DEFAULT_GATE_PROMPT,
                DEFAULT_LOGIN_SUCCESS,
                DEFAULT_LOGIN_SUCCESS_ADMIN,
                DEFAULT_SERVICE_UNAVAILABLE,
                DEFAULT_SERVICE_MISCONFIGURED,
                DEFAULT_FORBIDDEN,
                DEFAULT_NOT_FOUND,
                DEFAULT_SESSION_TIMEOUT,
                DEFAULT_INVALID_CODE,
                DEFAULT_EXPIRED_CODE,
                DEFAULT_USED_CODE,
                DEFAULT_GAME_ID_MISMATCH,
                DEFAULT_GAME_ID_MISMATCH_EXPECTED,
                DEFAULT_CODE_TOO_LONG,
                DEFAULT_RATE_LIMITED,
                DEFAULT_RATE_LIMITED_NO_RETRY,
                DEFAULT_RATE_LIMITED_WITH_ATTEMPTS,
                DEFAULT_RATE_LIMITED_NO_RETRY_WITH_ATTEMPTS,
                DEFAULT_EMPTY_CODE,
                DEFAULT_VERIFYING,
                DEFAULT_ALREADY_AUTHENTICATED,
                DEFAULT_STATUS_UNVERIFIED,
                AdminMessages.defaults()
        );
    }

    public static Messages from(Map<String, Object> root) {
        Map<String, Object> section = YamlMaps.section(root, "messages");
        Messages defaults = defaults();
        return new Messages(
                YamlMaps.rawString(section, "gate-title", defaults.gateTitle()),
                YamlMaps.rawString(section, "gate-prompt", defaults.gatePrompt()),
                YamlMaps.rawString(section, "login-success", defaults.loginSuccess()),
                YamlMaps.rawString(section, "login-success-admin", defaults.loginSuccessAdmin()),
                YamlMaps.rawString(section, "service-unavailable", defaults.serviceUnavailable()),
                YamlMaps.rawString(section, "service-misconfigured", defaults.serviceMisconfigured()),
                YamlMaps.rawString(section, "forbidden", defaults.forbidden()),
                YamlMaps.rawString(section, "not-found", defaults.notFound()),
                YamlMaps.rawString(section, "session-timeout", defaults.sessionTimeout()),
                YamlMaps.rawString(section, "invalid-code", defaults.invalidCode()),
                YamlMaps.rawString(section, "expired-code", defaults.expiredCode()),
                YamlMaps.rawString(section, "used-code", defaults.usedCode()),
                YamlMaps.rawString(section, "game-id-mismatch", defaults.gameIdMismatch()),
                YamlMaps.rawString(section, "game-id-mismatch-expected", defaults.gameIdMismatchExpected()),
                YamlMaps.rawString(section, "code-too-long", defaults.codeTooLong()),
                YamlMaps.rawString(section, "rate-limited", defaults.rateLimited()),
                YamlMaps.rawString(section, "rate-limited-no-retry", defaults.rateLimitedNoRetry()),
                YamlMaps.rawString(section, "rate-limited-with-attempts", defaults.rateLimitedWithAttempts()),
                YamlMaps.rawString(section, "rate-limited-no-retry-with-attempts",
                        defaults.rateLimitedNoRetryWithAttempts()),
                YamlMaps.rawString(section, "empty-code", defaults.emptyCode()),
                YamlMaps.rawString(section, "verifying", defaults.verifying()),
                YamlMaps.rawString(section, "already-authenticated", defaults.alreadyAuthenticated()),
                YamlMaps.rawString(section, "status-unverified", defaults.statusUnverified()),
                AdminMessages.from(section)
        );
    }

    /**
     * 检查 {@code config.yml} 中已自定义的文案是否漏掉了默认文案里的占位符。
     *
     * <p>只做提示，不改变 {@link #from(Map)} 的运行时替换行为：漏掉的占位符在游戏里
     * 会原样显示，管理员可据此排查。配置里没有的键（仍使用默认值）不算问题；
     * 自定义文案里多出来的未知占位符也不报错。</p>
     *
     * @param root {@code config.yml} 解析后的根 map
     * @return 配置键 → 缺失占位符列表（如 {@code messages.gate-prompt → "{url}"}）；
     *         没有问题时为空 map
     */
    public static Map<String, String> placeholderWarnings(Map<String, Object> root) {
        Objects.requireNonNull(root, "root");
        Map<String, Object> section = YamlMaps.section(root, "messages");
        Map<String, Object> adminSection = YamlMaps.section(section, "admin");

        Map<String, String> warnings = new LinkedHashMap<>();
        collectWarnings("messages.", playerDefaultsByKey(), section, warnings);
        collectWarnings("messages.admin.", adminDefaultsByKey(), adminSection, warnings);
        return Collections.unmodifiableMap(warnings);
    }

    /** 对比一段配置里已出现的字符串，把缺失的默认占位符记进 {@code warnings}。 */
    private static void collectWarnings(String keyPrefix,
                                        Map<String, String> defaults,
                                        Map<String, Object> section,
                                        Map<String, String> warnings) {
        defaults.forEach((key, defaultValue) -> {
            Object configured = section.get(key);
            if (!(configured instanceof String customValue)) {
                return;
            }
            Set<String> missing = missingPlaceholders(defaultValue, customValue);
            if (!missing.isEmpty()) {
                warnings.put(keyPrefix + key, String.join(", ", missing));
            }
        });
    }

    /** 默认值里存在、自定义值里已不存在的占位符；保留出现顺序。 */
    private static Set<String> missingPlaceholders(String defaultValue, String customValue) {
        Set<String> missing = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(defaultValue);
        while (matcher.find()) {
            String placeholder = "{" + matcher.group(1) + "}";
            if (!customValue.contains(placeholder)) {
                missing.add(placeholder);
            }
        }
        return missing;
    }

    /** 玩家文案的配置键 → 默认值，供 {@link #placeholderWarnings(Map)} 对比。 */
    private static Map<String, String> playerDefaultsByKey() {
        Messages defaults = defaults();
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("gate-title", defaults.gateTitle());
        entries.put("gate-prompt", defaults.gatePrompt());
        entries.put("login-success", defaults.loginSuccess());
        entries.put("login-success-admin", defaults.loginSuccessAdmin());
        entries.put("service-unavailable", defaults.serviceUnavailable());
        entries.put("service-misconfigured", defaults.serviceMisconfigured());
        entries.put("forbidden", defaults.forbidden());
        entries.put("not-found", defaults.notFound());
        entries.put("session-timeout", defaults.sessionTimeout());
        entries.put("invalid-code", defaults.invalidCode());
        entries.put("expired-code", defaults.expiredCode());
        entries.put("used-code", defaults.usedCode());
        entries.put("game-id-mismatch", defaults.gameIdMismatch());
        entries.put("game-id-mismatch-expected", defaults.gameIdMismatchExpected());
        entries.put("code-too-long", defaults.codeTooLong());
        entries.put("rate-limited", defaults.rateLimited());
        entries.put("rate-limited-no-retry", defaults.rateLimitedNoRetry());
        entries.put("rate-limited-with-attempts", defaults.rateLimitedWithAttempts());
        entries.put("rate-limited-no-retry-with-attempts", defaults.rateLimitedNoRetryWithAttempts());
        entries.put("empty-code", defaults.emptyCode());
        entries.put("verifying", defaults.verifying());
        entries.put("already-authenticated", defaults.alreadyAuthenticated());
        entries.put("status-unverified", defaults.statusUnverified());
        return entries;
    }

    /** 管理员文案的配置键 → 默认值（相对 {@code messages.admin} 段）。 */
    private static Map<String, String> adminDefaultsByKey() {
        AdminMessages defaults = defaults().admin();
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("no-permission", defaults.noPermission());
        entries.put("usage", defaults.usage());
        entries.put("unknown-subcommand", defaults.unknownSubcommand());
        entries.put("reloaded", defaults.reloaded());
        entries.put("reload-failed", defaults.reloadFailed());
        entries.put("approve-usage", defaults.approveUsage());
        entries.put("kick-usage", defaults.kickUsage());
        entries.put("session-not-found", defaults.sessionNotFound());
        entries.put("already-authenticated", defaults.alreadyAuthenticated());
        entries.put("approved-player", defaults.approvedPlayer());
        entries.put("approved", defaults.approved());
        entries.put("kicked-player", defaults.kickedPlayer());
        entries.put("kicked", defaults.kicked());
        entries.put("status-limbo", defaults.statusLimbo());
        entries.put("status-limbo-names", defaults.statusLimboNames());
        entries.put("status-pending", defaults.statusPending());
        entries.put("status-authenticated", defaults.statusAuthenticated());
        entries.put("status-authenticated-grace", defaults.statusAuthenticatedGrace());
        entries.put("status-health-healthy", defaults.statusHealthHealthy());
        entries.put("status-health-unhealthy", defaults.statusHealthUnhealthy());
        entries.put("status-restart-quota", defaults.statusRestartQuota());
        entries.put("status-next-allowed", defaults.statusNextAllowed());
        entries.put("status-permission", defaults.statusPermission());
        return entries;
    }
}