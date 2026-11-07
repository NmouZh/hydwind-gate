package me.hydwind.gate.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import me.hydwind.gate.auth.LoginSession;
import me.hydwind.gate.ui.AdminMessages;
import me.hydwind.gate.web.HealthResult;
import me.hydwind.gate.web.RestartPolicy;
import org.junit.jupiter.api.Test;

/**
 * {@code /hydwind status} 的统计与文案。只依赖会话快照、健康结果和重启策略，
 * 不访问 Velocity 或网站。
 */
class AdminStatusTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);

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
        assertEquals(true, status.websiteHealthy());
        assertEquals(42, status.uptimeSeconds());
        assertEquals(2, status.restartRemaining());
        assertTrue(status.nextAllowed().isEmpty());
    }

    @Test
    void linesSummarizeEveryStatusField() {
        List<LoginSession> sessions = List.of(
                session(1, LoginSession.Status.PENDING, null),
                session(2, LoginSession.Status.AUTHENTICATED, NOW.minusSeconds(30)));

        AdminStatus status = AdminStatus.from(sessions, List.of("One", "Two"),
                HealthResult.healthy(42), freshPolicy(), NOW);
        List<String> lines = status.lines(AdminMessages.defaults(), "hydwind.admin", TIME);

        assertTrue(lines.contains("Limbo 中: 2"), () -> "缺少 limbo 玩家数: " + lines);
        assertTrue(lines.contains("待认证: 1"), () -> "缺少待认证数: " + lines);
        assertTrue(lines.contains("已认证: 1（其中离线宽限 1）"), () -> "缺少认证/宽限统计: " + lines);
        assertTrue(lines.contains("网站: 健康（uptime 42 秒）"), () -> "缺少网站健康: " + lines);
        assertTrue(lines.contains("重启额度: 2 次可用"), () -> "缺少重启额度: " + lines);
        assertTrue(lines.contains("管理员权限: hydwind.admin"), () -> "缺少权限提示: " + lines);
    }

    @Test
    void unhealthyWebsiteAndExhaustedQuotaShowNextAllowedTime() {
        RestartPolicy policy = freshPolicy();
        assertTrue(policy.tryAcquire(NOW));

        AdminStatus status = AdminStatus.from(List.of(), List.of(), HealthResult.unhealthy(),
                policy, NOW);
        List<String> lines = status.lines(AdminMessages.defaults(), "hydwind.admin", TIME);

        assertTrue(status.nextAllowed().isPresent());
        assertTrue(lines.contains("网站: 不可达"), () -> "缺少不可达提示: " + lines);
        assertTrue(lines.contains("重启额度: 0 次可用（下次允许 00:10）"),
                () -> "缺少下次允许时间: " + lines);
    }

    @Test
    void plainAuthenticatedMessageIsUsedWithoutGraceRecords() {
        AdminStatus status = AdminStatus.from(
                List.of(session(1, LoginSession.Status.AUTHENTICATED, null)),
                List.of(), HealthResult.healthy(1), freshPolicy(), NOW);

        List<String> lines = status.lines(AdminMessages.defaults(), "hydwind.admin", TIME);

        assertTrue(lines.contains("已认证: 1"), () -> "无宽限时不应显示宽限文案: " + lines);
    }

    @Test
    void listsLimboPlayerNamesWhenPresent() {
        AdminStatus status = AdminStatus.from(List.of(), List.of("Bob", "Alice"),
                HealthResult.healthy(1), freshPolicy(), NOW);

        List<String> lines = status.lines(AdminMessages.defaults(), "hydwind.admin", TIME);

        assertEquals(2, status.limboPlayers());
        assertTrue(lines.contains("Limbo 玩家: Alice, Bob"), () -> "缺少 limbo 玩家名: " + lines);
        assertTrue(lines.contains("Limbo 中: 2"), () -> "缺少 limbo 人数: " + lines);
    }

    @Test
    void capsLimboPlayerNamesAtTwentyAndReportsTheTotal() {
        List<String> names = new java.util.ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            names.add("P%02d".formatted(i));
        }

        AdminStatus status = AdminStatus.from(List.of(), names,
                HealthResult.healthy(1), freshPolicy(), NOW);
        List<String> lines = status.lines(AdminMessages.defaults(), "hydwind.admin", TIME);

        String expected = "Limbo 玩家: " + String.join(", ",
                names.subList(0, AdminStatus.MAX_LIMBO_NAMES)) + " 等 25 人";
        assertTrue(lines.contains(expected), () -> "应最多列出 20 个玩家名: " + lines);
    }

    private static RestartPolicy freshPolicy() {
        return new RestartPolicy(Duration.ofMinutes(10), Duration.ofHours(1), 2);
    }

    private static LoginSession session(int id, LoginSession.Status status, Instant disconnectedAt) {
        UUID uuid = new UUID(0L, id);
        return new LoginSession(uuid, "Player" + id, "192.0.2.1", status, NOW, NOW, disconnectedAt);
    }
}