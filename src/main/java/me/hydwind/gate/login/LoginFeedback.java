package me.hydwind.gate.login;

import java.util.Objects;
import me.hydwind.gate.ui.Messages;
import me.hydwind.gate.ui.Templates;
import me.hydwind.gate.web.VerifyResult;

/**
 * 把网站校验结果翻译成玩家文案与后续动作。纯逻辑，不访问 Velocity。
 *
 * @param authenticated   是否应放行并转主服
 * @param triggerRestart  是否应触发受控重启（只有网站不可达时）
 * @param message         发给玩家的文案
 */
public record LoginFeedback(boolean authenticated, boolean triggerRestart, String message) {

    public LoginFeedback {
        Objects.requireNonNull(message, "message");
    }

    public static LoginFeedback from(VerifyResult result, Messages messages, String gameId) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(messages, "messages");

        return switch (result.status()) {
            case OK -> new LoginFeedback(true, false,
                    Templates.fill(adminRole(result) ? messages.loginSuccessAdmin() : messages.loginSuccess(),
                            "name", displayName(result, gameId)));
            case UNAUTHORIZED -> new LoginFeedback(false, false, messages.serviceMisconfigured());
            case FORBIDDEN -> new LoginFeedback(false, false, messages.forbidden());
            case NOT_FOUND -> new LoginFeedback(false, false, messages.notFound());
            case INVALID_CODE -> new LoginFeedback(false, false, messages.invalidCode());
            case EXPIRED -> new LoginFeedback(false, false, messages.expiredCode());
            case USED -> new LoginFeedback(false, false, messages.usedCode());
            case GAME_ID_MISMATCH -> new LoginFeedback(false, false,
                    isBlank(result.expectedGameId())
                            ? messages.gameIdMismatch()
                            : Templates.fill(messages.gameIdMismatchExpected(),
                                    "expectedGameId", result.expectedGameId()));
            case RATE_LIMITED -> new LoginFeedback(false, false, rateLimitMessage(result, messages));
            // 网站答了但契约不符：按配置异常提示，重启网站无意义。
            case CONTRACT_MISMATCH -> new LoginFeedback(false, false, messages.serviceMisconfigured());
            case UNREACHABLE -> new LoginFeedback(false, true, messages.serviceUnavailable());
        };
    }

    private static String rateLimitMessage(VerifyResult result, Messages messages) {
        Long retryAfterMs = result.retryAfterMs();
        Integer attemptsRemaining = result.attemptsRemaining();
        if (retryAfterMs == null) {
            return attemptsRemaining == null
                    ? messages.rateLimitedNoRetry()
                    : Templates.fill(messages.rateLimitedNoRetryWithAttempts(),
                            "attempts", attemptsRemaining);
        }
        long seconds = Math.max(1L, (retryAfterMs + 999L) / 1000L);
        return attemptsRemaining == null
                ? Templates.fill(messages.rateLimited(), "seconds", Long.toString(seconds))
                : Templates.fill(messages.rateLimitedWithAttempts(),
                        "seconds", Long.toString(seconds), "attempts", attemptsRemaining);
    }

    private static String displayName(VerifyResult result, String gameId) {
        if (!isBlank(result.nickname())) {
            return result.nickname();
        }
        if (!isBlank(result.username())) {
            return result.username();
        }
        return gameId == null ? "" : gameId;
    }

    private static boolean adminRole(VerifyResult result) {
        return "admin".equalsIgnoreCase(result.role());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}