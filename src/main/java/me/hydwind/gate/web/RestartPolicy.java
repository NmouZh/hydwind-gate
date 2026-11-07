package me.hydwind.gate.web;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import me.hydwind.gate.config.RestartSettings;

/**
 * 受控重启的准入策略：两次重启至少相隔 10 分钟，任意滚动一小时内最多 2 次。
 *
 * <p>超限只返回 {@code false}，由调用方决定是否记录告警；不抛异常。
 * 时间由调用方注入，便于测试。
 */
public final class RestartPolicy {

    static final Duration COOLDOWN = Duration.ofMinutes(10);
    static final Duration WINDOW = Duration.ofHours(1);
    static final int MAX_PER_WINDOW = 2;

    private Duration cooldown;
    private Duration window;
    private int maxPerWindow;
    private final List<Instant> acquisitions = new ArrayList<>();

    public RestartPolicy() {
        this(COOLDOWN, WINDOW, MAX_PER_WINDOW);
    }

    public RestartPolicy(RestartSettings settings) {
        this(Objects.requireNonNull(settings, "settings").cooldown(),
                settings.window(), settings.maxPerWindow());
    }

    public RestartPolicy(Duration cooldown, Duration window, int maxPerWindow) {
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
        this.window = Objects.requireNonNull(window, "window");
        this.maxPerWindow = maxPerWindow;
    }

    /**
     * 用热重载后的配置更新策略，但保留已记录的触发历史，
     * 避免 reload 清空冷却和窗口内计数。
     */
    public synchronized void reconfigure(RestartSettings settings) {
        Objects.requireNonNull(settings, "settings");
        this.cooldown = Objects.requireNonNull(settings.cooldown(), "cooldown");
        this.window = Objects.requireNonNull(settings.window(), "window");
        this.maxPerWindow = settings.maxPerWindow();
    }

    /**
     * 尝试取得一次重启许可。允许时记录本次触发；拒绝时不做任何改变，
     * 因此被拒绝的尝试不会重置冷却或占用次数。
     */
    public synchronized boolean tryAcquire(Instant now) {
        if (now == null) {
            throw new NullPointerException("now");
        }
        prune(now);
        if (acquisitions.size() >= maxPerWindow) {
            return false;
        }
        if (!acquisitions.isEmpty()) {
            Instant last = acquisitions.getLast();
            if (now.isBefore(last.plus(cooldown))) {
                return false;
            }
        }
        acquisitions.add(now);
        return true;
    }

    /** 当前还可用的重启额度，冷却期内为 0。 */
    public synchronized int remaining(Instant now) {
        Objects.requireNonNull(now, "now");
        prune(now);
        if (!acquisitions.isEmpty() && now.isBefore(acquisitions.getLast().plus(cooldown))) {
            return 0;
        }
        return Math.max(0, maxPerWindow - acquisitions.size());
    }

    /** 冷却结束、额度恢复的最早时间；已有额度时为空。 */
    public synchronized Optional<Instant> nextAllowed(Instant now) {
        Objects.requireNonNull(now, "now");
        prune(now);
        if (remaining(now) > 0) {
            return Optional.empty();
        }
        if (acquisitions.isEmpty()) {
            return Optional.empty();
        }
        Instant cooldownEnd = acquisitions.getLast().plus(cooldown);
        if (now.isBefore(cooldownEnd)) {
            return Optional.of(cooldownEnd);
        }
        if (maxPerWindow > 0 && acquisitions.size() >= maxPerWindow) {
            return Optional.of(acquisitions.getFirst().plus(window).plusNanos(1));
        }
        return Optional.empty();
    }

    private void prune(Instant now) {
        Instant cutoff = now.minus(window);
        acquisitions.removeIf(attempt -> !attempt.isAfter(cutoff));
    }
}
