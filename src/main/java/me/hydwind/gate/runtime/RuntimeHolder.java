package me.hydwind.gate.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 运行时代际的持有者。
 *
 * <p>发布时用一次 {@link AtomicReference} 替换完成整代替换，读取方看到的要么全是旧配置，
 * 要么全是新配置，不会读到半装配状态。关闭当前代际是幂等的，只有第一次调用会真正关闭。</p>
 */
public final class RuntimeHolder {

    private final AtomicReference<RuntimeSnapshot> current = new AtomicReference<>();

    /** 创建一个尚未发布任何代际的持有者；首次 {@link #publish} 之前 {@link #current()} 返回 {@code null}。 */
    public RuntimeHolder() {
    }

    /**
     * 当前代际。
     *
     * @return 当前代际；尚未发布或已关闭时返回 {@code null}
     */
    public RuntimeSnapshot current() {
        return current.get();
    }

    /**
     * 发布新一代运行时依赖，并返回被替换的旧代际。旧代际不会自动关闭，由调用方负责。
     *
     * @param next 新代际，不能为空
     * @return 被替换的旧代际；首次发布时为 {@code null}
     */
    public RuntimeSnapshot publish(RuntimeSnapshot next) {
        return current.getAndSet(Objects.requireNonNull(next, "next"));
    }

    /**
     * 关闭当前代际并清空引用。重复调用是安全的：只有第一次拿到代际的调用会执行关闭。
     */
    public void close() {
        RuntimeSnapshot snapshot = current.getAndSet(null);
        if (snapshot != null) {
            snapshot.close();
        }
    }
}