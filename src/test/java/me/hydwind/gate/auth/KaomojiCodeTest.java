package me.hydwind.gate.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KaomojiCodeTest {

    @Test
    void normalizesComposedAndDecomposedCharactersToTheSameCode() {
        assertEquals("é(・ω・)", KaomojiCode.normalize("é(・ω・)"));
    }

    @Test
    void removesSurroundingWhitespaceWithoutChangingTheCodeInside() {
        assertEquals("(・ω・) (´▽｀)", KaomojiCode.normalize("  (・ω・) (´▽｀)  "));
    }

    @Test
    void foldsAsciiCaseInFallbackCodes() {
        assertEquals("HWG-AB2C", KaomojiCode.normalize("hwg-aB2c"));
    }

    @Test
    void rejectsBlankCodes() {
        assertTrue(KaomojiCode.isBlankOrTooLong(""));
        assertTrue(KaomojiCode.isBlankOrTooLong("  \t  "));
    }

    @Test
    void treatsMissingCodeAsAbsent() {
        assertNull(KaomojiCode.normalize(null));
        assertTrue(KaomojiCode.isBlankOrTooLong(null));
    }

    @Test
    void rejectsCodesLongerThanSixtyFourCharacters() {
        assertFalse(KaomojiCode.isBlankOrTooLong("A".repeat(64)));
        assertTrue(KaomojiCode.isBlankOrTooLong("A".repeat(65)));
    }
}
