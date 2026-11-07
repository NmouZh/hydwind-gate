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