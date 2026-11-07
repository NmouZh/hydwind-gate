package me.hydwind.gate.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@code /hydwind} 的纯文本解析。CommandManager 与 limbo {@code onChat}
 * 两个入口共用同一套解析规则。
 */
class AdminCommandRequestTest {

    @Test
    void emptyInputIsUsage() {
        assertEquals(AdminCommandRequest.Type.USAGE, AdminCommandRequest.parseArgs().type());
        assertEquals(AdminCommandRequest.Type.USAGE, AdminCommandRequest.parseArgs("  ").type());
    }

    @Test
    void statusAndReloadTakeNoTarget() {
        AdminCommandRequest status = AdminCommandRequest.parseArgs("status");
        assertEquals(AdminCommandRequest.Type.STATUS, status.type());
        assertNull(status.target());

        AdminCommandRequest reload = AdminCommandRequest.parseArgs("reload");
        assertEquals(AdminCommandRequest.Type.RELOAD, reload.type());
        assertNull(reload.target());
    }

    @Test
    void approveAndKickCarryTheTargetAndFoldSubcommandCase() {
        AdminCommandRequest approve = AdminCommandRequest.parseArgs("APPROVE", "Alice");
        assertEquals(AdminCommandRequest.Type.APPROVE, approve.type());
        assertEquals("Alice", approve.target());

        AdminCommandRequest kick = AdminCommandRequest.parseArgs("Kick", "Bob");
        assertEquals(AdminCommandRequest.Type.KICK, kick.type());
        assertEquals("Bob", kick.target());
    }

    @Test
    void missingTargetIsKeptForCommandSpecificUsage() {
        AdminCommandRequest approve = AdminCommandRequest.parseArgs("approve");
        assertEquals(AdminCommandRequest.Type.APPROVE, approve.type());
        assertNull(approve.target());

        AdminCommandRequest kick = AdminCommandRequest.parseArgs("kick");
        assertEquals(AdminCommandRequest.Type.KICK, kick.type());
        assertNull(kick.target());
    }

    @Test
    void extraArgumentsFallBackToUsage() {
        assertEquals(AdminCommandRequest.Type.USAGE, AdminCommandRequest.parseArgs("status", "x").type());
        assertEquals(AdminCommandRequest.Type.USAGE, AdminCommandRequest.parseArgs("reload", "x").type());
        assertEquals(AdminCommandRequest.Type.USAGE,
                AdminCommandRequest.parseArgs("approve", "Alice", "extra").type());
        assertEquals(AdminCommandRequest.Type.USAGE,
                AdminCommandRequest.parseArgs("kick", "Bob", "extra").type());
    }

    @Test
    void unknownSubcommandIsReported() {
        assertEquals(AdminCommandRequest.Type.UNKNOWN, AdminCommandRequest.parseArgs("wat").type());
    }

    @Test
    void parseLineOnlyReactsToHydwind() {
        assertEquals(AdminCommandRequest.Type.NONE, AdminCommandRequest.parseLine("/login ABC").type());
        assertEquals(AdminCommandRequest.Type.NONE, AdminCommandRequest.parseLine("hello there").type());
        assertEquals(AdminCommandRequest.Type.NONE, AdminCommandRequest.parseLine("").type());
        assertEquals(AdminCommandRequest.Type.NONE, AdminCommandRequest.parseLine(null).type());
    }

    @Test
    void parseLineHandlesSlashWhitespaceAndFoldedCase() {
        assertEquals(AdminCommandRequest.Type.STATUS,
                AdminCommandRequest.parseLine("/hydwind status").type());
        assertEquals(AdminCommandRequest.Type.STATUS,
                AdminCommandRequest.parseLine("  HYDWIND   STATUS  ").type());
        assertEquals(AdminCommandRequest.Type.APPROVE,
                AdminCommandRequest.parseLine("/hydwind approve Alice").type());
        assertEquals("Alice",
                AdminCommandRequest.parseLine("/hydwind approve Alice").target());
    }

    @Test
    void bareHydwindLineIsUsage() {
        assertEquals(AdminCommandRequest.Type.USAGE, AdminCommandRequest.parseLine("/hydwind").type());
        assertEquals(AdminCommandRequest.Type.USAGE, AdminCommandRequest.parseLine("/hydwind   ").type());
    }

    @Test
    void onlyApproveAndKickNeedAPlayerName() {
        assertTrue(AdminCommandRequest.parseArgs("approve", "Alice").needsPlayerName());
        assertTrue(AdminCommandRequest.parseArgs("kick", "Bob").needsPlayerName());
        assertFalse(AdminCommandRequest.parseArgs("status").needsPlayerName());
        assertFalse(AdminCommandRequest.parseArgs("reload").needsPlayerName());
        assertFalse(AdminCommandRequest.parseArgs().needsPlayerName());
        assertFalse(AdminCommandRequest.parseArgs("unknown").needsPlayerName());
    }

    @Test
    void commandPrefixMustBeExactlyHydwind() {
        assertEquals(AdminCommandRequest.Type.NONE,
                AdminCommandRequest.parseLine("/hydwindx status").type());
        assertEquals(AdminCommandRequest.Type.NONE,
                AdminCommandRequest.parseLine("not-hydwind status").type());
    }
}