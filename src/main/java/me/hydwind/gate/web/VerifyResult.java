package me.hydwind.gate.web;

/**
 * 登录码校验结果。成功时携带账号信息，失败时只带状态。
 */
public record VerifyResult(Status status, String username, String nickname, String role) {

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
        /** 网站不可达、超时或返回了无法理解的内容。 */
        UNREACHABLE
    }

    public VerifyResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
    }

    public static VerifyResult ok(String username, String nickname, String role) {
        return new VerifyResult(Status.OK, username, nickname, role);
    }

    public static VerifyResult unauthorized() {
        return new VerifyResult(Status.UNAUTHORIZED, null, null, null);
    }

    public static VerifyResult invalidCode() {
        return new VerifyResult(Status.INVALID_CODE, null, null, null);
    }

    public static VerifyResult expired() {
        return new VerifyResult(Status.EXPIRED, null, null, null);
    }

    public static VerifyResult used() {
        return new VerifyResult(Status.USED, null, null, null);
    }

    public static VerifyResult gameIdMismatch() {
        return new VerifyResult(Status.GAME_ID_MISMATCH, null, null, null);
    }

    public static VerifyResult unreachable() {
        return new VerifyResult(Status.UNREACHABLE, null, null, null);
    }
}
