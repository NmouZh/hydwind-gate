package me.hydwind.gate.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void rejectsCodesLongerThanSixtyFourCharacters() {
        assertFalse(KaomojiCode.isBlankOrTooLong("A".repeat(64)));
        assertTrue(KaomojiCode.isBlankOrTooLong("A".repeat(65)));
    }

    @Test
    void recognizesFallbackCodeRegardlessOfAsciiCase() {
        assertTrue(KaomojiCode.looksLikeFallback("hwg-aB2c"));
        assertTrue(KaomojiCode.looksLikeFallback("  HWG-AB2C  "));
        assertFalse(KaomojiCode.looksLikeFallback("HWG-ABC"));
        assertFalse(KaomojiCode.looksLikeFallback("HWG-ABCDE"));
        assertFalse(KaomojiCode.looksLikeFallback("(・ω・)(´▽｀)"));
    }
}
