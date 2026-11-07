package me.hydwind.gate.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import me.hydwind.gate.config.RestartSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网站不可达时的受控重启触发。写入请求文件是唯一的外部副作用，
 * 用 {@link TempDir} 隔离，用注入的 {@link Clock} 控制冷却与窗口。
 */
class RestartTriggerTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Logger LOGGER = LoggerFactory.getLogger("RestartTriggerTest");

    @TempDir
    Path tempDir;

    private MutableClock clock;
    private FakeWebsiteClient website;
    private Path requestFile;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        website = new FakeWebsiteClient();
        requestFile = tempDir.resolve("restart-request");
    }

    @Test
    void firstUnhealthyCheckWritesRestartRequest() throws IOException {
        RestartTrigger trigger = trigger(settings(requestFile));

        trigger.onUnreachable();

        assertTrue(Files.exists(requestFile), "健康检查失败时应写入重启请求文件");
        String content = Files.readString(requestFile);
        assertTrue(content.contains("website-unreachable"), () -> "请求文件应说明原因: " + content);
        assertTrue(content.contains(START.toString()), () -> "请求文件应记录触发时间: " + content);
    }

    @Test
    void healthyWebsiteDoesNotWriteRestartRequest() {
        website.healthy = true;
        RestartTrigger trigger = trigger(settings(requestFile));

        trigger.onUnreachable();

        assertFalse(Files.exists(requestFile), "网站健康时不应触发重启");
    }

    @Test
    void contractMismatchDoesNotWriteRestartRequest() {
        website.contractMismatch = true;
        RestartTrigger trigger = trigger(settings(requestFile));

        trigger.onUnreachable();

        assertFalse(Files.exists(requestFile), "网站有响应但契约不匹配时不应触发重启");
    }

    @Test
    void secondTriggerWithinCooldownIsRejected() throws IOException {
        RestartTrigger trigger = trigger(settings(requestFile));
        trigger.onUnreachable();
        Files.delete(requestFile);

        clock.advance(Duration.ofMinutes(9));
        trigger.onUnreachable();

        assertFalse(Files.exists(requestFile), "10 分钟冷却内不应再次写入");

        clock.advance(Duration.ofMinutes(1));
        trigger.onUnreachable();
        assertTrue(Files.exists(requestFile), "冷却结束后应允许再次写入");
    }

    @Test
    void hourlyLimitCapsRestartRequests() throws IOException {
        RestartTrigger trigger = trigger(settings(requestFile));

        trigger.onUnreachable();
        Files.delete(requestFile);
        clock.advance(Duration.ofMinutes(10));
        trigger.onUnreachable();
        Files.delete(requestFile);

        clock.advance(Duration.ofMinutes(10));
        trigger.onUnreachable();
        assertFalse(Files.exists(requestFile), "一小时内第 3 次应被拒绝");

        clock.advance(Duration.ofMinutes(40)); // 从首次触发起满一小时
        trigger.onUnreachable();
        assertTrue(Files.exists(requestFile), "滚动窗口滑出后应恢复额度");
    }

    @Test
    void unwritableRequestPathOnlyWarnsAndDoesNotThrow() throws IOException {
        Path blocker = tempDir.resolve("not-a-directory");
        Files.writeString(blocker, "x");
        RestartTrigger trigger = trigger(settings(blocker.resolve("restart-request")));

        assertDoesNotThrow(trigger::onUnreachable);
    }

    @Test
    void healthProbeExceptionDoesNotWriteOrConsumeRestartAllowance() throws IOException {
        website.failWith = new IllegalStateException("boom");
        RestartTrigger trigger = trigger(settings(requestFile));

        assertDoesNotThrow(trigger::onUnreachable);
        assertFalse(Files.exists(requestFile), "探活自身异常不应写重启请求文件");

        website.failWith = null;
        trigger.onUnreachable();
        assertTrue(Files.exists(requestFile), "探活恢复后应保留首次重启额度");
        Files.delete(requestFile);
        clock.advance(Duration.ofMinutes(10));
        trigger.onUnreachable();
        assertTrue(Files.exists(requestFile), "异常未消耗额度，小时上限仍应允许第二次重启");
    }

    private RestartTrigger trigger(RestartSettings settings) {
        return new RestartTrigger(website, new RestartPolicy(RestartSettings.DEFAULT), settings, clock, LOGGER);
    }

    private static RestartSettings settings(Path path) {
        return new RestartSettings(path, Duration.ofMinutes(10), Duration.ofHours(1), 2);
    }

    private static final class FakeWebsiteClient implements WebsiteClient {

        private boolean healthy;
        private boolean contractMismatch;
        private RuntimeException failWith;

        @Override
        public VerifyResult verify(String code, String gameId, UUID uuid) {
            return VerifyResult.unreachable();
        }

        @Override
        public HealthResult health() {
            if (failWith != null) {
                throw failWith;
            }
            if (contractMismatch) {
                return HealthResult.unhealthyContract();
            }
            return healthy ? HealthResult.healthy(42) : HealthResult.unhealthy();
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}