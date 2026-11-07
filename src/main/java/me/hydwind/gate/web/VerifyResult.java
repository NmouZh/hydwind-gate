package me.hydwind.gate.web;

/**
 * 登录码校验结果。成功时携带账号信息，失败时只带状态和可选的诊断字段。
 */
public record VerifyResult(
        Status status,
        String username,
        String nickname,
        String role,
        Long retryAfterMs,
        Integer attemptsRemaining,
        String expectedGameId) {

    public enum Status {
        /** 校验通过。 */
        OK,
        /** 插件令牌错误。 */
        UNAUTHORIZED,
        /** 登录码不存在。 */
        INVALID_CODE,
        /** 登录码已过期。 */
        EXPIRED,
        /** 登录码已被使用。 */
        USED,
        /** 账号名与游戏 ID 不一致。 */
        GAME_ID_MISMATCH,
        /** 尝试过于频繁，网站要求稍后再试。 */
        RATE_LIMITED,
        /** 网站不可达、超时或返回了无法理解的内容。 */
        UNREACHABLE
    }

    public VerifyResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
    }

    public static VerifyResult ok(String username, String nickname, String role) {
        return new VerifyResult(Status.OK, username, nickname, role, null, null, null);
    }

    public static VerifyResult unauthorized() {
        return failed(Status.UNAUTHORIZED);
    }

    public static VerifyResult invalidCode() {
        return failed(Status.INVALID_CODE);
    }

    public static VerifyResult expired() {
        return failed(Status.EXPIRED);
    }

    public static VerifyResult used() {
        return failed(Status.USED);
    }

    public static VerifyResult gameIdMismatch() {
        return gameIdMismatch(null);
    }

    public static VerifyResult gameIdMismatch(String expectedGameId) {
        return new VerifyResult(Status.GAME_ID_MISMATCH, null, null, null, null, null, expectedGameId);
    }

    public static VerifyResult rateLimited(Long retryAfterMs, Integer attemptsRemaining) {
        return new VerifyResult(Status.RATE_LIMITED, null, null, null, retryAfterMs, attemptsRemaining, null);
    }

    public static VerifyResult unreachable() {
        return failed(Status.UNREACHABLE);
    }

    private static VerifyResult failed(Status status) {
        return new VerifyResult(status, null, null, null, null, null, null);
    }
}