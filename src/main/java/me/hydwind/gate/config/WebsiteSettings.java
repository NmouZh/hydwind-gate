package me.hydwind.gate.config;

import java.net.URI;
import java.util.Map;
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
 * @param allowInsecureHttp    是否允许明文 HTTP；默认 false，仅内网/本地开发显式开启
 */
public record WebsiteSettings(
        String baseUrl,
        String token,
        int connectTimeoutMillis,
        int readTimeoutMillis,
        String healthPath,
        boolean allowInsecureHttp) {

    /** 超时上限：再长也不会等出结果，只会占住线程。 */
    public static final int MAX_TIMEOUT_MILLIS = 60_000;

    /**
     * 兼容测试与旧调用点的 5 参数构造器：不启用 HTTPS 强制。
     * 生产路径统一走 {@link PluginConfig#from(Map, String)} 的 6 参数构造。
     */
    WebsiteSettings(String baseUrl, String token, int connectTimeoutMillis,
                    int readTimeoutMillis, String healthPath) {
        this(baseUrl, token, connectTimeoutMillis, readTimeoutMillis, healthPath, false);
    }

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
        if (scheme.equalsIgnoreCase("http") && !allowInsecureHttp) {
            throw new IllegalArgumentException(
                    "website.base-url must use https; set website.allow-insecure-http: true "
                            + "only for local development or an isolated network");
        }
        // userinfo 会被启动日志原样打印，也可能被误当成认证信息；配置里不支持。
        if (baseUri.getUserInfo() != null) {
            throw new IllegalArgumentException(
                    "website.base-url must not contain userinfo: " + baseUrl);
        }
        if (baseUri.getPort() > 65_535) {
            throw new IllegalArgumentException(
                    "website.base-url has an invalid port: " + baseUrl);
        }
        // 客户端用字符串拼接追加 /api/... 路径；query 或 fragment 会把接口路径并进
        // query，导致所有请求都打到错误的 URL 上。路径本身是允许的。
        if (baseUri.getRawQuery() != null || baseUri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "website.base-url must not contain a query or fragment: " + baseUrl);
        }
        // 没有 host 的 URL（如 "http:///"）语法合法，但所有请求都会在构造阶段失败，
        // 最终被归类为契约不匹配——看起来像网站问题。必须在加载时拒绝。
        if (baseUri.getHost() == null || baseUri.getHost().isBlank()) {
            throw new IllegalArgumentException(
                    "website.base-url must include a host: " + baseUrl);
        }
        if (connectTimeoutMillis <= 0 || connectTimeoutMillis > MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException(
                    "website.connect-timeout-millis must be between 1 and " + MAX_TIMEOUT_MILLIS);
        }
        if (readTimeoutMillis <= 0 || readTimeoutMillis > MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException(
                    "website.read-timeout-millis must be between 1 and " + MAX_TIMEOUT_MILLIS);
        }
        if (healthPath.isBlank()) {
            throw new IllegalArgumentException("website.health-path must not be blank");
        }
        if (healthPath.startsWith("//") || healthPath.contains("://")) {
            throw new IllegalArgumentException(
                    "website.health-path must be a path, not a URL: " + healthPath);
        }
    }
}
