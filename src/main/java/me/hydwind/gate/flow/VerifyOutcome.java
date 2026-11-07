package me.hydwind.gate.flow;

import java.util.Objects;

/**
 * 网站校验结果在登录流程中的最终含义。
 *
 * @param authenticated    是否应放行并转主服
 * @param triggerRestart   是否应触发受控重启（只有网站不可达时为 {@code true}）
 * @param contractMismatch 网站响应与插件契约不兼容（不应据此重启，只提示配置异常）
 * @param message          发给玩家的文案；迟到作废的成功结果为空字符串
 */
public record VerifyOutcome(boolean authenticated, boolean triggerRestart,
                            boolean contractMismatch, String message) {

    public VerifyOutcome {
        Objects.requireNonNull(message, "message");
    }
}