package me.hydwind.gate.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Velocity 版本兼容判断。只比较点分数字部分，忽略 {@code -SNAPSHOT} 等后缀，
 * 缺失的段按 0 处理。
 */
class VersionCompatTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "4.1.2",
            "4.1.2-SNAPSHOT",
            "4.1.2+build.7",
            "4.1.10",
            "4.2.0",
            "4.2",
            "5.0.0",
            " 4.1.2 "
    })
    void acceptsVelocityAtLeastFourOneTwo(String version) {
        assertTrue(VersionCompat.isSupportedVelocity(version), version);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "4.1.1",
            "4.1",
            "4.0.9",
            "3.9.9",
            "",
            "not-a-version",
            "-4.1.2"
    })
    void rejectsVersionsBelowTheMinimumOrUnparsable(String version) {
        assertFalse(VersionCompat.isSupportedVelocity(version), version);
    }

    @Test
    void handlesNullAsUnsupported() {
        assertFalse(VersionCompat.isSupportedVelocity(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"4.1.2", "4.1.2-SNAPSHOT"})
    void comparesAgainstAnExplicitMinimum(String actual) {
        assertTrue(VersionCompat.isAtLeast(actual, "4.1.2"));
        assertFalse(VersionCompat.isAtLeast(actual, "4.1.3"));
    }
}