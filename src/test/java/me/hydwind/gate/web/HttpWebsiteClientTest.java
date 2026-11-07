package me.hydwind.gate.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.hydwind.gate.config.PluginConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpWebsiteClientTest {
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void verifiesAccountWithBearerTokenAndPlayerIdentity() {
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        var method = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var contentType = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        server.createContext("/api/plugin/login/verify", exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"ok\":true,\"account\":{\"username\":\"Player_1\",\"nickname\":\"小风\",\"role\":\"player\"}}");
        });

        VerifyResult result = client(300, 300).verify("(｡･ω･｡)", "Player_1", uuid);

        assertEquals("POST", method.get());
        assertEquals("Bearer test-token", authorization.get());
        assertEquals("application/json", contentType.get());
        assertEquals("{\"code\":\"(｡･ω･｡)\",\"gameId\":\"Player_1\",\"uuid\":\"123e4567-e89b-12d3-a456-426614174000\"}",
                body.get());
        assertEquals(VerifyResult.Status.OK, result.status());
        assertEquals("Player_1", result.username());
        assertEquals("小风", result.nickname());
        assertEquals("player", result.role());
    }

    @Test
    void reportsUnauthorizedWhenPluginTokenIsRejected() {
        server.createContext("/api/plugin/login/verify",
                exchange -> respond(exchange, 401, "{\"ok\":false,\"reason\":\"unauthorized\"}"));

        VerifyResult result = client(300, 300)
                .verify("code", "Player_1", UUID.randomUUID());

        assertEquals(VerifyResult.Status.UNAUTHORIZED, result.status());
    }

    @ParameterizedTest
    @CsvSource({
            "invalid_code, INVALID_CODE",
            "expired, EXPIRED",
            "used, USED",
            "game_id_mismatch, GAME_ID_MISMATCH"
    })
    void mapsConflictReasons(String reason, String expectedStatus) {
        server.createContext("/api/plugin/login/verify",
                exchange -> respond(exchange, 409, "{\"ok\":false,\"reason\":\"" + reason + "\"}"));

        VerifyResult result = client(300, 300)
                .verify("code", "Player_1", UUID.randomUUID());

        assertEquals(VerifyResult.Status.valueOf(expectedStatus), result.status());
    }

    @Test
    void conflictCarriesExpectedGameIdWhenPresent() {
        server.createContext("/api/plugin/login/verify", exchange -> respond(exchange, 409,
                "{\"ok\":false,\"reason\":\"game_id_mismatch\",\"expectedGameId\":\"Right_Player\"}"));

        VerifyResult result = client(300, 300)
                .verify("code", "Player_1", UUID.randomUUID());

        assertEquals(VerifyResult.Status.GAME_ID_MISMATCH, result.status());
        assertEquals("Right_Player", result.expectedGameId());
    }

    @Test
    void missingExpectedGameIdLeavesFieldNull() {
        server.createContext("/api/plugin/login/verify",
                exchange -> respond(exchange, 409, "{\"ok\":false,\"reason\":\"game_id_mismatch\"}"));

        VerifyResult result = client(300, 300)
                .verify("code", "Player_1", UUID.randomUUID());

        assertEquals(VerifyResult.Status.GAME_ID_MISMATCH, result.status());
        assertEquals(null, result.expectedGameId());
    }

    @Test
    void mapsRateLimitWithRetryAfterAndAttemptsRemainingFromBody() {
        server.createContext("/api/plugin/login/verify", exchange -> respond(exchange, 429,
                "{\"ok\":false,\"reason\":\"rate_limited\",\"retryAfterMs\":30000,\"attemptsRemaining\":0}"));

        VerifyResult result = client(300, 300)
                .verify("code", "Player_1", UUID.randomUUID());

        assertEquals(VerifyResult.Status.RATE_LIMITED, result.status());
        assertEquals(30000L, result.retryAfterMs());
        assertEquals(0, result.attemptsRemaining());
    }

    @Test
    void fallsBackToRetryAfterHeaderWhenBodyOmitsIt() {
        server.createContext("/api/plugin/login/verify", exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "12");
            respond(exchange, 429, "{\"ok\":false,\"reason\":\"rate_limited\"}");
        });

        VerifyResult result = client(300, 300)
                .verify("code", "Player_1", UUID.randomUUID());

        assertEquals(VerifyResult.Status.RATE_LIMITED, result.status());
        assertEquals(12000L, result.retryAfterMs());
        assertEquals(null, result.attemptsRemaining());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"ok\":false,\"reason\":\"nope\"}",
            "{\"ok\":true}",
            "{\"ok\":true,\"account\":{}}",
            "{\"ok\":true,\"account\":null}",
            "not json at all",
            "[]"
    })
    void successfulStatusWithContractViolationIsContractMismatch(String body) {
        server.createContext("/api/plugin/login/verify", exchange -> respond(exchange, 200, body));

        VerifyResult result = client(300, 300)
                .verify("code", "Player_1", UUID.randomUUID());

        assertEquals(VerifyResult.Status.CONTRACT_MISMATCH, result.status());
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502})
    void serverErrorsAreUnreachable(int status) {
        server.createContext("/api/plugin/login/verify",
                exchange -> respond(exchange, status, "{}"));

        VerifyResult result = client(300, 300)
                .verify("code", "Player_1", UUID.randomUUID());

        assertEquals(VerifyResult.Status.UNREACHABLE, result.status());
    }

    @Test
    void forbiddenEndpointHasExplicitStatusThroughRealHttp() {
        server.createContext("/api/plugin/login/verify",
                exchange -> respond(exchange, 403, "{\"reason\":\"forbidden\"}"));

        VerifyResult result = client(300, 300)
                .verify("code", "Player_1", UUID.randomUUID());

        assertEquals(VerifyResult.Status.FORBIDDEN, result.status());
    }

    @Test
    void missingEndpointHasExplicitStatusThroughRealHttp() {
        server.createContext("/api/plugin/login/verify",
                exchange -> respond(exchange, 404, "{\"reason\":\"not_found\"}"));

        VerifyResult result = client(300, 300)
                .verify("code", "Player_1", UUID.randomUUID());

        assertEquals(VerifyResult.Status.NOT_FOUND, result.status());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"ok\":false,\"reason\":\"something_new\"}",
            "{\"ok\":false}",
            "{\"ok\":false,\"reason\":null}",
            "not json at all",
            "[]"
    })
    void unknownConflictReasonOrMalformedBodyIsContractMismatch(String body) {
        server.createContext("/api/plugin/login/verify", exchange -> respond(exchange, 409, body));

        VerifyResult result = client(300, 300)
                .verify("code", "Player_1", UUID.randomUUID());

        assertEquals(VerifyResult.Status.CONTRACT_MISMATCH, result.status());
    }

    @Test
    void reportsUnreachableWhenVerifyTimesOut() {
        server.createContext("/api/plugin/login/verify", exchange -> {
            sleepQuietly(1500);
            respond(exchange, 200, "{\"ok\":true}");
        });

        long startedAt = System.nanoTime();
        VerifyResult result = client(1000, 200)
                .verify("code", "Player_1", UUID.randomUUID());
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertEquals(VerifyResult.Status.UNREACHABLE, result.status());
        assertTrue(elapsedMillis < 1400, "verify should time out instead of waiting for the server");
    }

    @Test
    void reportsUnreachableWhenConnectionIsRefused() throws IOException {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        HttpWebsiteClient client = new HttpWebsiteClient(
                config("http://127.0.0.1:" + unusedPort, "test-token", 500, 500));

        VerifyResult result = client.verify("code", "Player_1", UUID.randomUUID());

        assertEquals(VerifyResult.Status.UNREACHABLE, result.status());
    }

    @Test
    void probesHealthWithoutToken() {
        server.createContext("/api/health", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            respond(exchange, 200, "{\"ok\":true,\"uptime\":42}");
        });

        HealthResult result = client(300, 300).health();

        assertTrue(result.healthy());
        assertEquals(42L, result.uptimeSeconds());
    }

    @Test
    void reportsUnhealthyWhenHealthReturnsError() {
        server.createContext("/api/health", exchange -> respond(exchange, 500, "boom"));

        HealthResult result = client(300, 300).health();

        assertFalse(result.healthy());
        assertFalse(result.transportFailure(), "网站答了 500，属于契约不匹配而非传输失败");
        assertEquals(0L, result.uptimeSeconds());
    }

    @Test
    void reportsUnhealthyWhenHealthTimesOut() {
        server.createContext("/api/health", exchange -> {
            sleepQuietly(1500);
            respond(exchange, 200, "{\"ok\":true,\"uptime\":42}");
        });

        HealthResult result = client(1000, 200).health();

        assertFalse(result.healthy());
        assertTrue(result.transportFailure(), "超时属于传输层失败，应允许受控重启");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"ok\":false,\"uptime\":9}",
            "{\"ok\":\"maybe\",\"uptime\":9}",
            "{\"uptime\":9}",
            "not json"
    })
    void healthRequiresWellFormedOkTrueBody(String body) {
        server.createContext("/api/health", exchange -> respond(exchange, 200, body));

        HealthResult result = client(300, 300).health();

        assertFalse(result.healthy());
        assertFalse(result.transportFailure(), "200 但健康体不符合契约，不应触发重启");
        assertEquals(0L, result.uptimeSeconds());
    }

    @Test
    void baseUrlTrailingSlashAndHealthPathWithoutLeadingSlashAreNormalized() {
        server.createContext("/health", exchange -> respond(exchange, 200, "{\"ok\":true,\"uptime\":7}"));
        PluginConfig config = PluginConfig.from(Map.<String, Object>of(
                "website", Map.<String, Object>of(
                        "base-url", baseUrl + "/",
                        "token", "test-token",
                        "connect-timeout-millis", 300,
                        "read-timeout-millis", 300,
                        "health-path", "health")), null);

        HealthResult result = new HttpWebsiteClient(config).health();

        assertTrue(result.healthy());
        assertFalse(result.transportFailure());
        assertEquals(7L, result.uptimeSeconds());
    }

    @Test
    void reportsTransportFailureWhenConnectionIsRefusedForHealth() throws IOException {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        HttpWebsiteClient client = new HttpWebsiteClient(
                config("http://127.0.0.1:" + unusedPort, "test-token", 500, 500));

        HealthResult result = client.health();

        assertFalse(result.healthy());
        assertTrue(result.transportFailure(), "连接失败属于传输层失败，应允许受控重启");
    }

    private HttpWebsiteClient client(int connectMillis, int readMillis) {
        return new HttpWebsiteClient(config(baseUrl, "test-token", connectMillis, readMillis));
    }

    private static PluginConfig config(String baseUrl, String token, int connectMillis, int readMillis) {
        return PluginConfig.from(Map.<String, Object>of(
                "website", Map.<String, Object>of(
                        "base-url", baseUrl,
                        "token", token,
                        "connect-timeout-millis", connectMillis,
                        "read-timeout-millis", readMillis)), null);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
