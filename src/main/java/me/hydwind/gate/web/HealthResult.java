package me.hydwind.gate.web;

/**
 * 网站健康检查结果。
 *
 * <p>区分两种“不健康”：{@code transportFailure=true} 表示网络层不可达（连接失败、
 * 超时等），受控重启可能修复；{@code transportFailure=false} 表示网站答了但
 * 响应不符合契约（状态码或健康体不正确），重启没有意义。</p>
 *
 * @param healthy          网站是否可用
 * @param transportFailure 是否属于传输层失败（不可达 / 超时）
 * @param uptimeSeconds    网站自报的运行秒数；不可用时为 0
 */
public record HealthResult(boolean healthy, boolean transportFailure, long uptimeSeconds) {

    public static HealthResult healthy(long uptimeSeconds) {
        return new HealthResult(true, false, uptimeSeconds);
    }

    /** 传输层不可达；允许 {@link RestartTrigger} 考虑受控重启。 */
    public static HealthResult unhealthy() {
        return new HealthResult(false, true, 0L);
    }

    /** 网站有响应但健康信息不符合契约；不触发重启。 */
    public static HealthResult unhealthyContract() {
        return new HealthResult(false, false, 0L);
    }
}
