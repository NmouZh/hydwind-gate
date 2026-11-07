package me.hydwind.gate.command;

import java.util.Objects;

/**
 * 解析后的 {@code /hydwind} 请求。
 *
 * <p>只是结果载体；解析、补全与目标判定统一由 {@link CommandGrammar} 提供。</p>
 *
 * @param type   子命令类型
 * @param target 需要玩家名的子命令的目标；缺省时为 {@code null}，由执行器给出对应用法提示
 */
public record AdminCommandRequest(Type type, String target) {

    public enum Type {
        /** 没有子命令。 */
        USAGE,
        /** 未知子命令。 */
        UNKNOWN,
        STATUS,
        RELOAD,
        /** 需要目标玩家；{@link #target()} 可能为 {@code null}。 */
        APPROVE,
        /** 需要目标玩家；{@link #target()} 可能为 {@code null}。 */
        KICK
    }

    public AdminCommandRequest {
        Objects.requireNonNull(type, "type");
    }
}
