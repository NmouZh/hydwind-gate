package me.hydwind.gate.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * limbo 聊天与命令共用的命令行解析：去掉可选前导斜杠，按第一段空白拆出命令与余下文本。
 */
class CommandLineTest {

    @Test
    void stripsOptionalLeadingSlashAndWhitespace() {
        CommandLine line = CommandLine.parse("  /login HWG-AB2C  ");

        assertEquals("login", line.command());
        assertEquals("HWG-AB2C", line.remainder());
    }

    @Test
    void preservesRemainderSpacesForKaomojiCodes() {
        CommandLine line = CommandLine.parse("login (・ω・) (´▽｀)");

        assertEquals("login", line.command());
        assertEquals("(・ω・) (´▽｀)", line.remainder());
    }

    @Test
    void commandWithoutArgumentsHasEmptyRemainder() {
        CommandLine line = CommandLine.parse("/hydwind");

        assertEquals("hydwind", line.command());
        assertEquals("", line.remainder());
        assertArrayEquals(new String[0], line.splitArguments());
    }

    @Test
    void splitArgumentsCollapsesWhitespace() {
        CommandLine line = CommandLine.parse("/hydwind   approve   Alice");

        assertArrayEquals(new String[]{"approve", "Alice"}, line.splitArguments());
    }

    @Test
    void blankOrNullInputHasNoCommand() {
        assertNull(CommandLine.parse(null));
        assertNull(CommandLine.parse(""));
        assertNull(CommandLine.parse("   "));
        assertNull(CommandLine.parse("/   "));
    }
}