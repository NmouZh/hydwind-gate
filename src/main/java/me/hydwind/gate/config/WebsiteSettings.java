package me.hydwind.gate.config;

import java.net.URI;
import java.util.Objects;

/**
 * 网站连接与鉴权设置（{@code website} 配置段）。
 *
 * <p>构造器负责本段的全部取值校验；配置非法时直接抛出异常，由加载方决定
 * 是启动失败还是热重载失败，不再由 HTTP 客户端等下游组件静默兜底。</p>
 *
 * @param baseUrl              网站根地址，不能为空
 * @param token                插件令牌；允许空字符串，缺失时由插件记录告警
 * @param connectTimeoutMillis 连接超时（毫秒），必须为正
 * @param readTimeoutMillis    读取超时（毫秒），必须为正
 * @param healthPath           健康检查路径，不能为空
 */
public record WebsiteSettings(
        String baseUrl,
        String token,
        int connectTimeoutMillis,
        int readTimeoutMillis,
        String healthPath) {

    public WebsiteSettings {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(healthPath, "healthPath");
        if (baseUrl.isBlank()) {
            throw new IllegalArgumentException("website.base-url must not be blank");
        }
        // 语法错误必须在加载时失败：否则每次请求都会构造失败并被归类为契约不匹配，
        // 让一个配置笔误看起来像网站故障。URI.create 只做语法校验，不发请求。
        URI baseUri;
        try {
            baseUri = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "website.base-url is not a valid URI: " + baseUrl, exception);
        }
        String scheme = baseUri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(
                    "website.base-url must use http or https: " + baseUrl);
        }
        if (connectTimeoutMillis <= 0) {
            throw new IllegalArgumentException("website.connect-timeout-millis must be positive");
        }
        if (readTimeoutMillis <= 0) {
            throw new IllegalArgumentException("website.read-timeout-millis must be positive");
        }
        if (healthPath.isBlank()) {
            throw new IllegalArgumentException("website.health-path must not be blank");
        }
    }
}
