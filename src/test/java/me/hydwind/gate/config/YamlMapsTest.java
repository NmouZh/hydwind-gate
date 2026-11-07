package me.hydwind.gate.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SnakeYAML 结果的读取规则：缺项、类型错误和空字符串都有安全回退。 */
class YamlMapsTest {

    @Test
    void sectionReturnsEmptyMapForMissingOrNonMapValues() {
        assertTrue(YamlMaps.section(Map.of(), "website").isEmpty());
        assertTrue(YamlMaps.section(Map.of("website", "not-a-map"), "website").isEmpty());
        assertTrue(YamlMaps.section(Map.of("website", 42), "website").isEmpty());
    }

    @Test
    void sectionCopiesEntriesAndNormalizesKeysToStrings() {
        Map<Object, Object> raw = new LinkedHashMap<>();
        raw.put(1, "one");
        raw.put("two", 2);
        Map<String, Object> section = YamlMaps.section(Map.<String, Object>of("section", raw), "section");

        assertEquals("one", section.get("1"));
        assertEquals(2, section.get("two"));
    }

    @Test
    void rawStringKeepsWhitespaceWhileTextTrimsAndFallsBackOnBlank() {
        Map<String, Object> section = Map.of(
                "raw", "  padded  ",
                "blank", "   ",
                "number", 7);

        assertEquals("  padded  ", YamlMaps.rawString(section, "raw", "fallback"));
        assertEquals("fallback", YamlMaps.rawString(section, "missing", "fallback"));
        assertEquals("7", YamlMaps.rawString(section, "number", "fallback"));
        assertEquals("fallback", YamlMaps.rawString(section, "null-value", "fallback"));

        assertEquals("padded", YamlMaps.text(section, "raw", "fallback"));
        assertEquals("fallback", YamlMaps.text(section, "blank", "fallback"));
        assertEquals("7", YamlMaps.text(section, "number", "fallback"));
    }

    @Test
    void longValueParsesNumbersAndNumericStringsAndFallsBackOtherwise() {
        Map<String, Object> section = Map.of(
                "integer", 12,
                "long", 12L,
                "numeric-string", " 34 ",
                "bad-string", "abc",
                "boolean", true);

        assertEquals(12L, YamlMaps.longValue(section, "integer", -1L));
        assertEquals(12L, YamlMaps.longValue(section, "long", -1L));
        assertEquals(34L, YamlMaps.longValue(section, "numeric-string", -1L));
        assertEquals(-1L, YamlMaps.longValue(section, "bad-string", -1L));
        assertEquals(-1L, YamlMaps.longValue(section, "boolean", -1L));
        assertEquals(-1L, YamlMaps.longValue(section, "missing", -1L));
    }

    @Test
    void intValueSaturatesAtIntegerMaxInsteadOfOverflowing() {
        Map<String, Object> section = Map.of(
                "too-large", Long.MAX_VALUE,
                "negative", -5L);

        assertEquals(Integer.MAX_VALUE, YamlMaps.intValue(section, "too-large", 0));
        assertEquals(-5, YamlMaps.intValue(section, "negative", 0));
    }
}