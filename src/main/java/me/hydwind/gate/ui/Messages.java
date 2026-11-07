package me.hydwind.gate.ui;

import java.util.Map;
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
}