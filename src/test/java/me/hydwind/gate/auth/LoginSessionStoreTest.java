package me.hydwind.gate.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoginSessionStoreTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final String IP = "192.0.2.1";

    @Test
    void joinStartsPendingAndSuccessfulVerificationAuthenticates() {
        LoginSessionStore store = new LoginSessionStore(Duration.ofMinutes(5));

        LoginSession pending = store.join(PLAYER, "PlayerOne", IP, START);
        assertEquals(LoginSession.Status.PENDING, pending.status());
        assertEquals(PLAYER, pending.uuid());
        assertEquals("PlayerOne", pending.gameId());
        assertEquals(IP, pending.ip());
        assertEquals(START, pending.enteredAt());
        assertEquals(START, pending.lastActivityAt());

        assertTrue(store.beginVerification(PLAYER, START.plusSeconds(30)));
        assertEquals(LoginSession.Status.VERIFYING, store.find(PLAYER).orElseThrow().status());
        assertEquals(START.plusSeconds(30), store.find(PLAYER).orElseThrow().lastActivityAt());
        assertFalse(store.beginVerification(PLAYER, START.plusSeconds(31)));

        assertTrue(store.authenticationSucceeded(PLAYER, START.plusSeconds(35)));
        assertEquals(LoginSession.Status.AUTHENTICATED, store.find(PLAYER).orElseThrow().status());
        assertEquals(START.plusSeconds(35), store.find(PLAYER).orElseThrow().lastActivityAt());
        assertFalse(store.beginVerification(PLAYER, START.plusSeconds(36)));
    }

    @Test
    void failedVerificationCanRetryButStaleResultCannotUndoSuccess() {
        LoginSessionStore store = new LoginSessionStore(Duration.ofMinutes(5));
        store.join(PLAYER, "PlayerOne", IP, START);
        assertFalse(store.authenticationSucceeded(PLAYER, START.plusSeconds(1)));
        assertTrue(store.beginVerification(PLAYER, START.plusSeconds(2)));
        assertTrue(store.authenticationFailed(PLAYER, START.plusSeconds(3)));
        assertEquals(LoginSession.Status.PENDING, store.find(PLAYER).orElseThrow().status());
        assertTrue(store.beginVerification(PLAYER, START.plusSeconds(4)));
        assertTrue(store.authenticationSucceeded(PLAYER, START.plusSeconds(5)));
        assertFalse(store.authenticationFailed(PLAYER, START.plusSeconds(6)));
        assertEquals(LoginSession.Status.AUTHENTICATED, store.find(PLAYER).orElseThrow().status());
    }

    @Test
    void pendingAndVerifyingSessionsTimeOutFromJoinTime() {
        LoginSessionStore store = new LoginSessionStore(Duration.ofMinutes(5));
        store.join(PLAYER, "PlayerOne", IP, START);
        Duration limit = Duration.ofMinutes(5);
        assertFalse(store.timeOut(PLAYER, START.plus(limit).minusNanos(1), limit));
        assertTrue(store.beginVerification(PLAYER, START.plusSeconds(60)));
        assertTrue(store.timeOut(PLAYER, START.plus(limit), limit));
        assertEquals(LoginSession.Status.TIMED_OUT, store.find(PLAYER).orElseThrow().status());
        assertFalse(store.authenticationSucceeded(PLAYER, START.plus(limit).plusSeconds(1)));
        assertFalse(store.beginVerification(PLAYER, START.plus(limit).plusSeconds(2)));
    }

    @Test
    void reconnectWithinFiveMinutesFromAuthenticatedQuitKeepsAuthentication() {
        LoginSessionStore store = new LoginSessionStore(Duration.ofMinutes(5));
        store.join(PLAYER, "PlayerOne", IP, START);
        store.beginVerification(PLAYER, START.plusSeconds(1));
        store.authenticationSucceeded(PLAYER, START.plusSeconds(2));
        Instant quitAt = START.plusSeconds(10);
        store.quit(PLAYER, quitAt);

        LoginSession resumed = store.join(PLAYER, "PlayerOne", IP, quitAt.plus(Duration.ofMinutes(5)).minusNanos(1));
        assertEquals(LoginSession.Status.AUTHENTICATED, resumed.status());
        assertEquals(START, resumed.enteredAt());
        assertEquals(quitAt.plus(Duration.ofMinutes(5)).minusNanos(1), resumed.lastActivityAt());
    }

    @Test
    void reconnectAtGraceDeadlineOrWithDifferentIpRequiresLogin() {
        LoginSessionStore store = new LoginSessionStore(Duration.ofMinutes(5));
        store.join(PLAYER, "PlayerOne", IP, START);
        store.beginVerification(PLAYER, START.plusSeconds(1));
        store.authenticationSucceeded(PLAYER, START.plusSeconds(2));
        store.quit(PLAYER, START.plusSeconds(10));

        LoginSession differentIp = store.join(PLAYER, "PlayerOne", "198.51.100.7", START.plusSeconds(11));
        assertEquals(LoginSession.Status.PENDING, differentIp.status());
        assertEquals("198.51.100.7", differentIp.ip());

        store.beginVerification(PLAYER, START.plusSeconds(12));
        store.authenticationSucceeded(PLAYER, START.plusSeconds(13));
        store.quit(PLAYER, START.plusSeconds(20));
        LoginSession expired = store.join(PLAYER, "PlayerOne", "198.51.100.7", START.plusSeconds(320));
        assertEquals(LoginSession.Status.PENDING, expired.status());
        assertEquals(START.plusSeconds(320), expired.enteredAt());
    }

    @Test
    void unauthenticatedQuitAndFreshStoreNeverGrantGrace() {
        LoginSessionStore store = new LoginSessionStore(Duration.ofMinutes(5));
        store.join(PLAYER, "PlayerOne", IP, START);
        store.quit(PLAYER, START.plusSeconds(10));
        assertEquals(LoginSession.Status.PENDING, store.join(PLAYER, "PlayerOne", IP, START.plusSeconds(11)).status());

        store.beginVerification(PLAYER, START.plusSeconds(12));
        store.authenticationSucceeded(PLAYER, START.plusSeconds(13));
        store.quit(PLAYER, START.plusSeconds(14));
        assertEquals(LoginSession.Status.PENDING,
                new LoginSessionStore(Duration.ofMinutes(5)).join(PLAYER, "PlayerOne", IP, START.plusSeconds(15)).status());
        assertTrue(store.find(PLAYER).isPresent());
    }

    @Test
    void duplicateJoinForAnOnlineAuthenticatedPlayerOnlyRefreshesActivity() {
        LoginSessionStore store = new LoginSessionStore(Duration.ofMinutes(5));
        store.join(PLAYER, "PlayerOne", IP, START);
        store.beginVerification(PLAYER, START.plusSeconds(1));
        store.authenticationSucceeded(PLAYER, START.plusSeconds(2));

        LoginSession duplicate = store.join(PLAYER, "PlayerOne", IP, START.plusSeconds(30));

        assertEquals(LoginSession.Status.AUTHENTICATED, duplicate.status());
        assertEquals(START, duplicate.enteredAt());
        assertEquals(START.plusSeconds(30), duplicate.lastActivityAt());
        assertEquals(null, duplicate.disconnectedAt());
    }

    @Test
    void adminForceAuthenticateWorksOnUnauthenticatedSessionsOnly() {
        LoginSessionStore store = new LoginSessionStore(Duration.ofMinutes(5));
        store.join(PLAYER, "PlayerOne", IP, START);

        assertTrue(store.forceAuthenticate(PLAYER, START.plusSeconds(1)));
        assertEquals(LoginSession.Status.AUTHENTICATED, store.find(PLAYER).orElseThrow().status());
        assertFalse(store.forceAuthenticate(PLAYER, START.plusSeconds(2)),
                "已认证会话不应被重复放行");
        assertFalse(store.forceAuthenticate(SECOND, START.plusSeconds(3)),
                "不存在的会话不能放行");

        store.join(SECOND, "PlayerTwo", IP, START);
        store.beginVerification(SECOND, START.plusSeconds(4));
        assertTrue(store.forceAuthenticate(SECOND, START.plusSeconds(5)),
                "管理员可以在校验中直接放行");
        assertEquals(LoginSession.Status.AUTHENTICATED, store.find(SECOND).orElseThrow().status());
    }

    @Test
    void adminRemoveDeletesTheSessionAndItsGraceRecord() {
        LoginSessionStore store = new LoginSessionStore(Duration.ofMinutes(5));
        store.join(PLAYER, "PlayerOne", IP, START);
        store.beginVerification(PLAYER, START.plusSeconds(1));
        store.authenticationSucceeded(PLAYER, START.plusSeconds(2));
        store.quit(PLAYER, START.plusSeconds(3));

        assertTrue(store.remove(PLAYER));
        assertTrue(store.find(PLAYER).isEmpty());
        assertFalse(store.remove(PLAYER), "重复删除返回 false");

        LoginSession rejoined = store.join(PLAYER, "PlayerOne", IP, START.plusSeconds(4));
        assertEquals(LoginSession.Status.PENDING, rejoined.status(), "踢出后宽限记录必须失效");
    }

    @Test
    void findByGameIdIgnoresCaseAndReturnsEmptyWhenMissing() {
        LoginSessionStore store = new LoginSessionStore(Duration.ofMinutes(5));
        store.join(PLAYER, "PlayerOne", IP, START);

        assertEquals(PLAYER, store.findByGameId("playerONE").orElseThrow().uuid());
        assertEquals(PLAYER, store.findByGameId("PlayerOne").orElseThrow().uuid());
        assertTrue(store.findByGameId("SomeoneElse").isEmpty());
    }

    @Test
    void snapshotIsAnImmutableCopy() {
        LoginSessionStore store = new LoginSessionStore(Duration.ofMinutes(5));
        store.join(PLAYER, "PlayerOne", IP, START);

        List<LoginSession> snapshot = store.snapshot();
        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());

        store.join(SECOND, "PlayerTwo", IP, START);
        assertEquals(1, snapshot.size(), "快照不应随后续写入变化");
        assertEquals(2, store.snapshot().size());
    }

    @Test
    void timeOutAllOnlyMarksAwaitingSessionsPastTheirOwnDeadline() {
        LoginSessionStore store = new LoginSessionStore(Duration.ofMinutes(5));
        Duration limit = Duration.ofMinutes(5);
        store.join(PLAYER, "PlayerOne", IP, START);
        store.join(SECOND, "PlayerTwo", IP, START.plusSeconds(60));
        store.beginVerification(SECOND, START.plusSeconds(61));
        store.join(THIRD, "PlayerThree", IP, START);
        store.beginVerification(THIRD, START.plusSeconds(1));
        store.authenticationSucceeded(THIRD, START.plusSeconds(2));

        List<LoginSession> firstSweep = store.timeOutAll(START.plus(limit), limit);

        assertEquals(1, firstSweep.size());
        assertEquals(PLAYER, firstSweep.getFirst().uuid());
        assertEquals(LoginSession.Status.TIMED_OUT, store.find(PLAYER).orElseThrow().status());
        assertEquals(LoginSession.Status.VERIFYING, store.find(SECOND).orElseThrow().status());
        assertEquals(LoginSession.Status.AUTHENTICATED, store.find(THIRD).orElseThrow().status(),
                "已认证会话不应被超时扫描影响");

        List<LoginSession> secondSweep = store.timeOutAll(START.plusSeconds(60).plus(limit), limit);

        assertEquals(1, secondSweep.size());
        assertEquals(SECOND, secondSweep.getFirst().uuid());
        assertEquals(LoginSession.Status.TIMED_OUT, store.find(SECOND).orElseThrow().status());
        assertTrue(store.timeOutAll(START.plusSeconds(60).plus(limit), limit).isEmpty(),
                "同一会话不应重复返回");
    }

    @Test
    void expireGraceRemovesOnlyDisconnectedAuthenticatedSessionsPastTheDeadline() {
        LoginSessionStore store = new LoginSessionStore(Duration.ofSeconds(30));
        store.join(PLAYER, "PlayerOne", IP, START);
        store.beginVerification(PLAYER, START.plusSeconds(1));
        store.authenticationSucceeded(PLAYER, START.plusSeconds(2));
        store.quit(PLAYER, START.plusSeconds(10));

        store.join(SECOND, "PlayerTwo", IP, START);
        store.beginVerification(SECOND, START.plusSeconds(1));
        store.authenticationSucceeded(SECOND, START.plusSeconds(2));

        store.join(THIRD, "PlayerThree", IP, START);

        assertEquals(0, store.expireGrace(START.plusSeconds(39)), "宽限期未满不应清理");
        assertEquals(1, store.expireGrace(START.plusSeconds(40)), "宽限期刚到应清理");
        assertTrue(store.find(PLAYER).isEmpty());
        assertEquals(LoginSession.Status.AUTHENTICATED, store.find(SECOND).orElseThrow().status(),
                "仍在线认证会话必须保留");
        assertEquals(LoginSession.Status.PENDING, store.find(THIRD).orElseThrow().status(),
                "未认证会话由超时逻辑处理，不属于宽限清理");
        assertEquals(0, store.expireGrace(START.plusSeconds(41)), "已清理记录不应重复计数");
    }

    @Test
    void changingReconnectGraceKeepsSessionsAndAppliesTheNewDeadline() {
        LoginSessionStore store = new LoginSessionStore(Duration.ofMinutes(5));
        store.join(PLAYER, "PlayerOne", IP, START);
        store.beginVerification(PLAYER, START.plusSeconds(1));
        store.authenticationSucceeded(PLAYER, START.plusSeconds(2));
        store.quit(PLAYER, START.plusSeconds(10));

        store.join(SECOND, "PlayerTwo", IP, START);
        store.beginVerification(SECOND, START.plusSeconds(1));
        store.authenticationSucceeded(SECOND, START.plusSeconds(2));

        store.setReconnectGrace(Duration.ofSeconds(30));

        assertEquals(LoginSession.Status.AUTHENTICATED,
                store.find(SECOND).orElseThrow().status(), "重载宽限不应丢失在线会话");
        LoginSession rejoined = store.join(PLAYER, "PlayerOne", IP, START.plusSeconds(41));
        assertEquals(LoginSession.Status.PENDING, rejoined.status(),
                "新宽限应立即用于断线重连判断");
        assertEquals(LoginSession.Status.AUTHENTICATED,
                store.join(SECOND, "PlayerTwo", IP, START.plusSeconds(42)).status());
    }
}
