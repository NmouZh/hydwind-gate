package me.hydwind.gate.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import me.hydwind.gate.auth.LoginSession;
import me.hydwind.gate.web.HealthResult;
import me.hydwind.gate.web.RestartPolicy;
import org.junit.jupiter.api.Test;

/**
 * {@code /hydwind status} 的统计快照。只依赖会话快照、健康结果和重启策略，
 * 不访问 Velocity 或网站；文案渲染由 {@code AdminStatusRendererTest} 覆盖。
 */
class AdminStatusTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void countsLimboPendingAuthenticatedAndDisconnectedGrace() {
        List<LoginSession> sessions = List.of(
                session(1, LoginSession.Status.PENDING, null),
                session(2, LoginSession.Status.VERIFYING, null),
                session(3, LoginSession.Status.AUTHENTICATED, null),
                session(4, LoginSession.Status.AUTHENTICATED, NOW.minusSeconds(30)));

        AdminStatus status = AdminStatus.from(sessions, List.of("A", "B", "C"),
                HealthResult.healthy(42), freshPolicy(), NOW);

        assertEquals(3, status.limboPlayers());
        assertEquals(2, status.pending());
        assertEquals(2, status.authenticated());
        assertEquals(1, status.grace());
        assertTrue(status.websiteHealthy());
        assertEquals(42, status.uptimeSeconds());
        assertEquals(2, status.restartRemaining());
        assertTrue(status.nextAllowed().isEmpty());
    }

    @Test
    void limboPlayerCountComesFromTheNameSnapshot() {
        AdminStatus status = AdminStatus.from(List.of(), List.of("Bob", "Alice"),
                HealthResult.healthy(1), freshPolicy(), NOW);

        assertEquals(2, status.limboPlayers());
        assertEquals(List.of("Bob", "Alice"), status.limboPlayerNames());
    }

    @Test
    void missingHealthResultIsTreatedAsUnhealthy() {
        AdminStatus status = AdminStatus.from(List.of(), List.of(), null, freshPolicy(), NOW);

        assertFalse(status.websiteHealthy());
        assertEquals(0, status.uptimeSeconds());
    }

    @Test
    void exhaustedQuotaExposesTheNextAllowedTime() {
        RestartPolicy policy = freshPolicy();
        assertTrue(policy.tryAcquire(NOW));

        AdminStatus status = AdminStatus.from(List.of(), List.of(), HealthResult.unhealthy(),
                policy, NOW);

        assertEquals(0, status.restartRemaining());
        assertTrue(status.nextAllowed().isPresent());
    }

    private static RestartPolicy freshPolicy() {
        return new RestartPolicy(Duration.ofMinutes(10), Duration.ofHours(1), 2);
    }

    private static LoginSession session(int id, LoginSession.Status status, Instant disconnectedAt) {
        UUID uuid = new UUID(0L, id);
        return new LoginSession(uuid, "Player" + id, "192.0.2.1", status, NOW, NOW,
                disconnectedAt, NOW.plusSeconds(300), 0L);
    }
}
