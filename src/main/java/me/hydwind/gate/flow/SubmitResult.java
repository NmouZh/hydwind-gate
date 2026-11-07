package me.hydwind.gate.flow;

import java.util.Objects;

/**
 * 登录码提交结果。
 *
 * @param kind           提交分类，决定玩家提示
 * @param verificationId 本次校验的会话内编号；仅 {@link SubmitKind#VERIFYING} 时非 0
 * @param normalizedCode 归一化后的登录码；仅 {@link SubmitKind#VERIFYING} 时非 {@code null}
 */
public record SubmitResult(SubmitKind kind, long verificationId, String normalizedCode) {

    public SubmitResult {
        Objects.requireNonNull(kind, "kind");
    }
}