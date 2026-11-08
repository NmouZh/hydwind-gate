package me.hydwind.gate.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void verificationIdsKeepIncreasingAcrossSessionsForTheSamePlayer() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);
        long firstId = store.beginVerification(PLAYER, START.plusSeconds(1)).orElseThrow();

        store.quit(PLAYER, START.plusSeconds(2));
        LoginSession rejoined = join(store, PLAYER, "PlayerOne", START.plusSeconds(3));
        assertEquals(LoginSession.Status.PENDING, rejoined.status());
        assertEquals(0L, rejoined.verificationId(), "新会话自身尚未提交校验，编号为 0");

        long secondId = store.beginVerification(PLAYER, START.plusSeconds(4)).orElseThrow();
        assertEquals(firstId + 1, secondId, "校验编号必须跨会话单调递增，不能回到 1");

        assertFalse(store.authenticationSucceeded(PLAYER, firstId, START.plusSeconds(5)),
                "旧会话的迟到成功结果不能认证重连后的新会话");
        assertFalse(store.authenticationFailed(PLAYER, firstId, START.plusSeconds(6)),
                "旧会话的迟到失败结果不能打断重连后的新会话");
        LoginSession stillVerifying = store.find(PLAYER).orElseThrow();
        assertEquals(LoginSession.Status.VERIFYING, stillVerifying.status());
        assertEquals(secondId, stillVerifying.verificationId());
        assertTrue(store.authenticationSucceeded(PLAYER, secondId, START.plusSeconds(7)),
                "当前会话的校验结果仍应正常生效");
    }

    @Test
    void onlineAuthenticatedSessionWithDifferentIpRequiresFreshLogin() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);
        assertTrue(store.beginVerification(PLAYER, START.plusSeconds(1)).isPresent());
        assertTrue(store.authenticationSucceeded(PLAYER, 1L, START.plusSeconds(2)));
        assertEquals(null, store.find(PLAYER).orElseThrow().disconnectedAt(),
                "仍在线会话的标志是 disconnectedAt 为空");

        LoginSessionStore.JoinResult differentIp = store.joinWithConnection(
                PLAYER, "PlayerOne", "198.51.100.7", START.plusSeconds(3), LOGIN_TIMEOUT);

        assertTrue(differentIp.rejected(), "在线会话不能被不同 IP 的新连接顶掉");
        assertEquals(LoginSession.Status.AUTHENTICATED, differentIp.session().status());
        assertEquals(IP, differentIp.session().ip(), "原会话的 IP 必须保持不变");
    }

    @Test
    void timeOutOnAnAlreadyTimedOutSessionStillAsksTheCallerToDisconnect() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);

        assertTrue(store.timeOut(PLAYER, START.plus(LOGIN_TIMEOUT)), "首次超时应要求断开");
        assertTrue(store.timeOut(PLAYER, START.plus(LOGIN_TIMEOUT).plusSeconds(1)),
                "sweep 与 per-player 定时器竞争时，后到的调用仍应要求断开");
        assertTrue(store.timeOut(PLAYER, START.plus(LOGIN_TIMEOUT).plusSeconds(2)),
                "已超时会话持续返回 true，避免两个超时入口互相以为对方已经踢人");
        assertEquals(LoginSession.Status.TIMED_OUT, store.find(PLAYER).orElseThrow().status());
    }

    @Test
    void successfulVerificationAfterDeadlineTimesOutInsteadOfAuthenticating() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);
        long id = store.beginVerification(PLAYER, START.plusSeconds(1)).orElseThrow();

        assertFalse(store.authenticationSucceeded(PLAYER, id, START.plus(LOGIN_TIMEOUT).plusNanos(1)),
                "超过 loginDeadline 的成功结果不得放行");

        LoginSession timedOut = store.find(PLAYER).orElseThrow();
        assertEquals(LoginSession.Status.TIMED_OUT, timedOut.status(),
                "超过 deadline 的迟到成功结果应把会话落为 TIMED_OUT");
        assertTrue(store.timeOut(PLAYER, START.plus(LOGIN_TIMEOUT).plusSeconds(1)),
                "TIMED_OUT 会话仍要求调用方断开连接");
        assertFalse(store.authenticationSucceeded(PLAYER, id, START.plus(LOGIN_TIMEOUT).plusSeconds(2)),
                "超时之后重复到达的成功结果同样不得放行");
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
        // 已标记超时的会话对再次调用仍返回 true：调用方（per-player 定时器或 sweep）
        // 必须据此断开连接，不能因为别人已经标记过就放弃踢人。
        assertTrue(store.timeOut(PLAYER, START.plus(LOGIN_TIMEOUT).plusSeconds(3)),
                "已超时会话再次查询应仍要求调用方断开");
        assertEquals(LoginSession.Status.TIMED_OUT, store.find(PLAYER).orElseThrow().status());
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

        long secondId = store.beginVerification(PLAYER, START.plusSeconds(12)).orElseThrow();
        assertTrue(store.authenticationSucceeded(PLAYER, secondId, START.plusSeconds(13)));
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
    void duplicateJoinForAnOnlineAuthenticatedPlayerIsRejected() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);
        assertTrue(store.beginVerification(PLAYER, START.plusSeconds(1)).isPresent());
        assertTrue(store.authenticationSucceeded(PLAYER, 1L, START.plusSeconds(2)));

        LoginSessionStore.JoinResult duplicate = store.joinWithConnection(
                PLAYER, "PlayerOne", IP, START.plusSeconds(30), Duration.ofMinutes(60));

        assertTrue(duplicate.rejected(), "在线会话不能被第二条连接顶替");
        assertEquals(LoginSession.Status.AUTHENTICATED, duplicate.session().status());
        assertEquals(START, duplicate.session().enteredAt());
        assertEquals(START.plusSeconds(2), duplicate.session().lastActivityAt());
        assertEquals(START.plus(LOGIN_TIMEOUT), duplicate.session().loginDeadline());
        assertEquals(1L, duplicate.session().verificationId());
    }

    @Test
    void duplicateJoinForAnOnlinePendingPlayerIsRejectedWithoutExtendingDeadline() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);

        LoginSessionStore.JoinResult duplicate = store.joinWithConnection(
                PLAYER, "PlayerOne", IP, START.plusSeconds(60), Duration.ofMinutes(30));

        assertTrue(duplicate.rejected(), "在线会话不能被第二条连接顶替");
        assertEquals(LoginSession.Status.PENDING, duplicate.session().status());
        assertEquals(START, duplicate.session().enteredAt());
        assertEquals(START.plus(LOGIN_TIMEOUT), duplicate.session().loginDeadline(),
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
    void findByGameIdMatchesExactlyAndReturnsEmptyWhenMissing() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);

        // 离线模式的大小写变体是不同账号；放宽比较会让管理员动作落到错误的人身上。
        assertEquals(PLAYER, store.findByGameId("PlayerOne").orElseThrow().uuid());
        assertTrue(store.findByGameId("playerONE").isEmpty(),
                "大小写变体不得匹配，否则管理员可能放行到另一个账号");
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
        long thirdId = store.beginVerification(THIRD, START.plusSeconds(1)).orElseThrow();
        assertTrue(store.authenticationSucceeded(THIRD, thirdId, START.plusSeconds(2)));

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

        // PLAYER 仍是 TIMED_OUT 且未被清理，会继续出现在结果里：sweep 必须能
        // 一直重试直到真的把玩家断开或会话被移除。
        assertEquals(2, secondSweep.size());
        assertTrue(secondSweep.stream().anyMatch(session -> session.uuid().equals(PLAYER)));
        assertTrue(secondSweep.stream().anyMatch(session -> session.uuid().equals(SECOND)));
        assertEquals(LoginSession.Status.TIMED_OUT, store.find(SECOND).orElseThrow().status());

        // 模拟 sweep 处理完：移除两个已超时会话后，扫描不再重复返回。
        assertTrue(store.removeIfTimedOut(PLAYER));
        assertTrue(store.removeIfTimedOut(SECOND));
        assertTrue(store.timeOutAll(START.plusSeconds(60).plus(LOGIN_TIMEOUT)).isEmpty(),
                "已清理的超时会话不应重复返回");
    }

    @Test
    void expireGraceRemovesOnlyDisconnectedAuthenticatedSessionsPastTheDeadline() {
        LoginSessionStore store = new LoginSessionStore(Duration.ofSeconds(30));
        join(store, PLAYER, "PlayerOne", START);
        assertTrue(store.beginVerification(PLAYER, START.plusSeconds(1)).isPresent());
        assertTrue(store.authenticationSucceeded(PLAYER, 1L, START.plusSeconds(2)));
        store.quit(PLAYER, START.plusSeconds(10));

        join(store, SECOND, "PlayerTwo", START);
        long secondId = store.beginVerification(SECOND, START.plusSeconds(1)).orElseThrow();
        assertTrue(store.authenticationSucceeded(SECOND, secondId, START.plusSeconds(2)));

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
        long secondId = store.beginVerification(SECOND, START.plusSeconds(1)).orElseThrow();
        assertTrue(store.authenticationSucceeded(SECOND, secondId, START.plusSeconds(2)));
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

    // ---------------------------------------------------------------------
    // 回归：超时入口竞争时不能让玩家永久留在 limbo。
    // ---------------------------------------------------------------------

    @Test
    void timeOutAllReturnsTimedOutSessionsMarkedByALateVerificationResult() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);
        long id = store.beginVerification(PLAYER, START.plusSeconds(1)).orElseThrow();
        Instant afterDeadline = START.plus(LOGIN_TIMEOUT).plusNanos(1);

        assertFalse(store.authenticationSucceeded(PLAYER, id, afterDeadline),
                "超过 deadline 的迟到成功结果不得放行");
        assertEquals(LoginSession.Status.TIMED_OUT, store.find(PLAYER).orElseThrow().status(),
                "迟到的成功结果应把会话落为 TIMED_OUT");

        // 这个 TIMED_OUT 不是由 timeOutAll 迁移的，也没有任何 sweep 处理过它。
        // 下一次扫描必须仍然返回它，否则没有组件负责断开玩家。
        List<LoginSession> sweep = store.timeOutAll(afterDeadline.plusSeconds(1));
        assertEquals(1, sweep.size(),
                "已被其它入口标记为 TIMED_OUT 但尚未清理的会话，必须由下一次 timeOutAll 返回");
        assertEquals(PLAYER, sweep.getFirst().uuid());
        assertEquals(LoginSession.Status.TIMED_OUT, sweep.getFirst().status());
        assertEquals(START.plus(LOGIN_TIMEOUT), sweep.getFirst().loginDeadline());

        assertTrue(store.removeIfTimedOut(PLAYER), "sweep 处理完后应能清掉这个滞留会话");
        assertTrue(store.timeOutAll(afterDeadline.plusSeconds(2)).isEmpty(),
                "已清理的滞留会话不应再次出现在扫描结果里");
        assertTrue(store.find(PLAYER).isEmpty(), "清理后会话必须真正从 store 中消失");
    }

    @Test
    void removeIfTimedOutKeepsTheFreshSessionCreatedByAReconnect() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);
        long id = store.beginVerification(PLAYER, START.plusSeconds(1)).orElseThrow();
        Instant afterDeadline = START.plus(LOGIN_TIMEOUT).plusNanos(1);
        assertFalse(store.authenticationSucceeded(PLAYER, id, afterDeadline),
                "超过 deadline 的成功结果不得放行");
        assertEquals(LoginSession.Status.TIMED_OUT, store.find(PLAYER).orElseThrow().status());

        // 玩家在 sweep 拿到旧快照之后重连，建立同 UUID 的全新 PENDING 会话。
        Instant rejoinAt = afterDeadline.plusSeconds(10);
        LoginSession rejoined = join(store, PLAYER, "PlayerOne", rejoinAt);
        assertEquals(LoginSession.Status.PENDING, rejoined.status(),
                "超时后重连必须建立全新的 PENDING 会话");
        assertEquals(rejoinAt, rejoined.enteredAt());
        assertEquals(rejoinAt.plus(LOGIN_TIMEOUT), rejoined.loginDeadline());

        assertFalse(store.removeIfTimedOut(PLAYER),
                "sweep 持有的旧快照不能删掉玩家重连后建立的新会话");
        LoginSession survivor = store.find(PLAYER).orElseThrow();
        assertEquals(LoginSession.Status.PENDING, survivor.status(), "新会话必须仍然存在");
        assertEquals(rejoinAt, survivor.enteredAt(), "保留下来的必须正是重连建立的新会话");
        assertTrue(store.timeOutAll(rejoinAt).isEmpty(),
                "新会话尚未到自己的 deadline，不应被超时扫描命中");
    }

    @Test
    void verificationIdsNeverResetAcrossGraceExpiry() {
        LoginSessionStore store = newStore();
        Duration retention = Duration.ofHours(24);

        join(store, PLAYER, "PlayerOne", START);
        long firstId = store.beginVerification(PLAYER, START.plusSeconds(1)).orElseThrow();
        store.quit(PLAYER, START.plusSeconds(2));
        join(store, PLAYER, "PlayerOne", START.plusSeconds(3));
        long secondId = store.beginVerification(PLAYER, START.plusSeconds(4)).orElseThrow();
        store.quit(PLAYER, START.plusSeconds(5));
        assertEquals(1L, firstId);
        assertEquals(2L, secondId, "校验编号必须全局单调递增");

        // 无论宽限记录是否过期，全局编号都不能回退；否则旧连接的迟到结果
        // 可能命中重连后重新从 1 开始的会话。
        Instant afterRetention = START.plus(retention).plusSeconds(10);
        assertEquals(0, store.expireGrace(afterRetention));
        join(store, PLAYER, "PlayerOne", afterRetention);
        long nextId = store.beginVerification(PLAYER, afterRetention.plusSeconds(1)).orElseThrow();
        assertEquals(secondId + 1, nextId, "过期清理不得让校验编号回到 1");
    }

    // ---------------------------------------------------------------------
    // 回归：门禁唯一放行判据 isAuthenticated 必须 fail-closed。
    // ---------------------------------------------------------------------

    @Test
    void isAuthenticatedIsFalseForEverySessionStateExceptAuthenticated() {
        LoginSessionStore store = newStore();

        assertFalse(store.isAuthenticated(PLAYER), "没有会话时必须返回 false（fail-closed）");

        join(store, PLAYER, "PlayerOne", START);
        assertFalse(store.isAuthenticated(PLAYER), "PENDING 会话不得放行");

        long id = store.beginVerification(PLAYER, START.plusSeconds(1)).orElseThrow();
        assertFalse(store.isAuthenticated(PLAYER), "VERIFYING 会话不得放行");

        assertTrue(store.authenticationSucceeded(PLAYER, id, START.plusSeconds(2)));
        assertTrue(store.isAuthenticated(PLAYER), "AUTHENTICATED 会话必须放行");

        store.quit(PLAYER, START.plusSeconds(3));
        LoginSession grace = store.find(PLAYER).orElseThrow();
        assertNotNull(grace.disconnectedAt(), "前置：断线后必须留下宽限记录");
        assertEquals(LoginSession.Status.AUTHENTICATED, grace.status());
        assertTrue(store.isAuthenticated(PLAYER),
                "宽限内的断线认证会话（disconnectedAt 非空）仍算已认证");

        join(store, SECOND, "PlayerTwo", START);
        assertFalse(store.isAuthenticated(SECOND), "前置：新会话仍为 PENDING");
        assertTrue(store.timeOut(SECOND, START.plus(LOGIN_TIMEOUT)));
        assertEquals(LoginSession.Status.TIMED_OUT, store.find(SECOND).orElseThrow().status());
        assertFalse(store.isAuthenticated(SECOND), "TIMED_OUT 会话不得放行");

        assertFalse(store.isAuthenticated(THIRD), "从未进服的 UUID 不得放行");
    }

    @Test
    void forceAuthenticateByGameIdWritesOnlyTheMatchingUnauthenticatedSession() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);
        join(store, SECOND, "PlayerTwo", START);
        LoginSession secondBefore = store.find(SECOND).orElseThrow();

        LoginSession forced = store
                .forceAuthenticateByGameId("PlayerOne", START.plusSeconds(1))
                .orElseThrow();

        assertEquals(PLAYER, forced.uuid(), "必须返回被写入的那个会话");
        assertEquals("PlayerOne", forced.gameId());
        assertEquals(LoginSession.Status.AUTHENTICATED, forced.status());
        assertEquals(START.plusSeconds(1), forced.lastActivityAt());
        assertTrue(store.isAuthenticated(PLAYER), "store 中该 UUID 必须已认证");
        assertEquals(forced, store.find(PLAYER).orElseThrow(),
                "返回的会话必须正是 store 中写入的会话");

        assertEquals(secondBefore, store.find(SECOND).orElseThrow(),
                "游戏 ID 不匹配的会话必须原样保留，不得被误写");
        assertEquals(LoginSession.Status.PENDING, store.find(SECOND).orElseThrow().status());

        assertTrue(store.forceAuthenticateByGameId("playerONE", START.plusSeconds(2)).isEmpty(),
                "大小写变体不得命中，已认证会话也不会被重复放行");
        assertTrue(store.forceAuthenticateByGameId("PlayerOne", START.plusSeconds(2)).isEmpty(),
                "已认证会话返回 empty");
        assertEquals(forced, store.find(PLAYER).orElseThrow(),
                "已认证会话的状态与活动时间都不得被重复放行改写");

        assertTrue(store.forceAuthenticateByGameId("MissingPlayer", START.plusSeconds(3)).isEmpty(),
                "不存在的游戏 ID 返回 empty");
        assertTrue(store.snapshot().stream()
                        .noneMatch(session -> session.gameId().equals("MissingPlayer")),
                "放行失败不得凭空创建会话");
        assertFalse(store.isAuthenticated(THIRD), "未被放行的 UUID 仍不得通过门禁");
    }

    @Test
    void exactDeadlineRejectsAuthenticationAndTimesOutJustLikeTimeOut() {
        LoginSessionStore store = newStore();
        Instant deadline = START.plus(LOGIN_TIMEOUT);

        join(store, PLAYER, "PlayerOne", START);
        long playerId = store.beginVerification(PLAYER, START.plusSeconds(1)).orElseThrow();
        assertFalse(store.authenticationSucceeded(PLAYER, playerId, deadline),
                "now == loginDeadline 时不得认证，必须与 timeOut 的 !now.isBefore(deadline) 边界一致");

        LoginSession timedOut = store.find(PLAYER).orElseThrow();
        assertEquals(LoginSession.Status.TIMED_OUT, timedOut.status(),
                "恰好等于 deadline 的成功结果应把会话落为 TIMED_OUT");
        assertEquals(deadline, timedOut.lastActivityAt());
        assertEquals(deadline, timedOut.loginDeadline(), "超时迁移不得改变会话自己的 deadline");
        assertFalse(store.isAuthenticated(PLAYER), "超时迁移后不得通过门禁");

        join(store, SECOND, "PlayerTwo", START);
        assertTrue(store.beginVerification(SECOND, START.plusSeconds(1)).isPresent());
        assertTrue(store.timeOut(SECOND, deadline),
                "timeOut 在 now == loginDeadline 时必须返回 true");
        assertEquals(LoginSession.Status.TIMED_OUT, store.find(SECOND).orElseThrow().status());

        join(store, THIRD, "PlayerThree", START);
        long thirdId = store.beginVerification(THIRD, START.plusSeconds(1)).orElseThrow();
        assertTrue(store.authenticationSucceeded(THIRD, thirdId, deadline.minusNanos(1)),
                "deadline 前一纳秒仍属于有效窗口，必须允许认证");
        assertTrue(store.isAuthenticated(THIRD));
    }

    @Test
    void forceAuthenticateCannotReviveATimedOutSession() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);
        assertTrue(store.timeOut(PLAYER, START.plus(LOGIN_TIMEOUT)));

        assertFalse(store.forceAuthenticate(PLAYER, START.plus(LOGIN_TIMEOUT).plusSeconds(1)),
                "已超时会话正在被断开，管理员放行不能把它复活");
        assertTrue(store.forceAuthenticateByGameId("PlayerOne", START.plus(LOGIN_TIMEOUT).plusSeconds(2))
                        .isEmpty(),
                "按名放行同样不能复活已超时会话");
        assertEquals(LoginSession.Status.TIMED_OUT, store.find(PLAYER).orElseThrow().status(),
                "拒绝放行不得改变会话状态");
        assertFalse(store.isAuthenticated(PLAYER));
    }

    @Test
    void removeIfSessionDoesNotDeleteAReplacedSession() {
        LoginSessionStore store = newStore();
        join(store, PLAYER, "PlayerOne", START);
        LoginSession stale = store.find(PLAYER).orElseThrow();

        // 玩家断线（未认证会话被移除）后重连，建立新会话。
        store.quit(PLAYER, START.plusSeconds(1));
        LoginSession fresh = join(store, PLAYER, "PlayerOne", START.plusSeconds(2));
        assertFalse(store.removeIfSession(PLAYER, stale),
                "kick 持有的是旧快照，不能删掉重连后建立的新会话");
        assertEquals(fresh, store.find(PLAYER).orElseThrow());

        assertTrue(store.removeIfSession(PLAYER, fresh), "当前会话匹配时应正常移除");
        assertTrue(store.find(PLAYER).isEmpty());
    }

    @Test
    void lateDisconnectFromOldConnectionDoesNotDeleteNewSession() {
        LoginSessionStore store = newStore();
        LoginSessionStore.JoinResult first =
                store.joinWithConnection(PLAYER, "PlayerOne", IP, START, LOGIN_TIMEOUT);
        // 旧连接先断线（事件正常到达），随后玩家重连。
        store.quit(PLAYER, first.connectionId(), START.plusSeconds(1));
        LoginSessionStore.JoinResult second = store.joinWithConnection(
                PLAYER, "PlayerOne", IP, START.plusSeconds(2), LOGIN_TIMEOUT);
        LoginSession fresh = second.session();

        // 旧连接更迟的重复断线事件不能删掉新会话。
        store.quit(PLAYER, first.connectionId(), START.plusSeconds(3));
        assertEquals(fresh, store.find(PLAYER).orElseThrow(),
                "旧连接的迟到断线事件不能删掉新会话");

        store.quit(PLAYER, second.connectionId(), START.plusSeconds(4));
        assertTrue(store.find(PLAYER).isEmpty(), "当前连接断线仍应正常清理未认证会话");
    }
}
