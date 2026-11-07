package me.hydwind.gate.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void longValueParsesNumbersAndNumericStringsAndFallsBackForNonStrings() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("integer", 12);
        section.put("long", 12L);
        section.put("numeric-string", " 34 ");
        section.put("null-value", null);
        section.put("boolean", true);

        assertEquals(12L, YamlMaps.longValue(section, "integer", -1L));
        assertEquals(12L, YamlMaps.longValue(section, "long", -1L));
        assertEquals(34L, YamlMaps.longValue(section, "numeric-string", -1L));
        assertEquals(-1L, YamlMaps.longValue(section, "null-value", -1L));
        assertEquals(-1L, YamlMaps.longValue(section, "boolean", -1L));
        assertEquals(-1L, YamlMaps.longValue(section, "missing", -1L));
    }

    @Test
    void longValueRejectsUnparsableStringsInsteadOfSilentlyFallingBack() {
        Map<String, Object> section = Map.of(
                "duration", "600s",
                "blank", "   ");

        IllegalArgumentException duration = assertThrows(IllegalArgumentException.class,
                () -> YamlMaps.longValue(section, "duration", 300L));
        assertEquals("config value 'duration' is not a number: 600s", duration.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> YamlMaps.longValue(section, "blank", 300L),
                "空白字符串同样不是数字，不能再按缺项处理");
    }

    @Test
    void intValuePropagatesTheFailingKey() {
        Map<String, Object> section = Map.of("connect-timeout-millis", "nope");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> YamlMaps.intValue(section, "connect-timeout-millis", 3000));
        assertEquals("config value 'connect-timeout-millis' is not a number: nope",
                exception.getMessage());
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