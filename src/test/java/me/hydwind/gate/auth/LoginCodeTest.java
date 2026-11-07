package me.hydwind.gate.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void rejectsBlankCodes() {
        assertTrue(LoginCode.isBlankOrTooLong(""));
        assertTrue(LoginCode.isBlankOrTooLong("  \t  "));
    }

    @Test
    void treatsMissingCodeAsAbsent() {
        assertNull(LoginCode.normalize(null));
        assertTrue(LoginCode.isBlankOrTooLong(null));
    }

    @Test
    void rejectsCodesLongerThanSixtyFourCharacters() {
        assertFalse(LoginCode.isBlankOrTooLong("A".repeat(64)));
        assertTrue(LoginCode.isBlankOrTooLong("A".repeat(65)));
    }
}
