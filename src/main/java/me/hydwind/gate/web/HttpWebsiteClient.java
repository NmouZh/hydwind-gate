package me.hydwind.gate.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.hydwind.gate.config.PluginConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    /** 网站响应上限：一次校验/探活不可能需要更多，超出即视为契约错误。 */
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    /** Retry-After 上限，防止恶意值把玩家的等待时间拉到几小时。 */
    private static final long MAX_RETRY_AFTER_MS = Duration.ofHours(1).toMillis();
    private static final Logger logger = LoggerFactory.getLogger(HttpWebsiteClient.class);

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
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String retryAfterHeader = response.headers().firstValue("Retry-After").orElse(null);
            int status = response.statusCode();

            // 先按状态码分类：5xx/401/403/404/3xx 不需要 body，避免一个超大
            // 错误页把 5xx 误报成契约错误、把 429 的限流文案丢掉。
            if (status >= 500) {
                closeBody(response);
                return VerifyResult.unreachable();
            }
            if (status == 401 || status == 403 || status == 404) {
                closeBody(response);
                return mapVerifyResponse(status, "", retryAfterHeader);
            }
            if (status >= 300 && status < 400) {
                closeBody(response);
                logger.warn("Website verify returned redirect {}; check website.base-url scheme/host.",
                        status);
                return VerifyResult.contractMismatch();
            }

            String body;
            try {
                body = readBody(response, readTimeout);
            } catch (ResponseTooLargeException e) {
                logger.warn("Website verify response exceeded {} bytes; treating as contract mismatch.",
                        MAX_RESPONSE_BYTES);
                return status == 429
                        ? VerifyResult.rateLimited(null, null)
                        : VerifyResult.contractMismatch();
            }
            return mapVerifyResponse(status, body, retryAfterHeader);
        } catch (SSLException e) {
            // 证书/握手问题重启网站也不能修复；按配置错误处理，不触发重启。
            logger.warn("Website TLS handshake failed: {}", e.toString());
            return VerifyResult.contractMismatch();
        } catch (IOException e) {
            // reload/关闭会有序 shutdown 客户端；本地取消不是“网站不可达”，不能触发重启。
            return closed ? VerifyResult.contractMismatch() : VerifyResult.unreachable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return closed ? VerifyResult.contractMismatch() : VerifyResult.unreachable();
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
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status != 200) {
                closeBody(response);
                if (status >= 300 && status < 400) {
                    logger.warn("Website health returned redirect {}; check website.base-url scheme/host.",
                            status);
                }
                return HealthResult.unhealthyContract();
            }

            String body;
            try {
                body = readBody(response, timeout);
            } catch (ResponseTooLargeException e) {
                logger.warn("Website health response exceeded {} bytes; treating as contract mismatch.",
                        MAX_RESPONSE_BYTES);
                return HealthResult.unhealthyContract();
            }
            return mapHealthResponse(status, body);
        } catch (SSLException e) {
            logger.warn("Website TLS handshake failed during health check: {}", e.toString());
            return HealthResult.unhealthyContract();
        } catch (IOException e) {
            return closed ? HealthResult.unhealthyContract() : HealthResult.unhealthy();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return closed ? HealthResult.unhealthyContract() : HealthResult.unhealthy();
        }
    }

    /** 标记为已关闭。旧代际可能仍被在途请求引用，因此不阻塞关闭流程。 */
    private volatile boolean closed;

    /**
     * 读取响应体：限制大小并给整个 body 读取加截止时间。
     *
     * <p>{@link HttpRequest#timeout} 只覆盖响应头阶段（JDK-8258397）；网站发完
     * 响应头后停住时，裸 InputStream 会无限阻塞并占满虚拟线程/信号量。
     * 看门狗线程到点关闭流，让 read 抛 IOException 退出。</p>
     */
    private String readBody(HttpResponse<InputStream> response, Duration timeout) throws IOException {
        InputStream input = response.body();
        if (input == null) throw new ResponseTooLargeException();

        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(Math.max(1L, timeout.toMillis()));
                input.close();
            } catch (InterruptedException ignored) {
                // 正常读取完成后由主线程 interrupt。
            } catch (IOException ignored) {
                // 流已经被主线程关闭。
            }
        });

        try (input) {
            long declared;
            try {
                declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            } catch (RuntimeException malformed) {
                // 非法 Content-Length 属于契约错误，不能让 NumberFormatException 逃逸成“不可达”。
                throw new ResponseTooLargeException();
            }
            if (declared > MAX_RESPONSE_BYTES) {
                throw new ResponseTooLargeException();
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    declared > 0 ? (int) Math.min(declared, 8192L) : 8192);
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new ResponseTooLargeException();
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        } finally {
            watchdog.interrupt();
        }
    }

    private static void closeBody(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException ignored) {
            // 丢弃响应体时的关闭失败不影响分类结果。
        }
    }

    private static final class ResponseTooLargeException extends IOException {
    }

    /**
     * 标记客户端关闭，使后续请求立即返回“契约不匹配”而不是伪装成网站不可达。
     *
     * <p>用 {@code shutdown()} 而不是 {@code shutdownNow()}：reload 时允许在途请求
     * 完成，避免把本地取消误报成网站不可达；关闭后新请求立即返回契约不匹配。</p>
     */
    @Override
    public void close() {
        closed = true;
        try {
            httpClient.shutdown();
        } catch (RuntimeException exception) {
            // 关闭失败不应影响 reload；旧代际最终会被 GC 回收。
            logger.debug("HttpClient shutdown failed: {}", exception.toString());
        }
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
            case 301, 302, 303, 307, 308 -> {
                // 通常是 base-url 还停留在 http:// 而网站已跳到 https://；
                // 把状态码写进日志，避免只看到“契约不匹配”。
                logger.warn("Website verify returned redirect {}; check website.base-url scheme/host.",
                        statusCode);
                yield VerifyResult.contractMismatch();
            }
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
            if (statusCode >= 300 && statusCode < 400) {
                logger.warn("Website health returned redirect {}; check website.base-url scheme/host.",
                        statusCode);
            }
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
        } catch (RuntimeException | StackOverflowError e) {
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
        } catch (RuntimeException | StackOverflowError e) {
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
        } catch (RuntimeException | StackOverflowError e) {
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
        } catch (RuntimeException | StackOverflowError ignored) {
            // 429 本身已经足够表达限流；响应体格式异常时退回通用文案。
        }
        if (retryAfterMs == null) {
            retryAfterMs = parseRetryAfterHeader(retryAfterHeader);
        } else {
            retryAfterMs = Math.max(0L, Math.min(retryAfterMs, MAX_RETRY_AFTER_MS));
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
            if (seconds < 0) return null;
            try {
                return Math.min(Math.multiplyExact(seconds, 1000L), MAX_RETRY_AFTER_MS);
            } catch (ArithmeticException overflow) {
                return MAX_RETRY_AFTER_MS;
            }
        } catch (NumberFormatException e) {
            // HTTP-date 形式当前不解析；返回 null 由调用方使用通用限流文案。
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
