package me.hydwind.gate.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoginSessionStoreTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final String IP = "192.0.2.1";

    @Test
    void joinStartsPendingAndSuccessfulVerificationAuthenticates() {
        LoginSessionStore store = new LoginSessionStore();

        LoginSession pending = store.join(PLAYER, "PlayerOne", IP, START);
        assertEquals(LoginSession.Status.PENDING, pending.status());
        assertEquals(PLAYER, pending.uuid());
        assertEquals("PlayerOne", pending.gameId());
        assertEquals(IP, pending.ip());
        assertEquals(START, pending.enteredAt());
        assertEquals(START, pending.lastActivityAt());

        assertTrue(store.beginVerification(PLAYER, START.plusSeconds(30)));
        assertEquals(LoginSession.Status.VERIFYING, store.find(PLAYER).orElseThrow().status());
        assertEquals(START.plusSeconds(30), store.find(PLAYER).lastActivityAt());
        assertFalse(store.beginVerification(PLAYER, START.plusSeconds(31)));

        assertTrue(store.authenticationSucceeded(PLAYER, START.plusSeconds(35)));
        assertEquals(LoginSession.Status.AUTHENTICATED, store.find(PLAYER).orElseThrow().status());
        assertEquals(START.plusSeconds(35), store.find(PLAYER).lastActivityAt());
        assertFalse(store.beginVerification(PLAYER, START.plusSeconds(36)));
    }

    @Test
    void failedVerificationCanRetryButStaleResultCannotUndoSuccess() {
        LoginSessionStore store = new LoginSessionStore();
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
        LoginSessionStore store = new LoginSessionStore();
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
        LoginSessionStore store = new LoginSessionStore();
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
        LoginSessionStore store = new LoginSessionStore();
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
        LoginSessionStore store = new LoginSessionStore();
        store.join(PLAYER, "PlayerOne", IP, START);
        store.quit(PLAYER, START.plusSeconds(10));
        assertEquals(LoginSession.Status.PENDING, store.join(PLAYER, "PlayerOne", IP, START.plusSeconds(11)).status());

        store.beginVerification(PLAYER, START.plusSeconds(12));
        store.authenticationSucceeded(PLAYER, START.plusSeconds(13));
        store.quit(PLAYER, START.plusSeconds(14));
        assertEquals(LoginSession.Status.PENDING,
                new LoginSessionStore().join(PLAYER, "PlayerOne", IP, START.plusSeconds(15)).status());
        assertTrue(store.find(PLAYER).isPresent());
    }
}
