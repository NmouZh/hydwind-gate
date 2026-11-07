package me.hydwind.gate.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoginSessionStoreTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final String IP = "192.0.2.1";
    private static final Duration GRACE = Duration.ofMinutes(5);
    private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(5);

    private static LoginSessionStore newStore() {
        return new LoginSessionStore(GRACE);
    }

    private static LoginSession join(LoginSessionStore store, UUID uuid, String gameId, Instant now) {
        return store.join(uuid, gameId, IP, now, LOGIN_TIMEOUT);
    }

    @Test
    void joinStartsPendingWithFixedDeadlineAndSuccessfulVerificationAuthenticates() {
        LoginSessionStore store = newStore();

        LoginSession pending = join(store, PLAYER, "PlayerOne", START);
        assertEquals(LoginSession.Status.PENDING, pending.status());
        assertEquals(PLAYER, pending.uuid());
        assertEquals("PlayerOne", pending.gameId());
        assertEquals(IP, pending.ip());
        assertEquals(START, pending.enteredAt());
        assertEquals(START, pending.lastActivityAt());
        assertEquals(START.plus(LOGIN_TIMEOUT), pending.loginDeadline());
        assertEquals(0L, pending.verificationId(), "尚未提交校验时编号为 0");

        OptionalLong firstId = store.beginVerification(PLAYER, START.plusSeconds(30));
        assertTrue(firstId.isPresent());
        assertEquals(1L, firstId.getAsLong());
        LoginSession verifying = store.find(PLAYER).orElseThrow();
        assertEquals(LoginSession.Status.VERIFYING, verifying.status());
        assertEquals(START.plusSeconds(30), verifying.lastActivityAt());
        assertEquals(1L, verifying.verificationId());
        assertEquals(START.plus(LOGIN_TIMEOUT), verifying.loginDeadline(), "提交校验不应改变 deadline");
        assertTrue(store.beginVerification(PLAYER, START.plusSeconds(31)).isEmpty());

        assertFalse(store.authenticationSucceeded(PLAYER, firstId.getAsLong() + 1, START.plusSeconds(32)),
                "错误的校验编号不能认证");
        assertTrue(store.authenticationSucceeded(PLAYER, firstId.getAsLong(), START.plusSeconds(35)));
        LoginSession authenticated = store.find(PLAYER).orElseThrow();
        assertEquals(LoginSession.Status.AUTHENTICATED, authenticated.status());
        assertEquals(START.plusSeconds(35), authenticated.lastActivityAt());
        assertEquals(START.plus(LOGIN_TIMEOUT), authenticated.loginDeadline());
        assertEquals(1L, authenticated.verificationId());
        assertTrue(store.beginVerification(PLAYER, START.plusSeconds(36)).isEmpty());
    }

    @Test
    void failedVerificationCanRetryButStaleResultsCannotAffectTheNewAttempt() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);
        assertFalse(store.authenticationSucceeded(PLAYER, 0L, START.plusSeconds(1)),
                "PENDING 会话没有可成功的校验");

        OptionalLong firstId = store.beginVerification(PLAYER, START.plusSeconds(2));
        assertTrue(firstId.isPresent());
        assertTrue(store.authenticationFailed(PLAYER, firstId.getAsLong(), START.plusSeconds(3)));
        LoginSession pending = store.find(PLAYER).orElseThrow();
        assertEquals(LoginSession.Status.PENDING, pending.status());
        assertEquals(firstId.getAsLong(), pending.verificationId(), "失败后编号保留，下一次提交继续递增");

        OptionalLong secondId = store.beginVerification(PLAYER, START.plusSeconds(4));
        assertTrue(secondId.isPresent());
        assertEquals(firstId.getAsLong() + 1, secondId.getAsLong());

        assertFalse(store.authenticationFailed(PLAYER, firstId.getAsLong(), START.plusSeconds(5)),
                "第一次校验的迟到失败结果必须被忽略");
        assertFalse(store.authenticationSucceeded(PLAYER, firstId.getAsLong(), START.plusSeconds(6)),
                "第一次校验的迟到成功结果必须被忽略");
        assertEquals(LoginSession.Status.VERIFYING, store.find(PLAYER).orElseThrow().status());

        assertTrue(store.authenticationSucceeded(PLAYER, secondId.getAsLong(), START.plusSeconds(7)));
        assertFalse(store.authenticationFailed(PLAYER, secondId.getAsLong(), START.plusSeconds(8)));
        assertEquals(LoginSession.Status.AUTHENTICATED, store.find(PLAYER).orElseThrow().status());
    }

    @Test
    void timeOutFiresExactlyAtTheSessionsOwnDeadline() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);

        assertFalse(store.timeOut(PLAYER, START.plus(LOGIN_TIMEOUT).minusNanos(1)),
                "deadline 之前不应超时");
        assertTrue(store.beginVerification(PLAYER, START.plusSeconds(60)).isPresent());
        assertTrue(store.timeOut(PLAYER, START.plus(LOGIN_TIMEOUT)),
                "到达 deadline 即应超时");

        LoginSession timedOut = store.find(PLAYER).orElseThrow();
        assertEquals(LoginSession.Status.TIMED_OUT, timedOut.status());
        assertEquals(START.plus(LOGIN_TIMEOUT), timedOut.lastActivityAt());
        assertEquals(START.plus(LOGIN_TIMEOUT), timedOut.loginDeadline());
        assertFalse(store.authenticationSucceeded(PLAYER, 1L, START.plus(LOGIN_TIMEOUT).plusSeconds(1)));
        assertTrue(store.beginVerification(PLAYER, START.plus(LOGIN_TIMEOUT).plusSeconds(2)).isEmpty());
        assertFalse(store.timeOut(PLAYER, START.plus(LOGIN_TIMEOUT).plusSeconds(3)),
                "同一会话不应重复超时");
    }

    @Test
    void eachSessionKeepsTheTimeoutFromItsOwnJoin() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);
        store.join(SECOND, "PlayerTwo", IP, START, Duration.ofMinutes(10));

        Instant fiveMinutes = START.plus(LOGIN_TIMEOUT);
        assertTrue(store.timeOut(PLAYER, fiveMinutes));
        assertFalse(store.timeOut(SECOND, fiveMinutes), "不同会话使用各自的 deadline");
        assertTrue(store.timeOut(SECOND, START.plus(Duration.ofMinutes(10))));
    }

    @Test
    void reconnectWithinGraceKeepsAuthenticationAndTheOriginalDeadline() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);
        assertTrue(store.beginVerification(PLAYER, START.plusSeconds(1)).isPresent());
        assertTrue(store.authenticationSucceeded(PLAYER, 1L, START.plusSeconds(2)));
        Instant quitAt = START.plusSeconds(10);
        store.quit(PLAYER, quitAt);

        Instant rejoinAt = quitAt.plus(GRACE).minusNanos(1);
        LoginSession resumed = store.join(PLAYER, "PlayerOne", IP, rejoinAt, Duration.ofMinutes(30));

        assertEquals(LoginSession.Status.AUTHENTICATED, resumed.status());
        assertEquals(START, resumed.enteredAt());
        assertEquals(rejoinAt, resumed.lastActivityAt());
        assertEquals(START.plus(LOGIN_TIMEOUT), resumed.loginDeadline(),
                "宽限恢复的会话必须保留原 deadline，不采用本次 join 的超时");
        assertEquals(1L, resumed.verificationId());
        assertFalse(store.timeOut(PLAYER, START.plus(Duration.ofHours(1))),
                "已认证会话不受登录超时影响");
    }

    @Test
    void reconnectAtGraceDeadlineOrWithDifferentIpRequiresLogin() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);
        assertTrue(store.beginVerification(PLAYER, START.plusSeconds(1)).isPresent());
        assertTrue(store.authenticationSucceeded(PLAYER, 1L, START.plusSeconds(2)));
        store.quit(PLAYER, START.plusSeconds(10));

        LoginSession differentIp = store.join(PLAYER, "PlayerOne", "198.51.100.7", START.plusSeconds(11),
                LOGIN_TIMEOUT);
        assertEquals(LoginSession.Status.PENDING, differentIp.status());
        assertEquals("198.51.100.7", differentIp.ip());
        assertEquals(START.plusSeconds(11), differentIp.enteredAt());
        assertEquals(START.plusSeconds(11).plus(LOGIN_TIMEOUT), differentIp.loginDeadline());
        assertEquals(0L, differentIp.verificationId());

        assertTrue(store.beginVerification(PLAYER, START.plusSeconds(12)).isPresent());
        assertTrue(store.authenticationSucceeded(PLAYER, 1L, START.plusSeconds(13)));
        store.quit(PLAYER, START.plusSeconds(20));
        LoginSession expired = store.join(PLAYER, "PlayerOne", "198.51.100.7", START.plusSeconds(320),
                LOGIN_TIMEOUT);
        assertEquals(LoginSession.Status.PENDING, expired.status());
        assertEquals(START.plusSeconds(320), expired.enteredAt());
        assertEquals(START.plusSeconds(320).plus(LOGIN_TIMEOUT), expired.loginDeadline());
    }

    @Test
    void unauthenticatedQuitAndFreshStoreNeverGrantGrace() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);
        store.quit(PLAYER, START.plusSeconds(10));
        LoginSession rejoined = join(store, PLAYER, "PlayerOne", START.plusSeconds(11));
        assertEquals(LoginSession.Status.PENDING, rejoined.status());
        assertEquals(START.plusSeconds(11).plus(LOGIN_TIMEOUT), rejoined.loginDeadline());

        assertTrue(store.beginVerification(PLAYER, START.plusSeconds(12)).isPresent());
        assertTrue(store.authenticationSucceeded(PLAYER, 1L, START.plusSeconds(13)));
        store.quit(PLAYER, START.plusSeconds(14));
        LoginSession fromFreshStore = join(newStore(), PLAYER, "PlayerOne", START.plusSeconds(15));
        assertEquals(LoginSession.Status.PENDING, fromFreshStore.status());
        assertEquals(START.plusSeconds(15).plus(LOGIN_TIMEOUT), fromFreshStore.loginDeadline());
        assertTrue(store.find(PLAYER).isPresent());
    }

    @Test
    void duplicateJoinForAnOnlineAuthenticatedPlayerOnlyRefreshesActivity() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);
        assertTrue(store.beginVerification(PLAYER, START.plusSeconds(1)).isPresent());
        assertTrue(store.authenticationSucceeded(PLAYER, 1L, START.plusSeconds(2)));

        LoginSession duplicate = store.join(PLAYER, "PlayerOne", IP, START.plusSeconds(30),
                Duration.ofMinutes(60));

        assertEquals(LoginSession.Status.AUTHENTICATED, duplicate.status());
        assertEquals(START, duplicate.enteredAt());
        assertEquals(START.plusSeconds(30), duplicate.lastActivityAt());
        assertEquals(null, duplicate.disconnectedAt());
        assertEquals(START.plus(LOGIN_TIMEOUT), duplicate.loginDeadline(),
                "重复在线事件不能延长原 deadline");
        assertEquals(1L, duplicate.verificationId());
    }

    @Test
    void duplicateJoinForAnOnlinePendingPlayerKeepsTheOriginalDeadline() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);

        LoginSession duplicate = store.join(PLAYER, "PlayerOne", IP, START.plusSeconds(60),
                Duration.ofMinutes(30));

        assertEquals(LoginSession.Status.PENDING, duplicate.status());
        assertEquals(START, duplicate.enteredAt());
        assertEquals(START.plusSeconds(60), duplicate.lastActivityAt());
        assertEquals(START.plus(LOGIN_TIMEOUT), duplicate.loginDeadline(),
                "重复进服不能重新开始登录倒计时");
        assertTrue(store.timeOut(PLAYER, START.plus(LOGIN_TIMEOUT)));
    }

    @Test
    void adminForceAuthenticateWorksOnUnauthenticatedSessionsOnly() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);

        assertTrue(store.forceAuthenticate(PLAYER, START.plusSeconds(1)));
        LoginSession forced = store.find(PLAYER).orElseThrow();
        assertEquals(LoginSession.Status.AUTHENTICATED, forced.status());
        assertEquals(START.plus(LOGIN_TIMEOUT), forced.loginDeadline());
        assertEquals(0L, forced.verificationId());
        assertFalse(store.forceAuthenticate(PLAYER, START.plusSeconds(2)),
                "已认证会话不应被重复放行");
        assertFalse(store.forceAuthenticate(SECOND, START.plusSeconds(3)),
                "不存在的会话不能放行");

        join(store, SECOND, "PlayerTwo", START);
        assertTrue(store.beginVerification(SECOND, START.plusSeconds(4)).isPresent());
        assertTrue(store.forceAuthenticate(SECOND, START.plusSeconds(5)),
                "管理员可以在校验中直接放行");
        LoginSession second = store.find(SECOND).orElseThrow();
        assertEquals(LoginSession.Status.AUTHENTICATED, second.status());
        assertEquals(1L, second.verificationId());
    }

    @Test
    void adminRemoveDeletesTheSessionAndItsGraceRecord() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);
        assertTrue(store.beginVerification(PLAYER, START.plusSeconds(1)).isPresent());
        assertTrue(store.authenticationSucceeded(PLAYER, 1L, START.plusSeconds(2)));
        store.quit(PLAYER, START.plusSeconds(3));

        assertTrue(store.remove(PLAYER));
        assertTrue(store.find(PLAYER).isEmpty());
        assertFalse(store.remove(PLAYER), "重复删除返回 false");

        LoginSession rejoined = join(store, PLAYER, "PlayerOne", START.plusSeconds(4));
        assertEquals(LoginSession.Status.PENDING, rejoined.status(), "踢出后宽限记录必须失效");
        assertEquals(START.plusSeconds(4).plus(LOGIN_TIMEOUT), rejoined.loginDeadline());
    }

    @Test
    void findByGameIdIgnoresCaseAndReturnsEmptyWhenMissing() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);

        assertEquals(PLAYER, store.findByGameId("playerONE").orElseThrow().uuid());
        assertEquals(PLAYER, store.findByGameId("PlayerOne").orElseThrow().uuid());
        assertTrue(store.findByGameId("SomeoneElse").isEmpty());
    }

    @Test
    void snapshotIsAnImmutableCopy() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);

        List<LoginSession> snapshot = store.snapshot();
        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());

        join(store, SECOND, "PlayerTwo", START);
        assertEquals(1, snapshot.size(), "快照不应随后续写入变化");
        assertEquals(2, store.snapshot().size());
    }

    @Test
    void timeOutAllOnlyMarksAwaitingSessionsPastTheirOwnDeadline() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);
        store.join(SECOND, "PlayerTwo", IP, START.plusSeconds(60), LOGIN_TIMEOUT);
        assertTrue(store.beginVerification(SECOND, START.plusSeconds(61)).isPresent());
        join(store, THIRD, "PlayerThree", START);
        assertTrue(store.beginVerification(THIRD, START.plusSeconds(1)).isPresent());
        assertTrue(store.authenticationSucceeded(THIRD, 1L, START.plusSeconds(2)));

        List<LoginSession> firstSweep = store.timeOutAll(START.plus(LOGIN_TIMEOUT));

        assertEquals(1, firstSweep.size());
        assertEquals(PLAYER, firstSweep.getFirst().uuid());
        assertEquals(LoginSession.Status.TIMED_OUT, firstSweep.getFirst().status());
        assertEquals(START.plus(LOGIN_TIMEOUT), firstSweep.getFirst().loginDeadline());
        assertEquals(LoginSession.Status.TIMED_OUT, store.find(PLAYER).orElseThrow().status());
        assertEquals(LoginSession.Status.VERIFYING, store.find(SECOND).orElseThrow().status(),
                "未到自己的 deadline 不应超时");
        assertEquals(LoginSession.Status.AUTHENTICATED, store.find(THIRD).orElseThrow().status(),
                "已认证会话不应被超时扫描影响");

        List<LoginSession> secondSweep = store.timeOutAll(START.plusSeconds(60).plus(LOGIN_TIMEOUT));

        assertEquals(1, secondSweep.size());
        assertEquals(SECOND, secondSweep.getFirst().uuid());
        assertEquals(LoginSession.Status.TIMED_OUT, store.find(SECOND).orElseThrow().status());
        assertTrue(store.timeOutAll(START.plusSeconds(60).plus(LOGIN_TIMEOUT)).isEmpty(),
                "同一会话不应重复返回");
    }

    @Test
    void expireGraceRemovesOnlyDisconnectedAuthenticatedSessionsPastTheDeadline() {
        LoginSessionStore store = new LoginSessionStore(Duration.ofSeconds(30));
        join(store, PLAYER, "PlayerOne", START);
        assertTrue(store.beginVerification(PLAYER, START.plusSeconds(1)).isPresent());
        assertTrue(store.authenticationSucceeded(PLAYER, 1L, START.plusSeconds(2)));
        store.quit(PLAYER, START.plusSeconds(10));

        join(store, SECOND, "PlayerTwo", START);
        assertTrue(store.beginVerification(SECOND, START.plusSeconds(1)).isPresent());
        assertTrue(store.authenticationSucceeded(SECOND, 1L, START.plusSeconds(2)));

        join(store, THIRD, "PlayerThree", START);

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
    void changingReconnectGraceKeepsSessionsAndTheirDeadlines() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);
        assertTrue(store.beginVerification(PLAYER, START.plusSeconds(1)).isPresent());
        assertTrue(store.authenticationSucceeded(PLAYER, 1L, START.plusSeconds(2)));
        store.quit(PLAYER, START.plusSeconds(10));

        join(store, SECOND, "PlayerTwo", START);
        assertTrue(store.beginVerification(SECOND, START.plusSeconds(1)).isPresent());
        assertTrue(store.authenticationSucceeded(SECOND, 1L, START.plusSeconds(2)));
        Instant secondDeadline = store.find(SECOND).orElseThrow().loginDeadline();

        store.setReconnectGrace(Duration.ofSeconds(30));

        assertEquals(LoginSession.Status.AUTHENTICATED,
                store.find(SECOND).orElseThrow().status(), "重载宽限不应丢失在线会话");
        assertEquals(secondDeadline, store.find(SECOND).orElseThrow().loginDeadline(),
                "重载宽限不应移动已有会话的 loginDeadline");
        LoginSession rejoined = join(store, PLAYER, "PlayerOne", START.plusSeconds(41));
        assertEquals(LoginSession.Status.PENDING, rejoined.status(),
                "新宽限应立即用于断线重连判断");
        assertEquals(START.plusSeconds(41).plus(LOGIN_TIMEOUT), rejoined.loginDeadline());
        LoginSession stillAuthenticated = store.join(SECOND, "PlayerTwo", IP, START.plusSeconds(42),
                Duration.ofMinutes(30));
        assertEquals(LoginSession.Status.AUTHENTICATED, stillAuthenticated.status());
        assertEquals(secondDeadline, stillAuthenticated.loginDeadline(),
                "重复加入不能改变已认证会话的 deadline");
    }

    @Test
    void reloadWithANewStoreBuildsFreshDeadlinesWithoutTouchingTheOldStore() {
        LoginSessionStore original = newStore();
        LoginSession beforeReload = join(original, PLAYER, "PlayerOne", START);
        assertEquals(START.plus(LOGIN_TIMEOUT), beforeReload.loginDeadline());

        LoginSessionStore reloaded = new LoginSessionStore(Duration.ofSeconds(30));
        LoginSession afterReload = reloaded.join(PLAYER, "PlayerOne", IP, START.plusSeconds(120),
                Duration.ofMinutes(10));

        assertEquals(LoginSession.Status.PENDING, afterReload.status());
        assertEquals(START.plusSeconds(120).plus(Duration.ofMinutes(10)), afterReload.loginDeadline());
        assertEquals(START.plus(LOGIN_TIMEOUT), original.find(PLAYER).orElseThrow().loginDeadline(),
                "新建 store 不应影响旧 store 中会话的 deadline");
    }

    @Test
    void loginSessionRejectsNullDeadline() {
        assertThrows(NullPointerException.class, () -> new LoginSession(
                PLAYER, "PlayerOne", IP, LoginSession.Status.PENDING, START, START, null, null, 0L));
    }
}
