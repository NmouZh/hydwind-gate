package me.hydwind.gate.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.hydwind.gate.config.PluginConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * 基于 {@link HttpClient} 的 {@link WebsiteClient} 实现。
 *
 * <p>网络故障、超时映射为 {@link VerifyResult.Status#UNREACHABLE} /
 * {@link HealthResult#unhealthy()}（传输层失败，可考虑受控重启）；网站有响应但
 * 内容不符合契约时映射为 {@link VerifyResult.Status#CONTRACT_MISMATCH} /
 * {@link HealthResult#unhealthyContract()}，重启无意义。401/403/404 映射为
 * 明确的失败结果，由调用方按配置文案提示。</p>
 *
 * <p>实现 {@link AutoCloseable}：{@link #close()} 关闭内部 {@link HttpClient}，
 * 释放连接池与相关线程，供配置热重载时关闭旧代际。</p>
 */
public final class HttpWebsiteClient implements WebsiteClient, AutoCloseable {

    private static final String VERIFY_PATH = "/api/plugin/login/verify";
    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 3000;
    private static final int DEFAULT_READ_TIMEOUT_MILLIS = 5000;
    private static final Duration HEALTH_MAX_TIMEOUT = Duration.ofSeconds(2);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String pluginToken;
    private final Duration readTimeout;
    private final String healthPath;

    public HttpWebsiteClient(PluginConfig config) {
        Objects.requireNonNull(config, "config");
        String url = Objects.requireNonNull(config.websiteBaseUrl(), "website base URL").trim();
        if (url.isEmpty()) {
            throw new IllegalArgumentException("website base URL must not be blank");
        }
        this.baseUrl = Urls.stripTrailingSlashes(url);
        this.pluginToken = config.pluginToken() == null ? "" : config.pluginToken();
        this.readTimeout = Duration.ofMillis(positiveOrDefault(
                config.readTimeoutMillis(), DEFAULT_READ_TIMEOUT_MILLIS));
        this.healthPath = normalizePath(config.healthPath());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(positiveOrDefault(
                        config.connectTimeoutMillis(), DEFAULT_CONNECT_TIMEOUT_MILLIS)))
                .build();
    }

    @Override
    public VerifyResult verify(String code, String gameId, UUID uuid) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(uuid, "uuid");

        JsonObject payload = new JsonObject();
        payload.addProperty("code", code);
        payload.addProperty("gameId", gameId);
        payload.addProperty("uuid", uuid.toString());

        HttpRequest request = HttpRequest.newBuilder(uri(VERIFY_PATH))
                .timeout(readTimeout)
                .header("Authorization", "Bearer " + pluginToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String retryAfterHeader = response.headers().firstValue("Retry-After").orElse(null);
            return mapVerifyResponse(response.statusCode(), response.body(), retryAfterHeader);
        } catch (IOException e) {
            return VerifyResult.unreachable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return VerifyResult.unreachable();
        }
    }

    @Override
    public HealthResult health() {
        Duration timeout = readTimeout.compareTo(HEALTH_MAX_TIMEOUT) < 0 ? readTimeout : HEALTH_MAX_TIMEOUT;
        HttpRequest request = HttpRequest.newBuilder(uri(healthPath))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return mapHealthResponse(response.statusCode(), response.body());
        } catch (IOException e) {
            return HealthResult.unhealthy();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HealthResult.unhealthy();
        }
    }

    /** 关闭内部 {@link HttpClient}，不再接受新的校验或探活请求。 */
    @Override
    public void close() {
        httpClient.close();
    }

    static VerifyResult mapVerifyResponse(int statusCode, String body) {
        return mapVerifyResponse(statusCode, body, null);
    }

    /**
     * 把校验响应映射为结果。5xx 视为网站暂时不可用（传输层语义），
     * 其余非预期状态码视为契约不匹配。
     */
    static VerifyResult mapVerifyResponse(int statusCode, String body, String retryAfterHeader) {
        return switch (statusCode) {
            case 200 -> parseSuccess(body);
            case 401 -> VerifyResult.unauthorized();
            case 403 -> VerifyResult.forbidden();
            case 404 -> VerifyResult.notFound();
            case 409 -> parseConflict(body);
            case 429 -> parseRateLimited(body, retryAfterHeader);
            default -> statusCode >= 500
                    ? VerifyResult.unreachable()
                    : VerifyResult.contractMismatch();
        };
    }

    /**
     * 把健康检查响应映射为结果。只有网络层失败才标记 {@code transportFailure}；
     * 网站给出的状态码或健康体不对时只算契约不匹配。
     */
    static HealthResult mapHealthResponse(int statusCode, String body) {
        if (statusCode != 200) {
            return HealthResult.unhealthyContract();
        }
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("ok") || json.get("ok").isJsonNull() || !json.get("ok").getAsBoolean()) {
                return HealthResult.unhealthyContract();
            }
            long uptime = json.has("uptime") && !json.get("uptime").isJsonNull()
                    ? json.get("uptime").getAsLong()
                    : 0L;
            return HealthResult.healthy(uptime);
        } catch (RuntimeException e) {
            return HealthResult.unhealthyContract();
        }
    }

    /** 200 必须带 {@code ok=true} 和至少一个用户名；否则说明网站契约不匹配。 */
    private static VerifyResult parseSuccess(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("ok") || json.get("ok").isJsonNull() || !json.get("ok").getAsBoolean()) {
                return VerifyResult.contractMismatch();
            }
            JsonObject account = json.has("account") && json.get("account").isJsonObject()
                    ? json.getAsJsonObject("account")
                    : null;
            if (account == null || !account.has("username") || account.get("username").isJsonNull()) {
                return VerifyResult.contractMismatch();
            }
            return VerifyResult.ok(
                    account.get("username").getAsString(),
                    optionalString(account, "nickname"),
                    optionalString(account, "role"));
        } catch (RuntimeException e) {
            return VerifyResult.contractMismatch();
        }
    }

    /** 只有已知 reason 才算业务失败；未知 reason 或畸形 body 视为契约不匹配。 */
    private static VerifyResult parseConflict(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("reason") || json.get("reason").isJsonNull()) {
                return VerifyResult.contractMismatch();
            }
            return switch (json.get("reason").getAsString()) {
                case "invalid_code" -> VerifyResult.invalidCode();
                case "expired" -> VerifyResult.expired();
                case "used" -> VerifyResult.used();
                case "game_id_mismatch" ->
                        VerifyResult.gameIdMismatch(optionalString(json, "expectedGameId"));
                default -> VerifyResult.contractMismatch();
            };
        } catch (RuntimeException e) {
            return VerifyResult.contractMismatch();
        }
    }

    private static VerifyResult parseRateLimited(String body, String retryAfterHeader) {
        Long retryAfterMs = null;
        Integer attemptsRemaining = null;
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            retryAfterMs = optionalLong(json, "retryAfterMs");
            attemptsRemaining = optionalInteger(json, "attemptsRemaining");
        } catch (RuntimeException ignored) {
            // 429 本身已经足够表达限流；响应体格式异常时退回通用文案。
        }
        if (retryAfterMs == null) {
            retryAfterMs = parseRetryAfterHeader(retryAfterHeader);
        }
        return VerifyResult.rateLimited(retryAfterMs, attemptsRemaining);
    }

    private static Long optionalLong(JsonObject json, String member) {
        if (!json.has(member) || json.get(member).isJsonNull()) {
            return null;
        }
        try {
            return json.get(member).getAsLong();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer optionalInteger(JsonObject json, String member) {
        if (!json.has(member) || json.get(member).isJsonNull()) {
            return null;
        }
        try {
            return json.get(member).getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Long parseRetryAfterHeader(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds >= 0 ? seconds * 1000 : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String optionalString(JsonObject json, String member) {
        if (!json.has(member) || json.get(member).isJsonNull()) {
            return null;
        }
        return json.get(member).getAsString();
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private static String normalizePath(String path) {
        String trimmed = Objects.requireNonNull(path, "path").trim();
        if (trimmed.isEmpty()) {
            return PluginConfig.DEFAULT_HEALTH_PATH;
        }
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private static int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
