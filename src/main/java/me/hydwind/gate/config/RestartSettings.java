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

    private static final Duration MAX_COOLDOWN = Duration.ofDays(1);
    private static final Duration MAX_WINDOW = Duration.ofDays(1);
    private static final int MAX_PER_WINDOW = 100;

    public RestartSettings {
        Objects.requireNonNull(requestFile, "requestFile");
        Objects.requireNonNull(cooldown, "cooldown");
        Objects.requireNonNull(window, "window");
        if (requestFile.toString().isBlank()) {
            throw new IllegalArgumentException("restart.request-path must not be blank");
        }
        if (cooldown.isNegative() || cooldown.isZero() || cooldown.compareTo(MAX_COOLDOWN) > 0) {
            throw new IllegalArgumentException(
                    "restart.cooldown-seconds must be between 1 and " + MAX_COOLDOWN.toSeconds());
        }
        if (window.isNegative() || window.isZero() || window.compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException(
                    "restart.window-seconds must be between 1 and " + MAX_WINDOW.toSeconds());
        }
        if (maxPerWindow < 0 || maxPerWindow > MAX_PER_WINDOW) {
            throw new IllegalArgumentException(
                    "restart.max-per-hour must be between 0 and " + MAX_PER_WINDOW);
        }
    }
}