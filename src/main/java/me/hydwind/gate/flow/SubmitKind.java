package me.hydwind.gate.flow;

/** 一次 {@code /login <码>} 提交的分类结果，供 Adapter 决定发什么提示。 */
public enum SubmitKind {
    /** 不是登录命令，直接忽略。 */
    IGNORED,
    /** 是登录命令但没有登录码，提示玩家补上登录码。 */
    EMPTY_CODE,
    /** 登录码超过网站接受的长度。 */
    TOO_LONG,
    /** 会话已认证，无需再次提交。 */
    ALREADY_AUTHENTICATED,
    /** 已进入校验，等待网站返回结果。 */
    VERIFYING,
    /** 会话不存在、已超时或已在验证中，本次提交被拒绝。 */
    REJECTED
}