package me.hydwind.gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

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
}