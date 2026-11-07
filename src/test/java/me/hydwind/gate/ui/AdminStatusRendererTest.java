package me.hydwind.gate.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.hydwind.gate.auth.LoginSession;
import me.hydwind.gate.command.AdminStatus;
import me.hydwind.gate.web.HealthResult;
import me.hydwind.gate.web.RestartPolicy;
import org.junit.jupiter.api.Test;

/**
 * {@code /hydwind status} 的文案渲染。快照统计由 {@code AdminStatusTest} 覆盖，
 * 这里只验证发出去的每一行。
 */
class AdminStatusRendererTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);

    @Test
    void rendersEveryStatusField() {
        List<LoginSession> sessions = List.of(
                session(1, LoginSession.Status.PENDING, null),
                session(2, LoginSession.Status.AUTHENTICATED, NOW.minusSeconds(30)));

        AdminStatus status = AdminStatus.from(sessions, List.of("One", "Two"),
                HealthResult.healthy(42), freshPolicy(), NOW);
        List<String> lines = render(status);

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
        List<String> lines = render(status);

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

        List<String> lines = render(status);

        assertTrue(lines.contains("已认证: 1"), () -> "无宽限时不应显示宽限文案: " + lines);
    }

    @Test
    void listsLimboPlayerNamesSorted() {
        AdminStatus status = AdminStatus.from(List.of(), List.of("Bob", "Alice"),
                HealthResult.healthy(1), freshPolicy(), NOW);

        List<String> lines = render(status);

        assertTrue(lines.contains("Limbo 玩家: Alice, Bob"), () -> "缺少排序后的 limbo 玩家名: " + lines);
        assertTrue(lines.contains("Limbo 中: 2"), () -> "缺少 limbo 人数: " + lines);
    }

    @Test
    void capsLimboPlayerNamesAtTwentyAndReportsTheTotal() {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            names.add("P%02d".formatted(i));
        }

        AdminStatus status = AdminStatus.from(List.of(), names,
                HealthResult.healthy(1), freshPolicy(), NOW);
        List<String> lines = render(status);

        String expected = "Limbo 玩家: " + String.join(", ",
                names.subList(0, AdminStatusRenderer.MAX_LIMBO_NAMES)) + " 等 25 人";
        assertTrue(lines.contains(expected), () -> "应最多列出 20 个玩家名: " + lines);
    }

    @Test
    void omitsTheNamesLineWhenThereAreNoLimboPlayers() {
        AdminStatus status = AdminStatus.from(List.of(), List.of(),
                HealthResult.healthy(1), freshPolicy(), NOW);

        List<String> lines = render(status);

        assertTrue(lines.stream().noneMatch(line -> line.startsWith("Limbo 玩家:")),
                () -> "没有玩家时不应输出玩家名行: " + lines);
    }

    private static List<String> render(AdminStatus status) {
        return AdminStatusRenderer.render(status, AdminMessages.defaults(), "hydwind.admin", TIME);
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