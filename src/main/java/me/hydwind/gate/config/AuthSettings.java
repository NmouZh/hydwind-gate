package me.hydwind.gate.config;

import java.time.Duration;
import java.util.Objects;

/**
 * 登录会话时限设置（{@code auth} 配置段）。
 *
 * @param loginTimeout    PENDING / VERIFYING 会话的登录截止时长，必须为正
 * @param reconnectGrace  已认证玩家断线后的免登录宽限时长，必须为正
 * @param promptInterval  limbo 中重复提示登录的间隔，必须为正
 */
public record AuthSettings(Duration loginTimeout, Duration reconnectGrace, Duration promptInterval) {

    /** 上限：超出后 Duration 运算和玩家等待都没有实际意义。 */
    private static final Duration MAX_LOGIN_TIMEOUT = Duration.ofHours(1);
    private static final Duration MAX_RECONNECT_GRACE = Duration.ofDays(1);
    private static final Duration MAX_PROMPT_INTERVAL = Duration.ofHours(1);

    public AuthSettings {
        Objects.requireNonNull(loginTimeout, "loginTimeout");
        Objects.requireNonNull(reconnectGrace, "reconnectGrace");
        Objects.requireNonNull(promptInterval, "promptInterval");
        requireRange(loginTimeout, "auth.login-timeout-seconds", MAX_LOGIN_TIMEOUT);
        requireRange(reconnectGrace, "auth.session-grace-seconds", MAX_RECONNECT_GRACE);
        requireRange(promptInterval, "auth.prompt-interval-seconds", MAX_PROMPT_INTERVAL);
    }

    private static void requireRange(Duration value, String name, Duration max) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        if (value.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + " must not exceed " + max.toSeconds() + " seconds");
        }
    }
}
