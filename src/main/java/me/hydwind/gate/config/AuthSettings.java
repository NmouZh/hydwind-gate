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

    public AuthSettings {
        Objects.requireNonNull(loginTimeout, "loginTimeout");
        Objects.requireNonNull(reconnectGrace, "reconnectGrace");
        Objects.requireNonNull(promptInterval, "promptInterval");
        requirePositive(loginTimeout, "auth.login-timeout-seconds");
        requirePositive(reconnectGrace, "auth.session-grace-seconds");
        requirePositive(promptInterval, "auth.prompt-interval-seconds");
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
