package me.hydwind.gate.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * 受控重启配置：请求文件位置与准入策略参数。
 *
 * @param requestFile  健康检查失败时写入的请求文件
 * @param cooldown     两次重启之间的最小间隔
 * @param window       每小时限额的滚动窗口
 * @param maxPerWindow 窗口内最多允许的重启次数
 */
public record RestartSettings(Path requestFile, Duration cooldown, Duration window, int maxPerWindow) {

    public static final RestartSettings DEFAULT = new RestartSettings(
            Path.of("/run/hydwind/restart-request"),
            Duration.ofMinutes(10),
            Duration.ofHours(1),
            2);

    public RestartSettings {
        Objects.requireNonNull(requestFile, "requestFile");
        Objects.requireNonNull(cooldown, "cooldown");
        Objects.requireNonNull(window, "window");
        if (cooldown.isNegative() || cooldown.isZero()) {
            throw new IllegalArgumentException("cooldown must be positive");
        }
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive");
        }
        if (maxPerWindow < 0) {
            throw new IllegalArgumentException("maxPerWindow must not be negative");
        }
    }
}