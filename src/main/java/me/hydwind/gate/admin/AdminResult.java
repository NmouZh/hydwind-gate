package me.hydwind.gate.admin;

import java.util.Objects;

/**
 * 一次管理员 approve / kick 操作的结果。Adapter 只负责把它映射成玩家可见文案，
 * 不再自行推断“玩家在 limbo 还是已断线”。
 *
 * @param kind        操作落点与结果分类
 * @param playerName  操作针对的玩家显示名；调用方必须传入非 {@code null}
 */
public record AdminResult(Kind kind, String playerName) {

    public enum Kind {
        /** 放行成功，玩家仍在 limbo，已转服。 */
        APPROVED_IN_LIMBO,
        /** 放行成功，但玩家已断线，只更新了会话状态。 */
        APPROVED_OFFLINE,
        /** 踢出成功，玩家在 limbo 中被断开。 */
        KICKED_IN_LIMBO,
        /** 踢出成功，玩家不在 limbo；可能已断开后端连接，也可能只剩会话残留。 */
        KICKED_BACKEND,
        /** 会话已经是认证状态，无需再次放行。 */
        ALREADY_AUTHENTICATED,
        /** 会话、limbo、后端在线玩家中都没有找到目标。 */
        NOT_FOUND
    }

    public AdminResult {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(playerName, "playerName");
    }
}