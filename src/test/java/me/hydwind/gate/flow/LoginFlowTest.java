package me.hydwind.gate.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import me.hydwind.gate.auth.LoginSession;
import me.hydwind.gate.auth.LoginSessionStore;
import me.hydwind.gate.ui.Messages;
import me.hydwind.gate.web.HealthResult;
import me.hydwind.gate.web.VerifyResult;
import me.hydwind.gate.web.WebsiteClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 穿过 {@link LoginFlow} 接口的编排测试：真实内存会话仓库 + 假网站客户端 + 可控时钟。
 */
class LoginFlowTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final String GAME_ID = "PlayerOne";
    private static final String IP = "192.0.2.1";
    private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(5);

    private final Messages messages = Messages.defaults();
    private final MutableClock clock = new MutableClock(START);
    private final StubWebsite website = new StubWebsite();

    private LoginSessionStore store;
    private LoginFlow flow;
    private LoginIdentity identity;

    @BeforeEach
    void setUp() {
        // 宽限时长与登录超时无关，取一个固定值即可。
        store = new LoginSessionStore(Duration.ofMinutes(10));
        flow = new LoginFlow(store, website, messages, clock);
        identity = new LoginIdentity(PLAYER, GAME_ID, IP);
        // 进服时由 Adapter 建会话；LoginFlow 自身不负责 join。
        store.join(PLAYER, GAME_ID, IP, clock.instant(), LOGIN_TIMEOUT);
    }

    @Test
    void nonLoginChatIsIgnored() {
        assertKind(SubmitKind.IGNORED, flow.submit(identity, "hello everyone"));
        assertKind(SubmitKind.IGNORED, flow.submit(identity, "/register something"));
    }

    @Test
    void emptyAndOversizedCodesAreClassifiedBeforeTouchingTheWebsite() {
        assertKind(SubmitKind.EMPTY_CODE, flow.submit(identity, "/login"));
        assertKind(SubmitKind.EMPTY_CODE, flow.submit(identity, "/login    "));
        assertKind(SubmitKind.TOO_LONG, flow.submit(identity, "/login " + "x".repeat(65)));

        assertNull(website.lastCode, "被拒绝的提交不应调用网站");
        assertEquals(LoginSession.Status.PENDING, session().status());
    }

    @Test
    void submitWithoutSessionIsRejectedAndDoesNotCreateOne() {
        LoginIdentity ghost = new LoginIdentity(
                UUID.fromString("00000000-0000-0000-0000-000000000099"), "Ghost", IP);

        assertKind(SubmitKind.REJECTED, flow.submit(ghost, "/login some-code"));
        assertTrue(store.find(ghost.uuid()).isEmpty(), "LoginFlow 不得替 Adapter 创建会话");
    }

    @Test
    void successfulSubmitStartsVerificationAndNormalizesTheCode() {
        SubmitResult result = flow.submit(identity, "/login ShowMe123");

        assertEquals(SubmitKind.VERIFYING, result.kind());
        assertTrue(result.verificationId() > 0L, "提交成功后必须带有会话内校验编号");
        assertEquals("SHOWME123", result.normalizedCode(), "ASCII 字母应统一大写");
        assertEquals(LoginSession.Status.VERIFYING, session().status());
    }

    @Test
    void duplicateSubmitWhileVerifyingIsRejected() {
        SubmitResult first = flow.submit(identity, "/login ShowMe123");
        SubmitResult second = flow.submit(identity, "/login AnotherCode");

        assertEquals(SubmitKind.REJECTED, second.kind());
        assertEquals(0L, second.verificationId());
        assertNull(second.normalizedCode());
        assertEquals(LoginSession.Status.VERIFYING, session().status(), "重复提交不应改变状态");
        assertEquals(first.verificationId(), session().verificationId(),
                "重复提交不应递增校验编号");
    }

    @Test
    void successfulVerificationAuthenticatesAndPassesIdentityToTheWebsite() {
        SubmitResult submitted = flow.submit(identity, "/login ShowMe123");
        website.result = VerifyResult.ok("PlayerOne", "一号玩家", "player");

        VerifyOutcome outcome = flow.verify(identity, submitted.verificationId(), submitted.normalizedCode());

        assertTrue(outcome.authenticated());
        assertFalse(outcome.triggerRestart());
        assertFalse(outcome.contractMismatch());
        assertEquals(VerifyResult.Status.OK, outcome.status());
        assertTrue(outcome.message().contains("一号玩家"));

        assertEquals("SHOWME123", website.lastCode);
        assertEquals(GAME_ID, website.lastGameId);
        assertEquals(PLAYER, website.lastUuid);
        assertEquals(LoginSession.Status.AUTHENTICATED, session().status());
    }

    @Test
    void furtherSubmitsAfterAuthenticationAreReportedAsAlreadyAuthenticated() {
        authenticate(VerifyResult.ok("PlayerOne", null, "player"));

        assertKind(SubmitKind.ALREADY_AUTHENTICATED, flow.submit(identity, "/login again"));
    }

    @Test
    void lateSuccessAfterTimeoutIsDiscarded() {
        SubmitResult submitted = flow.submit(identity, "/login ShowMe123");
        clock.advance(LOGIN_TIMEOUT.plusSeconds(1));
        assertTrue(flow.timeout(identity), "超过截止时刻应标记超时");
        assertEquals(LoginSession.Status.TIMED_OUT, session().status());

        website.result = VerifyResult.ok("PlayerOne", "一号玩家", "player");
        VerifyOutcome outcome = flow.verify(identity, submitted.verificationId(), submitted.normalizedCode());

        assertFalse(outcome.authenticated(), "迟到的成功结果不得放行");
        assertEquals("", outcome.message(), "迟到结果不再向玩家播报成功文案");
        assertNull(outcome.status(), "迟到成功不应向 Adapter 暴露诊断状态");
        assertFalse(outcome.triggerRestart());
        assertFalse(outcome.contractMismatch());
        assertEquals(LoginSession.Status.TIMED_OUT, session().status());
    }

    @Test
    void timeoutFiresOnlyAfterTheSessionDeadline() {
        assertFalse(flow.timeout(identity), "截止时刻之前不应超时");
        assertEquals(LoginSession.Status.PENDING, session().status());

        clock.advance(LOGIN_TIMEOUT.plusSeconds(1));
        assertTrue(flow.timeout(identity));
        assertEquals(LoginSession.Status.TIMED_OUT, session().status());
        // 已标记超时的会话仍返回 true：调用方必须据此断开，不能因为别人先标记过就放弃踢人。
        assertTrue(flow.timeout(identity), "已超时会话再次查询应仍要求调用方断开");
    }

    @Test
    void failedVerificationReturnsItsFeedbackAndAllowsRetry() {
        SubmitResult submitted = flow.submit(identity, "/login WrongCode");
        website.result = VerifyResult.invalidCode();

        VerifyOutcome outcome = flow.verify(identity, submitted.verificationId(), submitted.normalizedCode());

        assertFalse(outcome.authenticated());
        assertFalse(outcome.triggerRestart());
        assertFalse(outcome.contractMismatch());
        assertEquals(VerifyResult.Status.INVALID_CODE, outcome.status());
        assertEquals(messages.invalidCode(), outcome.message());
        assertEquals(LoginSession.Status.PENDING, session().status(), "失败后应允许重新提交");

        SubmitResult retry = flow.submit(identity, "/login AnotherCode");
        assertEquals(SubmitKind.VERIFYING, retry.kind());
        assertNotEquals(submitted.verificationId(), retry.verificationId(),
                "重新提交必须使用新的校验编号");
    }

    @Test
    void unreachableWebsiteTriggersRestart() {
        SubmitResult submitted = flow.submit(identity, "/login ShowMe123");
        website.result = VerifyResult.unreachable();

        VerifyOutcome outcome = flow.verify(identity, submitted.verificationId(), submitted.normalizedCode());

        assertFalse(outcome.authenticated());
        assertTrue(outcome.triggerRestart());
        assertFalse(outcome.contractMismatch());
        assertEquals(VerifyResult.Status.UNREACHABLE, outcome.status());
        assertEquals(messages.serviceUnavailable(), outcome.message());
        assertEquals(LoginSession.Status.PENDING, session().status());
    }

    @Test
    void websiteExceptionIsTreatedAsUnreachable() {
        SubmitResult submitted = flow.submit(identity, "/login ShowMe123");
        website.failure = new IllegalStateException("boom");

        VerifyOutcome outcome = flow.verify(identity, submitted.verificationId(), submitted.normalizedCode());

        assertFalse(outcome.authenticated());
        assertTrue(outcome.triggerRestart(), "意外异常必须按网站不可达处理");
        assertFalse(outcome.contractMismatch());
        assertEquals(VerifyResult.Status.UNREACHABLE, outcome.status());
        assertEquals(messages.serviceUnavailable(), outcome.message());
    }

    @Test
    void contractMismatchIsReportedWithoutRestart() {
        SubmitResult submitted = flow.submit(identity, "/login ShowMe123");
        website.result = VerifyResult.contractMismatch();

        VerifyOutcome outcome = flow.verify(identity, submitted.verificationId(), submitted.normalizedCode());

        assertFalse(outcome.authenticated());
        assertFalse(outcome.triggerRestart(), "契约不兼容时重启没有意义");
        assertTrue(outcome.contractMismatch());
        assertEquals(VerifyResult.Status.CONTRACT_MISMATCH, outcome.status());
        assertEquals(messages.serviceMisconfigured(), outcome.message());
        assertEquals(LoginSession.Status.PENDING, session().status(), "失败后应允许重新提交");
    }

    @Test
    void unauthorizedWebsiteResultIsExposedForAdapterWarningWithoutRestart() {
        SubmitResult submitted = flow.submit(identity, "/login ShowMe123");
        website.result = VerifyResult.unauthorized();

        VerifyOutcome outcome = flow.verify(identity, submitted.verificationId(), submitted.normalizedCode());

        assertFalse(outcome.authenticated());
        assertFalse(outcome.triggerRestart());
        assertFalse(outcome.contractMismatch());
        assertEquals(VerifyResult.Status.UNAUTHORIZED, outcome.status());
        assertEquals(messages.serviceMisconfigured(), outcome.message());
        assertEquals(LoginSession.Status.PENDING, session().status());
    }

    @Test
    void lateFailureFromAnAbandonedAttemptDoesNotTouchTheNewAttempt() {
        SubmitResult first = flow.submit(identity, "/login WrongCode");
        website.result = VerifyResult.invalidCode();
        VerifyOutcome firstOutcome = flow.verify(identity, first.verificationId(), first.normalizedCode());
        assertFalse(firstOutcome.authenticated());
        assertEquals(messages.invalidCode(), firstOutcome.message(),
                "前置：第一次失败必须正常返回自己的文案");
        assertEquals(LoginSession.Status.PENDING, session().status());

        SubmitResult retry = flow.submit(identity, "/login AnotherCode");
        assertEquals(SubmitKind.VERIFYING, retry.kind());
        assertNotEquals(first.verificationId(), retry.verificationId(),
                "重试必须分配新的校验编号");
        assertEquals(LoginSession.Status.VERIFYING, session().status());

        // 旧请求的失败结果现在才到达。故意用会触发重启的不可达：若缺少编号守卫，
        // 旧结果会把重试打回 PENDING、播报旧文案并触发重启，下面的断言就会失败。
        website.result = VerifyResult.unreachable();
        VerifyOutcome late = flow.verify(identity, first.verificationId(), first.normalizedCode());

        assertFalse(late.authenticated(), "迟到的失败结果不得放行");
        assertFalse(late.triggerRestart(), "迟到的失败结果不得触发重启");
        assertFalse(late.contractMismatch(), "迟到的失败结果不得报告契约不匹配");
        assertEquals("", late.message(), "迟到的失败结果不得向玩家播报旧文案");
        assertNull(late.status(), "迟到的失败结果不得暴露诊断状态");

        LoginSession stillVerifying = session();
        assertEquals(LoginSession.Status.VERIFYING, stillVerifying.status(),
                "旧结果不得把重试打回 PENDING");
        assertEquals(retry.verificationId(), stillVerifying.verificationId(),
                "旧结果不得改写重试的校验编号");

        website.result = VerifyResult.ok(GAME_ID, "一号玩家", "player");
        VerifyOutcome success = flow.verify(identity, retry.verificationId(), retry.normalizedCode());
        assertTrue(success.authenticated(), "丢弃旧结果后，新尝试的成功结果必须正常生效");
        assertEquals(LoginSession.Status.AUTHENTICATED, session().status());
    }

    private void authenticate(VerifyResult result) {
        SubmitResult submitted = flow.submit(identity, "/login ShowMe123");
        website.result = result;
        VerifyOutcome outcome = flow.verify(identity, submitted.verificationId(), submitted.normalizedCode());
        assertTrue(outcome.authenticated(), "测试前置：必须完成认证");
    }

    private LoginSession session() {
        return store.find(PLAYER).orElseThrow();
    }

    private static void assertKind(SubmitKind expected, SubmitResult result) {
        assertEquals(expected, result.kind());
        assertEquals(0L, result.verificationId());
        assertNull(result.normalizedCode());
    }

    /** 可控时钟，用于跨过会话截止时刻。 */
    private static final class MutableClock extends Clock {

        private Instant now;
        private final ZoneId zone;

        MutableClock(Instant now) {
            this(now, ZoneOffset.UTC);
        }

        private MutableClock(Instant now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** 可配置结果或异常的网站客户端替身，并记录最后一次调用参数。 */
    private static final class StubWebsite implements WebsiteClient {

        private VerifyResult result = VerifyResult.unreachable();
        private RuntimeException failure;
        private String lastCode;
        private String lastGameId;
        private UUID lastUuid;

        @Override
        public VerifyResult verify(String code, String gameId, UUID uuid) {
            lastCode = code;
            lastGameId = gameId;
            lastUuid = uuid;
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public HealthResult health() {
            return HealthResult.healthy(0L);
        }
    }
}