package me.hydwind.gate.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class LoginCommandTest {

    @Test
    void parsesSlashPrefixedCommandWithCode() {
        LoginCommand command = LoginCommand.parse("/login HWG-AB2C");

        assertEquals(LoginCommand.Status.LOGIN, command.status());
        assertEquals("HWG-AB2C", command.code());
    }

    @Test
    void acceptsCommandWithoutSlashAndFoldsCommandCase() {
        LoginCommand command = LoginCommand.parse("LOGIN   hwg-ab2c");

        assertEquals(LoginCommand.Status.LOGIN, command.status());
        assertEquals("hwg-ab2c", command.code());
    }

    @Test
    void preservesKaomojiCodeContainingSpaces() {
        LoginCommand command = LoginCommand.parse("/login (・ω・) (´▽｀)");

        assertEquals(LoginCommand.Status.LOGIN, command.status());
        assertEquals("(・ω・) (´▽｀)", command.code());
    }

    @Test
    void reportsEmptyCodeForBareCommand() {
        assertEquals(LoginCommand.Status.EMPTY_CODE, LoginCommand.parse("/login").status());
        assertEquals(LoginCommand.Status.EMPTY_CODE, LoginCommand.parse("/login    ").status());
        assertNull(LoginCommand.parse("/login").code());
    }

    @Test
    void ignoresChatAndOtherCommands() {
        assertEquals(LoginCommand.Status.NOT_LOGIN, LoginCommand.parse("/register x").status());
        assertEquals(LoginCommand.Status.NOT_LOGIN, LoginCommand.parse("hello there").status());
        assertEquals(LoginCommand.Status.NOT_LOGIN, LoginCommand.parse("").status());
        assertEquals(LoginCommand.Status.NOT_LOGIN, LoginCommand.parse(null).status());
    }

    @Test
    void rejectsCodesAboveTheChatSafeLimit() {
        assertEquals(LoginCommand.Status.LOGIN,
                LoginCommand.parse("/login " + "A".repeat(LoginCommand.MAX_CODE_LENGTH)).status());
        assertEquals(LoginCommand.Status.TOO_LONG,
                LoginCommand.parse("/login " + "A".repeat(LoginCommand.MAX_CODE_LENGTH + 1)).status());
    }
}