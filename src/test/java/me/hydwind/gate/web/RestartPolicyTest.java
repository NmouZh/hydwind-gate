package me.hydwind.gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void remainingSlotsRespectCooldownAndWindow() {
        RestartPolicy policy = new RestartPolicy();
        assertEquals(2, policy.remaining(START));
        assertTrue(policy.tryAcquire(START));
        assertEquals(0, policy.remaining(START.plus(Duration.ofMinutes(1))), "冷却期内没有可用额度");
        assertTrue(policy.nextAllowed(START.plus(Duration.ofMinutes(1))).isPresent());
        assertEquals(1, policy.remaining(START.plus(Duration.ofMinutes(10))));
        assertTrue(policy.tryAcquire(START.plus(Duration.ofMinutes(10))));
        assertEquals(2, policy.remaining(START.plus(Duration.ofHours(2))), "窗口滑出后额度恢复");
    }

    @Test
    void configurablePolicyUsesGivenCooldownAndLimit() {
        RestartPolicy policy = new RestartPolicy(Duration.ofMinutes(2), Duration.ofMinutes(30), 1);
        assertEquals(1, policy.remaining(START));
        assertTrue(policy.tryAcquire(START));
        assertFalse(policy.tryAcquire(START.plus(Duration.ofMinutes(3))), "每小时上限为 1");
        assertTrue(policy.tryAcquire(START.plus(Duration.ofMinutes(30))), "窗口滑出后恢复额度");
    }
}
