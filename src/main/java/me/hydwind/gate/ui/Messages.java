package me.hydwind.gate.ui;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 玩家可见文案。文案来自 {@code config.yml} 的 {@code messages} 段。
 */
public record Messages(
        String gatePrompt,
        String loginSuccess,
        String serviceUnavailable,
        String sessionTimeout
) {

    private static final String DEFAULT_GATE_PROMPT =
            "请在浏览器打开 {url} 登录账号，然后把验证码发送到游戏聊天框，或使用 /login <验证码>。";
    private static final String DEFAULT_LOGIN_SUCCESS = "登录成功，欢迎回来，{name}！";
    private static final String DEFAULT_SERVICE_UNAVAILABLE = "认证服务暂时不可用，请稍后重试。";
    private static final String DEFAULT_SESSION_TIMEOUT = "登录超时，请重新进服完成认证。";

    public static Messages from(FileConfiguration config) {
        return new Messages(
                config.getString("messages.gate-prompt", DEFAULT_GATE_PROMPT),
                config.getString("messages.login-success", DEFAULT_LOGIN_SUCCESS),
                config.getString("messages.service-unavailable", DEFAULT_SERVICE_UNAVAILABLE),
                config.getString("messages.session-timeout", DEFAULT_SESSION_TIMEOUT)
        );
    }
}
