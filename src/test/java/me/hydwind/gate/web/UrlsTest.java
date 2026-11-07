package me.hydwind.gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlsTest {

    @Test
    void stripsTrailingSlashes() {
        assertEquals("http://example.test", Urls.stripTrailingSlashes("http://example.test/"));
        assertEquals("http://example.test", Urls.stripTrailingSlashes("http://example.test///"));
    }

    @Test
    void leavesUrlWithoutTrailingSlashUntouched() {
        assertEquals("http://example.test/gate", Urls.stripTrailingSlashes("http://example.test/gate"));
    }

    @Test
    void handlesNull() {
        assertNull(Urls.stripTrailingSlashes(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.test",
            "https://example.test/",
            "http://127.0.0.1:62179",
            "https://example.test/gate/",
            "HTTP://EXAMPLE.TEST",
            "http://[::1]:8080"
    })
    void acceptsAbsoluteHttpAndHttpsUrls(String url) {
        assertTrue(Urls.isAbsoluteHttpUrl(url), () -> "应接受合法根地址: " + url);
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertTrue(Urls.isAbsoluteHttpUrl("  https://example.test/gate  "));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "example.test",
            "/gate",
            "ftp://example.test",
            "file:///tmp/gate",
            "javascript:alert(1)",
            "http://",
            "http:///gate",
            "http:/example.test",
            "http://example.test?x=1",
            "http://example.test#fragment",
            "http://user:pass@example.test"
    })
    void rejectsNonAbsoluteHttpOrUnsafeUrls(String url) {
        assertFalse(Urls.isAbsoluteHttpUrl(url), () -> "应拒绝非法根地址: " + url);
    }

    @Test
    void rejectsNullBaseUrl() {
        assertFalse(Urls.isAbsoluteHttpUrl(null));
    }
}