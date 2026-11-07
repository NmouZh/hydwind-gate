package me.hydwind.gate.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.hydwind.gate.auth.AdminService.Outcome;
import me.hydwind.gate.ui.PlayerGateway;
import me.hydwind.gate.web.HealthResult;
import me.hydwind.gate.web.RestartPolicy;
import me.hydwind.gate.web.VerifyResult;
import me.hydwind.gate.web.WebsiteClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminServiceTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final String IP = "192.0.2.1";

    private LoginSessionStore store;
    private FakeWebsiteClient website;
    private FakePlayerGateway gateway;
    private RestartPolicy restartPolicy;
    private MutableClock clock;
    private AdminService service;

    @BeforeEach
    void setUp() {
        store = new LoginSessionStore();
        website = new FakeWebsiteClient();
        gateway = new FakePlayerGateway();
        restartPolicy = new RestartPolicy();
        clock = new MutableClock(START);
        service = new AdminService(store, gateway, website, restartPolicy, clock);
    }

    @Test
    void statusReportsPendingAuthenticatedHealthAndRestartQuota() {
        store.join(PLAYER, "PlayerOne", IP, START);
        store.join(OTHER, "PlayerTwo", IP, START);
        store.beginVerification(OTHER, START);
        store.authenticationSucceeded(OTHER, START);
        store.quit(OTHER, START); // 离线宽限记录

        AdminService.Status status = service.status();

        assertNotNull(status);
        assertEquals(1, status.pending(), "待认证数量应包含 PENDING/VERIFYING");
        assertEquals(1, status.authenticated(), "已认证数量应包含在线玩家");
        assertEquals(1, status.grace(), "离线宽限记录应单独统计");
        assertTrue(status.health().healthy());
        assertEquals(2, status.restartRemaining(), "未触发重启时应显示完整额度");
        assertTrue(status.nextRestartAllowedAt().isEmpty(), "有额度时没有下次允许时间限制");
    }

    @Test
    void statusReportsUnhealthyWebsiteAndExhaustedQuota() {
        website.healthy = false;
        restartPolicy.tryAcquire(START);
        restartPolicy.tryAcquire(START.plus(java.time.Duration.ofMinutes(10)));
        clock.advance(java.time.Duration.ofMinutes(20));

        AdminService.Status status = service.status();

        assertFalse(status.health().healthy());
        assertEquals(0, status.restartRemaining());
        assertTrue(status.nextRestartAllowedAt().isPresent());
    }

    @Test
    void approveMarksPendingPlayerAuthenticatedAndUnfreezes() {
        store.join(PLAYER, "PlayerOne", IP, START);

        AdminService.Result result = service.approve("playerone");

        assertEquals(Outcome.OK, result.outcome());
        assertEquals(LoginSession.Status.AUTHENTICATED, store.find(PLAYER).orElseThrow().status());
        assertEquals(List.of(PLAYER), gateway.unfrozen);
        assertEquals(List.of(PLAYER), gateway.messages);
    }

    @Test
    void approveReportsUnknownPlayer() {
        AdminService.Result result = service.approve("Nobody");

        assertEquals(Outcome.NOT_FOUND, result.outcome());
        assertTrue(gateway.unfrozen.isEmpty());
    }

    @Test
    void approveReportsAlreadyAuthenticated() {
        store.join(PLAYER, "PlayerOne", IP, START);
        store.beginVerification(PLAYER, START);
        store.authenticationSucceeded(PLAYER, START);

        AdminService.Result result = service.approve("PlayerOne");

        assertEquals(Outcome.ALREADY_AUTHENTICATED, result.outcome());
        assertTrue(gateway.unfrozen.isEmpty(), "已认证玩家不应再次解冻");
    }

    @Test
    void kickRemovesSessionAndKicksPlayer() {
        store.join(PLAYER, "PlayerOne", IP, START);

        AdminService.Result result = service.kick("PlayerOne");

        assertEquals(Outcome.OK, result.outcome());
        assertTrue(store.find(PLAYER).isEmpty(), "踢出后应清理会话");
        assertEquals(List.of(PLAYER), gateway.kicked);
    }

    @Test
    void kickReportsUnknownPlayer() {
        AdminService.Result result = service.kick("Nobody");

        assertEquals(Outcome.NOT_FOUND, result.outcome());
        assertTrue(gateway.kicked.isEmpty());
    }

    private static final class FakeWebsiteClient implements WebsiteClient {

        private boolean healthy = true;

        @Override
        public VerifyResult verify(String code, String gameId, UUID uuid) {
            return VerifyResult.unreachable();
        }

        @Override
        public HealthResult health() {
            return healthy ? HealthResult.healthy(7) : HealthResult.unhealthy();
        }
    }

    private static final class FakePlayerGateway implements PlayerGateway {

        private final List<UUID> unfrozen = new ArrayList<>();
        private final List<UUID> kicked = new ArrayList<>();
        private final List<UUID> messages = new ArrayList<>();

        @Override
        public void sendMessage(UUID playerId, String message) {
            messages.add(playerId);
        }

        @Override
        public void kick(UUID playerId, String reason) {
            kicked.add(playerId);
        }

        @Override
        public void freeze(UUID playerId) {
        }

        @Override
        public void unfreeze(UUID playerId) {
            unfrozen.add(playerId);
        }

        @Override
        public void showLoginDialog(UUID playerId) {
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(java.time.Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}