package me.hydwind.gate.web;

/**
 * 网站健康检查结果。
 *
 * @param healthy       网站是否可用
 * @param uptimeSeconds 网站自报的运行秒数；不可用时为 0
 */
public record HealthResult(boolean healthy, long uptimeSeconds) {

    public static HealthResult healthy(long uptimeSeconds) {
        return new HealthResult(true, uptimeSeconds);
    }

    public static HealthResult unhealthy() {
        return new HealthResult(false, 0L);
    }
}
