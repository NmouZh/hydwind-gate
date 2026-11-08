package me.hydwind.gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.UUID;
import me.hydwind.gate.config.PluginConfig;
import org.junit.jupiter.api.Test;

/**
 * 覆盖 {@link HttpWebsiteClient} 在“已关闭”和“配置构造失败”两种非传输层故障下的行为。
 *
 * <p>这两种情况都必须报告 {@code CONTRACT_MISMATCH} / {@code transportFailure=false}：
 * 若伪装成传输层失败，{@link RestartTrigger} 会误以为网站不可达并触发假重启。</p>
 */
class HttpWebsiteClientCloseTest {

    @Test
    void closedClientReportsContractMismatchInsteadOfTransportFailure() {
        HttpWebsiteClient client = new HttpWebsiteClient(config("http://127.0.0.1:1"));
        client.close();

        VerifyResult verify = client.verify("code", "Player_1", UUID.randomUUID());
        assertEquals(VerifyResult.Status.CONTRACT_MISMATCH, verify.status(),
                "关闭后的旧代际不应继续发起校验，也不能报告网站不可达");

        HealthResult health = client.health();
        assertFalse(health.healthy(), "关闭后的客户端不应报告健康");
        assertFalse(health.transportFailure(),
                "关闭不是传输层失败，否则会触发假重启");
        assertEquals(0L, health.uptimeSeconds());
    }

    @Test
    void unusableBaseUrlIsRejectedAtLoadTimeInsteadOfLookingLikeAContractMismatch() {
        // 缺少主机的 base-url 无法构造任何请求；若允许它通过加载，
        // 每次校验都会变成“契约不匹配”，看起来像网站问题。必须在配置层拒绝。
        assertThrows(IllegalArgumentException.class, () -> config("http:///"),
                "缺少主机的 base-url 应在加载时失败");

        // 客户端自身仍保留防御：即使被人为构造成无效请求，也不能报成传输层失败。
        HttpWebsiteClient client = new HttpWebsiteClient(
                PluginConfig.from(Map.<String, Object>of(
                        "website", Map.<String, Object>of(
                                "base-url", "http://127.0.0.1:1",
                                "allow-insecure-http", true,
                                "token", "test-token")), null));
        client.close();
        HealthResult health = client.health();
        assertFalse(health.transportFailure(), "关闭不是传输层失败");
    }

    private static PluginConfig config(String baseUrl) {
        return PluginConfig.from(Map.<String, Object>of(
                "website", Map.<String, Object>of(
                        "base-url", baseUrl,
                        "allow-insecure-http", true,
                        "token", "test-token",
                        "connect-timeout-millis", 300,
                        "read-timeout-millis", 300)), null);
    }
}