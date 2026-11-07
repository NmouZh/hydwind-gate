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
 * <p>实现 {@link AutoCloseable}：{@link #close()} 只标记本代际失效，后续请求立即
 * 返回契约不匹配。它刻意不调用会阻塞的 {@link HttpClient#close()}，
 * 因为 reload 发生在代理调度线程上；连接池随旧代际失去引用后被 GC 回收。</p>
 */
public final class HttpWebsiteClient implements WebsiteClient, AutoCloseable {

    private static final String VERIFY_PATH = "/api/plugin/login/verify";
    private static final Duration HEALTH_MAX_TIMEOUT = Duration.ofSeconds(2);

    /**
     * 请求在进入网络层之前就失败（URL 非法、请求构造失败等）时使用的可观察探测：
     * 这些异常来自本 Adapter 的配置/构造，而不是传输层，因此不能算“网站不健康”。
     */
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String pluginToken;
    private final Duration readTimeout;
    private final String healthPath;

    public HttpWebsiteClient(PluginConfig config) {
        Objects.requireNonNull(config, "config");
        // 校验已在 WebsiteSettings 的紧凑构造器完成，这里只做规范化。
        this.baseUrl = Urls.stripTrailingSlashes(config.websiteBaseUrl().trim());
        this.pluginToken = config.pluginToken() == null ? "" : config.pluginToken();
        this.readTimeout = Duration.ofMillis(config.readTimeoutMillis());
        this.healthPath = normalizePath(config.healthPath());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.connectTimeoutMillis()))
                .build();
    }

    @Override
    public VerifyResult verify(String code, String gameId, UUID uuid) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(uuid, "uuid");
        if (closed) {
            // 旧代际已被 reload 替换；调用方应改用当前快照。
            return VerifyResult.contractMismatch();
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("code", code);
        payload.addProperty("gameId", gameId);
        payload.addProperty("uuid", uuid.toString());

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri(VERIFY_PATH))
                    .timeout(readTimeout)
                    .header("Authorization", "Bearer " + pluginToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException e) {
            // 非法 URL / 非法 header 属于插件配置问题，不是网站不可达。
            return VerifyResult.contractMismatch();
        }

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
        if (closed) {
            return HealthResult.unhealthyContract();
        }
        Duration timeout = readTimeout.compareTo(HEALTH_MAX_TIMEOUT) < 0 ? readTimeout : HEALTH_MAX_TIMEOUT;
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri(healthPath))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
        } catch (RuntimeException e) {
            // 配置导致的构造失败不是网站不健康；用契约不匹配表达，避免触发重启。
            return HealthResult.unhealthyContract();
        }

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

    /** 标记为已关闭。旧代际可能仍被在途请求引用，因此不阻塞关闭流程。 */
    private volatile boolean closed;

    /**
     * 标记客户端关闭，使后续请求立即返回“契约不匹配”而不是伪装成网站不可达。
     *
     * <p>这里不调用 {@link HttpClient#close()}：它会阻塞等待在途请求完成，
     * 而 reload 发生在代理调度线程上，阻塞会拖慢其它调度任务。底层连接池
     * 随客户端的代际被 GC 回收。</p>
     */
    @Override
    public void close() {
        closed = true;
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
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }
}
