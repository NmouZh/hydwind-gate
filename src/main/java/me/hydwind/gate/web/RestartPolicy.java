package me.hydwind.gate.web;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

    private final List<Instant> acquisitions = new ArrayList<>();

    /**
     * 尝试取得一次重启许可。允许时记录本次触发；拒绝时不做任何改变，
     * 因此被拒绝的尝试不会重置冷却或占用次数。
     */
    public synchronized boolean tryAcquire(Instant now) {
        if (now == null) {
            throw new NullPointerException("now");
        }
        prune(now);
        if (acquisitions.size() >= MAX_PER_WINDOW) {
            return false;
        }
        if (!acquisitions.isEmpty()) {
            Instant last = acquisitions.getLast();
            if (now.isBefore(last.plus(COOLDOWN))) {
                return false;
            }
        }
        acquisitions.add(now);
        return true;
    }

    private void prune(Instant now) {
        Instant cutoff = now.minus(WINDOW);
        acquisitions.removeIf(attempt -> !attempt.isAfter(cutoff));
    }
}
