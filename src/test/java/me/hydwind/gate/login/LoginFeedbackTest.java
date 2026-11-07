package me.hydwind.gate.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.hydwind.gate.ui.Messages;
import me.hydwind.gate.web.VerifyResult;
import org.junit.jupiter.api.Test;

class LoginFeedbackTest {

    private final Messages messages = Messages.defaults();

    @Test
    void successfulVerificationAuthenticatesAndUsesNickname() {
        LoginFeedback feedback = LoginFeedback.from(
                VerifyResult.ok("PlayerOne", "一号玩家", "player"), messages, "PlayerOne");

        assertTrue(feedback.authenticated());
        assertFalse(feedback.triggerRestart());
        assertTrue(feedback.message().contains("一号玩家"));
    }

    @Test
    void successfulAdminVerificationUsesAdminMessageAndFallsBackToGameId() {
        LoginFeedback feedback = LoginFeedback.from(
                VerifyResult.ok("AdminOne", null, "admin"), messages, "AdminOne");

        assertTrue(feedback.authenticated());
        assertEquals(messages.loginSuccessAdmin().replace("{name}", "AdminOne"), feedback.message());
    }

    @Test
    void successfulVerificationUsesGameIdWhenNicknameAndUsernameAreMissing() {
        LoginFeedback feedback = LoginFeedback.from(
                VerifyResult.ok(null, null, null), messages, "Fallback_Player");

        assertTrue(feedback.authenticated());
        assertEquals(messages.loginSuccess().replace("{name}", "Fallback_Player"), feedback.message());
    }

    @Test
    void knownFailuresMapToTheirConfiguredMessages() {
        assertFalse(LoginFeedback.from(VerifyResult.invalidCode(), messages, "P").authenticated());
        assertEquals(messages.invalidCode(), LoginFeedback.from(VerifyResult.invalidCode(), messages, "P").message());
        assertEquals(messages.expiredCode(), LoginFeedback.from(VerifyResult.expired(), messages, "P").message());
        assertEquals(messages.usedCode(), LoginFeedback.from(VerifyResult.used(), messages, "P").message());
        assertEquals(messages.gameIdMismatch(), LoginFeedback.from(VerifyResult.gameIdMismatch(), messages, "P").message());
        assertEquals(messages.gameIdMismatchExpected().replace("{expectedGameId}", "ExpectedId"),
                LoginFeedback.from(VerifyResult.gameIdMismatch("ExpectedId"), messages, "P").message());
    }

    @Test
    void rateLimitRoundsRetrySecondsUpAndNeverTriggersRestart() {
        LoginFeedback feedback = LoginFeedback.from(VerifyResult.rateLimited(1500L, null), messages, "P");
        assertEquals(messages.rateLimited().replace("{seconds}", "2"), feedback.message());
        assertFalse(feedback.authenticated());
        assertFalse(feedback.triggerRestart());

        LoginFeedback immediate = LoginFeedback.from(VerifyResult.rateLimited(0L, null), messages, "P");
        assertEquals(messages.rateLimited().replace("{seconds}", "1"), immediate.message());

        LoginFeedback noRetry = LoginFeedback.from(VerifyResult.rateLimited(null, null), messages, "P");
        assertEquals(messages.rateLimitedNoRetry(), noRetry.message());
        assertFalse(noRetry.triggerRestart());
    }

    @Test
    void rateLimitIncludesRemainingAttemptsWhenWebsiteReportsThem() {
        LoginFeedback withRetry = LoginFeedback.from(
                VerifyResult.rateLimited(1500L, 3), messages, "P");
        assertEquals("尝试过多，请 2 秒后再试，剩余 3 次。", withRetry.message());
        assertFalse(withRetry.authenticated());
        assertFalse(withRetry.triggerRestart());

        LoginFeedback withoutRetry = LoginFeedback.from(
                VerifyResult.rateLimited(null, 0), messages, "P");
        assertEquals("尝试过多，请稍后再试，剩余 0 次。", withoutRetry.message());
        assertFalse(withoutRetry.triggerRestart());
    }

    @Test
    void forbiddenAndNotFoundAreUserFacingAndDoNotRestart() {
        LoginFeedback forbidden = LoginFeedback.from(VerifyResult.forbidden(), messages, "P");
        assertFalse(forbidden.authenticated());
        assertFalse(forbidden.triggerRestart(), "403 不应触发健康检查或受控重启");
        assertEquals(messages.forbidden(), forbidden.message());

        LoginFeedback notFound = LoginFeedback.from(VerifyResult.notFound(), messages, "P");
        assertFalse(notFound.authenticated());
        assertFalse(notFound.triggerRestart(), "404 不应触发健康检查或受控重启");
        assertEquals(messages.notFound(), notFound.message());
    }

    @Test
    void invalidCodeMentionsRegistrationWhenPlayerHasNoAccount() {
        assertTrue(messages.invalidCode().contains("QQ 群私聊机器人注册 游戏ID"));
    }

    @Test
    void unreachableStaysClosedAndTriggersControlledRestart() {
        LoginFeedback feedback = LoginFeedback.from(VerifyResult.unreachable(), messages, "P");

        assertFalse(feedback.authenticated());
        assertTrue(feedback.triggerRestart());
        assertEquals(messages.serviceUnavailable(), feedback.message());
    }

    @Test
    void unauthorizedIsTreatedAsServiceMisconfigurationWithoutRestart() {
        LoginFeedback feedback = LoginFeedback.from(VerifyResult.unauthorized(), messages, "P");

        assertFalse(feedback.authenticated());
        assertFalse(feedback.triggerRestart());
        assertEquals(messages.serviceMisconfigured(), feedback.message());
    }
}