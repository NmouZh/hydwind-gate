package me.hydwind.gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
    void unusableBaseUrlIsContractMismatchNotTransportFailure() {
        // 语法非法的 base-url（如 "http://[invalid"）会在配置加载时被 WebsiteSettings
        // 拒绝；这里用一个能通过配置校验、但无法构成 HTTP 请求的地址（缺少主机）
        // 覆盖 HttpWebsiteClient 自己的防御路径。
        HttpWebsiteClient client = new HttpWebsiteClient(config("http:///"));

        VerifyResult verify = client.verify("code", "Player_1", UUID.randomUUID());
        assertEquals(VerifyResult.Status.CONTRACT_MISMATCH, verify.status(),
                "无法构造请求的 base-url 属于插件配置问题，不是网站不可达");

        HealthResult health = client.health();
        assertFalse(health.healthy(), "不可用的 base-url 下不能报告网站健康");
        assertFalse(health.transportFailure(),
                "请求构造失败不能触发受控重启");
        assertEquals(0L, health.uptimeSeconds());
    }

    private static PluginConfig config(String baseUrl) {
        return PluginConfig.from(Map.<String, Object>of(
                "website", Map.<String, Object>of(
                        "base-url", baseUrl,
                        "token", "test-token",
                        "connect-timeout-millis", 300,
                        "read-timeout-millis", 300)), null);
    }
}