package me.hydwind.gate.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@code {placeholder}} 模板替换工具。null 值替换为空字符串。 */
class TemplatesTest {

    @Test
    void replacesOneOrMorePlaceholders() {
        assertEquals("已认证: 3（其中离线宽限 1）",
                Templates.fill("已认证: {count}（其中离线宽限 {grace}）",
                        "count", 3, "grace", 1));
    }

    @Test
    void replacesAllOccurrencesOfTheSamePlaceholder() {
        assertEquals("A/A", Templates.fill("{x}/{x}", "x", "A"));
    }

    @Test
    void nullValueBecomesEmptyString() {
        assertEquals("玩家: ", Templates.fill("玩家: {player}", "player", null));
    }

    @Test
    void unknownPlaceholdersAreLeftAlone() {
        assertEquals("你好 {unknown}", Templates.fill("你好 {unknown}", "name", "X"));
    }

    @Test
    void placeholderValueMustBePaired() {
        assertThrows(IllegalArgumentException.class, () -> Templates.fill("{x}", "x"));
    }
}