package me.hydwind.gate.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import me.hydwind.gate.config.PluginConfig;
import me.hydwind.gate.config.RestartSettings;
import me.hydwind.gate.ui.Messages;
import me.hydwind.gate.web.HealthResult;
import me.hydwind.gate.web.RestartPolicy;
import me.hydwind.gate.web.RestartTrigger;
import me.hydwind.gate.web.VerifyResult;
import me.hydwind.gate.web.WebsiteClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运行时代际的发布与关闭。用实现 {@link AutoCloseable} 的假客户端观察关闭次数，
 * 用临时目录隔离 {@link RestartTrigger} 可能写入的重启请求文件路径。
 */
class RuntimeHolderTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeHolderTest.class);

    @TempDir
    Path tempDir;

    @Test
    void publishMakesNewGenerationVisibleAndReturnsPrevious() {
        RuntimeHolder holder = new RuntimeHolder();
        FakeWebsiteClient firstClient = new FakeWebsiteClient();
        FakeWebsiteClient secondClient = new FakeWebsiteClient();
        RuntimeSnapshot first = snapshot(firstClient);
        RuntimeSnapshot second = snapshot(secondClient);

        assertNull(holder.publish(first), "首次发布没有旧代际");
        assertSame(first, holder.current(), "发布后应能看到新代际");

        assertSame(first, holder.publish(second), "发布应返回被替换的旧代际");
        assertSame(second, holder.current(), "读取方应看到最新代际");
    }

    @Test
    void returnedOldGenerationCanBeClosedByCaller() {
        RuntimeHolder holder = new RuntimeHolder();
        FakeWebsiteClient oldClient = new FakeWebsiteClient();
        FakeWebsiteClient newClient = new FakeWebsiteClient();
        holder.publish(snapshot(oldClient));

        RuntimeSnapshot old = holder.publish(snapshot(newClient));
        old.close();

        assertEquals(1, oldClient.closeCount, "旧代际关闭时应关闭其网站客户端");
        assertEquals(0, newClient.closeCount, "当前代际不受旧代际关闭影响");
        assertSame(holder.current().websiteClient(), newClient);
    }

    @Test
    void closeClosesCurrentGenerationsWebsiteClient() {
        RuntimeHolder holder = new RuntimeHolder();
        FakeWebsiteClient oldClient = new FakeWebsiteClient();
        FakeWebsiteClient currentClient = new FakeWebsiteClient();
        holder.publish(snapshot(oldClient));
        holder.publish(snapshot(currentClient));

        holder.close();

        assertEquals(0, oldClient.closeCount, "持有者只关闭当前代际，旧代际由发布方负责");
        assertEquals(1, currentClient.closeCount, "关闭持有者应关闭当前代际的网站客户端");
        assertNull(holder.current(), "关闭后不再有当前代际");
    }

    @Test
    void repeatedCloseIsSafeAndOnlyClosesOnce() {
        RuntimeHolder holder = new RuntimeHolder();
        FakeWebsiteClient client = new FakeWebsiteClient();
        holder.publish(snapshot(client));

        holder.close();
        assertDoesNotThrow(holder::close, "重复关闭应安全");

        assertEquals(1, client.closeCount, "重复关闭不应重复关闭网站客户端");
    }

    @Test
    void closeBeforePublishIsNoOp() {
        RuntimeHolder holder = new RuntimeHolder();

        assertDoesNotThrow(holder::close);
        assertNull(holder.current());
    }

    @Test
    void nonCloseableWebsiteClientIsIgnoredOnClose() {
        RuntimeHolder holder = new RuntimeHolder();
        holder.publish(new RuntimeSnapshot(
                config(),
                Messages.defaults(),
                new PlainWebsiteClient(),
                restartTrigger(new PlainWebsiteClient())));

        assertDoesNotThrow(holder::close, "非 AutoCloseable 的网站在关闭时直接跳过");
        assertNull(holder.current());
    }

    @Test
    void publishAfterCloseThrowsAndClosesTheRejectedGeneration() {
        RuntimeHolder holder = new RuntimeHolder();
        holder.close();

        FakeWebsiteClient lateClient = new FakeWebsiteClient();
        assertThrows(IllegalStateException.class,
                () -> holder.publish(snapshot(lateClient)),
                "close 之后再 publish 必须抛 IllegalStateException");
        assertEquals(1, lateClient.closeCount,
                "被拒绝的新代际必须立即关闭其网站客户端，且只关闭一次");
        assertNull(holder.current(), "被拒绝的新代际不得成为当前代际");
    }

    @Test
    void interleavedPublishAndCloseLeavesNoCurrentGenerationAndStaysIdempotent() {
        RuntimeHolder holder = new RuntimeHolder();
        FakeWebsiteClient firstClient = new FakeWebsiteClient();
        FakeWebsiteClient secondClient = new FakeWebsiteClient();

        holder.publish(snapshot(firstClient));
        holder.publish(snapshot(secondClient));
        holder.close();

        assertNull(holder.current(), "close 与发布交错后 current 必须为 null");
        assertEquals(1, secondClient.closeCount, "当前代际只关闭一次");
        assertEquals(0, firstClient.closeCount, "旧代际仍由发布方负责关闭");

        FakeWebsiteClient lateClient = new FakeWebsiteClient();
        assertThrows(IllegalStateException.class,
                () -> holder.publish(snapshot(lateClient)),
                "关闭后再发布必须被拒绝");
        assertEquals(1, lateClient.closeCount, "被拒绝的代际只关闭一次");

        assertDoesNotThrow(holder::close, "publish 被拒后重复 close 仍应幂等");
        assertNull(holder.current(), "重复 close 后 current 必须仍为 null");
        assertEquals(1, secondClient.closeCount, "重复 close 不得重复关闭原当前代际");
        assertEquals(1, lateClient.closeCount, "重复 close 不得触碰已被拒绝的代际");
    }

    private RuntimeSnapshot snapshot(WebsiteClient client) {
        return new RuntimeSnapshot(config(), Messages.defaults(), client, restartTrigger(client));
    }

    private RestartTrigger restartTrigger(WebsiteClient client) {
        RestartSettings settings = new RestartSettings(
                tempDir.resolve("restart-request"),
                Duration.ofMinutes(10),
                Duration.ofHours(1),
                2);
        return new RestartTrigger(client, new RestartPolicy(settings), settings,
                Clock.systemUTC(), LOGGER);
    }

    private static PluginConfig config() {
        return PluginConfig.from(Map.of("website", Map.of(
                "base-url", "http://127.0.0.1:1",
                "allow-insecure-http", true)), null);
    }

    /** 假网站客户端：只记录 close 次数，其余操作返回固定结果。 */
    private static final class FakeWebsiteClient implements WebsiteClient, AutoCloseable {

        private int closeCount;

        @Override
        public VerifyResult verify(String code, String gameId, UUID uuid) {
            return VerifyResult.unreachable();
        }

        @Override
        public HealthResult health() {
            return HealthResult.healthy(0);
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    /** 不实现 {@link AutoCloseable} 的网站客户端，用于验证关闭时的类型判断。 */
    private static final class PlainWebsiteClient implements WebsiteClient {

        @Override
        public VerifyResult verify(String code, String gameId, UUID uuid) {
            return VerifyResult.unreachable();
        }

        @Override
        public HealthResult health() {
            return HealthResult.healthy(0);
        }
    }
}