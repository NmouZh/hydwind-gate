package me.hydwind.gate.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.hydwind.gate.config.PluginConfig;
import me.hydwind.gate.util.Throwables;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 {@link HttpClient} 的 {@link WebsiteClient} 实现。
 *
 * <p>网络故障、超时、无法解析的响应映射为
 * {@link VerifyResult.Status#UNREACHABLE} / {@link HealthResult#unhealthy()}；
 * 401/403/404 映射为明确的失败结果，由调用方按配置文案提示。</p>
 *
 * <p>日志会记录完整请求 URL、状态码、耗时与异常 cause 链；登录码、插件令牌
 * 与响应体原文不写入日志。响应体只提取 {@code reason} 字段用于诊断。</p>
 */
public final class HttpWebsiteClient implements WebsiteClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpWebsiteClient.class);
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

        URI endpoint = uri(VERIFY_PATH);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(readTimeout)
                .header("Authorization", "Bearer " + pluginToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        long startedAt = System.nanoTime();
        LOGGER.info("网站校验请求：玩家={}，gameId={}，uuid={}，URL={}，超时={}ms",
                gameId, gameId, uuid, endpoint, readTimeout.toMillis());

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsedMillis = elapsedMillis(startedAt);
            String retryAfterHeader = response.headers().firstValue("Retry-After").orElse(null);
            String responseReason = extractReason(response.body());
            VerifyResult result = mapVerifyResponse(response.statusCode(), response.body(), retryAfterHeader);

            if (result.status() == VerifyResult.Status.RATE_LIMITED) {
                LOGGER.warn("网站校验被限流：玩家={}，HTTP {}，reason={}，retryAfterMs={}，attemptsRemaining={}，耗时={}ms",
                        gameId, response.statusCode(), responseReason, result.retryAfterMs(),
                        result.attemptsRemaining(), elapsedMillis);
            } else {
                LOGGER.info("网站校验响应：玩家={}，HTTP {}，reason={}，结果={}，触发重启检查={}，耗时={}ms",
                        gameId, response.statusCode(), responseReason, result.status(),
                        willTriggerRestart(result), elapsedMillis);
            }
            return result;
        } catch (IOException exception) {
            LOGGER.warn("网站校验请求失败：玩家={}，gameId={}，URL={}，耗时={}ms，异常={}",
                    gameId, gameId, endpoint, elapsedMillis(startedAt),
                    Throwables.describe(exception));
            return VerifyResult.unreachable();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("网站校验请求被中断：玩家={}，URL={}，耗时={}ms，异常={}",
                    gameId, endpoint, elapsedMillis(startedAt), Throwables.describe(exception));
            return VerifyResult.unreachable();
        }
    }

    @Override
    public HealthResult health() {
        Duration timeout = readTimeout.compareTo(HEALTH_MAX_TIMEOUT) < 0 ? readTimeout : HEALTH_MAX_TIMEOUT;
        URI endpoint = uri(healthPath);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();

        long startedAt = System.nanoTime();
        LOGGER.info("网站健康检查请求：URL={}，超时={}ms", endpoint, timeout.toMillis());

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsedMillis = elapsedMillis(startedAt);
            HealthResult result = mapHealthResponse(response.statusCode(), response.body());
            if (result.healthy()) {
                LOGGER.info("网站健康检查通过：URL={}，HTTP {}，uptime={}s，耗时={}ms",
                        endpoint, response.statusCode(), result.uptimeSeconds(), elapsedMillis);
            } else {
                LOGGER.warn("网站健康检查失败：URL={}，HTTP {}，原因={}，耗时={}ms",
                        endpoint, response.statusCode(), healthFailureReason(response.statusCode(), response.body()),
                        elapsedMillis);
            }
            return result;
        } catch (IOException exception) {
            LOGGER.warn("网站健康检查请求异常：URL={}，耗时={}ms，异常={}",
                    endpoint, elapsedMillis(startedAt), Throwables.describe(exception));
            return HealthResult.unhealthy();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("网站健康检查请求被中断：URL={}，耗时={}ms，异常={}",
                    endpoint, elapsedMillis(startedAt), Throwables.describe(exception));
            return HealthResult.unhealthy();
        }
    }

    static VerifyResult mapVerifyResponse(int statusCode, String body) {
        return mapVerifyResponse(statusCode, body, null);
    }

    static VerifyResult mapVerifyResponse(int statusCode, String body, String retryAfterHeader) {
        return switch (statusCode) {
            case 200 -> parseSuccess(body);
            case 401 -> VerifyResult.unauthorized();
            case 403 -> VerifyResult.forbidden();
            case 404 -> VerifyResult.notFound();
            case 409 -> parseConflict(body);
            case 429 -> parseRateLimited(body, retryAfterHeader);
            default -> VerifyResult.unreachable();
        };
    }

    static HealthResult mapHealthResponse(int statusCode, String body) {
        if (statusCode != 200) {
            return HealthResult.unhealthy();
        }
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("ok") || !json.get("ok").getAsBoolean()) {
                return HealthResult.unhealthy();
            }
            long uptime = json.has("uptime") && !json.get("uptime").isJsonNull()
                    ? json.get("uptime").getAsLong()
                    : 0L;
            return HealthResult.healthy(uptime);
        } catch (RuntimeException e) {
            return HealthResult.unhealthy();
        }
    }

    private static VerifyResult parseSuccess(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("ok") || !json.get("ok").getAsBoolean()) {
                return VerifyResult.unreachable();
            }
            JsonObject account = json.getAsJsonObject("account");
            if (account == null || !account.has("username") || account.get("username").isJsonNull()) {
                return VerifyResult.unreachable();
            }
            return VerifyResult.ok(
                    account.get("username").getAsString(),
                    optionalString(account, "nickname"),
                    optionalString(account, "role"));
        } catch (RuntimeException e) {
            return VerifyResult.unreachable();
        }
    }

    private static VerifyResult parseConflict(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("reason") || json.get("reason").isJsonNull()) {
                return VerifyResult.unreachable();
            }
            return switch (json.get("reason").getAsString()) {
                case "invalid_code" -> VerifyResult.invalidCode();
                case "expired" -> VerifyResult.expired();
                case "used" -> VerifyResult.used();
                case "game_id_mismatch" ->
                        VerifyResult.gameIdMismatch(optionalString(json, "expectedGameId"));
                default -> VerifyResult.unreachable();
            };
        } catch (RuntimeException e) {
            return VerifyResult.unreachable();
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

    private static boolean willTriggerRestart(VerifyResult result) {
        return result.status() == VerifyResult.Status.UNREACHABLE;
    }

    private static String extractReason(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("reason") && !json.get("reason").isJsonNull()) {
                return json.get("reason").getAsString();
            }
            if (json.has("ok") && !json.get("ok").isJsonNull()) {
                return json.get("ok").getAsBoolean() ? "ok" : "ok=false";
            }
        } catch (RuntimeException ignored) {
            // 响应体不是预期 JSON 时只记录 HTTP 状态，避免把原文写进日志。
        }
        return "-";
    }

    private static String healthFailureReason(int statusCode, String body) {
        if (statusCode != 200) {
            return "HTTP " + statusCode + "（reason=" + extractReason(body) + "）";
        }
        return "响应体未返回 ok=true（reason=" + extractReason(body) + "）";
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
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
