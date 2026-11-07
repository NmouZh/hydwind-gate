package me.hydwind.gate.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RestartPolicyTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void restartHasTenMinuteCooldownAndDeniedAttemptDoesNotResetIt() {
        RestartPolicy policy = new RestartPolicy();
        assertTrue(policy.tryAcquire(START));
        assertFalse(policy.tryAcquire(START.plus(Duration.ofMinutes(9))));
        assertTrue(policy.tryAcquire(START.plus(Duration.ofMinutes(10))));
    }

    @Test
    void onlyTwoRestartsAreAllowedInARollingHour() {
        RestartPolicy policy = new RestartPolicy();
        assertTrue(policy.tryAcquire(START));
        assertTrue(policy.tryAcquire(START.plus(Duration.ofMinutes(10))));
        assertFalse(policy.tryAcquire(START.plus(Duration.ofMinutes(20))));
        assertTrue(policy.tryAcquire(START.plus(Duration.ofHours(1))));
        assertFalse(policy.tryAcquire(START.plus(Duration.ofHours(1)).plus(Duration.ofMinutes(5))));
        assertTrue(policy.tryAcquire(START.plus(Duration.ofHours(1)).plus(Duration.ofMinutes(10))));
    }
}
