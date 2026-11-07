package me.hydwind.gate.ui;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 玩家可见文案。文案来自 {@code config.yml} 的 {@code messages} 段。
 */
public record Messages(
        String gateTitle,
        String gatePrompt,
        String codeLabel,
        String submitButton,
        String submitTooltip,
        String reopenButton,
        String reopenTooltip,
        String loginSuccess,
        String serviceUnavailable,
        String serviceMisconfigured,
        String sessionTimeout,
        String invalidCode,
        String expiredCode,
        String usedCode,
        String gameIdMismatch,
        String emptyCode,
        String verifying,
        String alreadyAuthenticated,
        String loginSuccessAdmin,
        String gameIdMismatchExpected,
        String codeTooLong,
        String rateLimited,
        String rateLimitedNoRetry,
        AdminMessages admin
) {

    private static final String DEFAULT_GATE_TITLE = "氢风门禁";
    private static final String DEFAULT_GATE_PROMPT =
            "请在浏览器打开 {url} 登录账号，然后复制颜文字登录码粘贴到下面的输入框 (｡･ω･｡)";
    private static final String DEFAULT_CODE_LABEL = "登录码";
    private static final String DEFAULT_SUBMIT_BUTTON = "提交验证";
    private static final String DEFAULT_SUBMIT_TOOLTIP = "提交对话框中的登录码或备用码";
    private static final String DEFAULT_REOPEN_BUTTON = "重新打开网站";
    private static final String DEFAULT_REOPEN_TOOLTIP = "在浏览器中打开登录网站";
    private static final String DEFAULT_LOGIN_SUCCESS = "登录成功，欢迎回来，{name}！(๑•̀ㅂ•́)و✧";
    private static final String DEFAULT_SERVICE_UNAVAILABLE = "认证服务暂时不可用，请稍后重试。";
    private static final String DEFAULT_SERVICE_MISCONFIGURED = "认证服务配置异常，请联系管理员。";
    private static final String DEFAULT_SESSION_TIMEOUT = "登录超时，请重新进服完成认证。";
    private static final String DEFAULT_INVALID_CODE = "登录码不存在，请检查后重新输入。";
    private static final String DEFAULT_EXPIRED_CODE = "登录码已过期，请回到网站重新获取。";
    private static final String DEFAULT_USED_CODE = "登录码已被使用，请回到网站重新获取。";
    private static final String DEFAULT_GAME_ID_MISMATCH = "网站账号名与你的游戏 ID 不一致，请使用与游戏 ID 相同的账号登录。";
    private static final String DEFAULT_EMPTY_CODE = "请先填写登录码，或使用 /login <登录码>。";
    private static final String DEFAULT_VERIFYING = "正在验证登录码，请稍候…";
    private static final String DEFAULT_ALREADY_AUTHENTICATED = "你已经完成认证，可以直接游戏。";
    private static final String DEFAULT_LOGIN_SUCCESS_ADMIN = "管理员 {name} 登录成功，欢迎回来！";
    private static final String DEFAULT_GAME_ID_MISMATCH_EXPECTED =
            "此账号对应游戏 ID 为 {expectedGameId}，请使用该游戏 ID 对应的账号登录。";
    private static final String DEFAULT_CODE_TOO_LONG = "登录码过长，请检查后重新输入。";
    private static final String DEFAULT_RATE_LIMITED = "尝试过多，请 {seconds} 秒后再试。";
    private static final String DEFAULT_RATE_LIMITED_NO_RETRY = "尝试过多，请稍后再试。";

    /** 默认文案，供测试与未加载配置的场景使用。 */
    public static Messages defaults() {
        return new Messages(
                DEFAULT_GATE_TITLE,
                DEFAULT_GATE_PROMPT,
                DEFAULT_CODE_LABEL,
                DEFAULT_SUBMIT_BUTTON,
                DEFAULT_SUBMIT_TOOLTIP,
                DEFAULT_REOPEN_BUTTON,
                DEFAULT_REOPEN_TOOLTIP,
                DEFAULT_LOGIN_SUCCESS,
                DEFAULT_SERVICE_UNAVAILABLE,
                DEFAULT_SERVICE_MISCONFIGURED,
                DEFAULT_SESSION_TIMEOUT,
                DEFAULT_INVALID_CODE,
                DEFAULT_EXPIRED_CODE,
                DEFAULT_USED_CODE,
                DEFAULT_GAME_ID_MISMATCH,
                DEFAULT_EMPTY_CODE,
                DEFAULT_VERIFYING,
                DEFAULT_ALREADY_AUTHENTICATED,
                DEFAULT_LOGIN_SUCCESS_ADMIN,
                DEFAULT_GAME_ID_MISMATCH_EXPECTED,
                DEFAULT_CODE_TOO_LONG,
                DEFAULT_RATE_LIMITED,
                DEFAULT_RATE_LIMITED_NO_RETRY,
                AdminMessages.defaults()
        );
    }

    public static Messages from(FileConfiguration config) {
        Messages defaults = defaults();
        return new Messages(
                config.getString("messages.gate-title", defaults.gateTitle()),
                config.getString("messages.gate-prompt", defaults.gatePrompt()),
                config.getString("messages.code-label", defaults.codeLabel()),
                config.getString("messages.submit-button", defaults.submitButton()),
                config.getString("messages.submit-tooltip", defaults.submitTooltip()),
                config.getString("messages.reopen-button", defaults.reopenButton()),
                config.getString("messages.reopen-tooltip", defaults.reopenTooltip()),
                config.getString("messages.login-success", defaults.loginSuccess()),
                config.getString("messages.service-unavailable", defaults.serviceUnavailable()),
                config.getString("messages.service-misconfigured", defaults.serviceMisconfigured()),
                config.getString("messages.session-timeout", defaults.sessionTimeout()),
                config.getString("messages.invalid-code", defaults.invalidCode()),
                config.getString("messages.expired-code", defaults.expiredCode()),
                config.getString("messages.used-code", defaults.usedCode()),
                config.getString("messages.game-id-mismatch", defaults.gameIdMismatch()),
                config.getString("messages.empty-code", defaults.emptyCode()),
                config.getString("messages.verifying", defaults.verifying()),
                config.getString("messages.already-authenticated", defaults.alreadyAuthenticated()),
                config.getString("messages.login-success-admin", defaults.loginSuccessAdmin()),
                config.getString("messages.game-id-mismatch-expected", defaults.gameIdMismatchExpected()),
                config.getString("messages.code-too-long", defaults.codeTooLong()),
                config.getString("messages.rate-limited", defaults.rateLimited()),
                config.getString("messages.rate-limited-no-retry", defaults.rateLimitedNoRetry()),
                AdminMessages.from(config)
        );
    }
}