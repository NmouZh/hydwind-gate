package me.hydwind.gate.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class LoginCodeTest {

    @Test
    void normalizesComposedAndDecomposedCharactersToTheSameCode() {
        assertEquals("é(・ω・)", LoginCode.normalize("é(・ω・)"));
    }

    @Test
    void removesSurroundingWhitespaceWithoutChangingTheCodeInside() {
        assertEquals("(・ω・) (´▽｀)", LoginCode.normalize("  (・ω・) (´▽｀)  "));
    }

    @Test
    void foldsAsciiCaseInFallbackCodes() {
        assertEquals("HWG-AB2C", LoginCode.normalize("hwg-aB2c"));
    }

    @Test
    void treatsMissingCodeAsAbsent() {
        assertNull(LoginCode.normalize(null));
    }
}
