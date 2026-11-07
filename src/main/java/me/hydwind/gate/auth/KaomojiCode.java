package me.hydwind.gate.auth;

import java.text.Normalizer;

/** Basic client-side preparation of website-issued login codes. */
public final class KaomojiCode {

    private static final int MAX_LENGTH = 64;

    private KaomojiCode() {
    }

    /** Normalizes Unicode and folds only ASCII letters, leaving kaomoji untouched. */
    public static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String normalized = Normalizer.normalize(code.strip(), Normalizer.Form.NFC);
        StringBuilder result = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char character = normalized.charAt(i);
            result.append(character >= 'a' && character <= 'z' ? (char) (character - ('a' - 'A')) : character);
        }
        return result.toString();
    }

    /** Rejects absent, blank, or oversized codes before sending them to the website. */
    public static boolean isBlankOrTooLong(String code) {
        String normalized = normalize(code);
        return normalized == null || normalized.isBlank() || normalized.codePointCount(0, normalized.length()) > MAX_LENGTH;
    }
}
