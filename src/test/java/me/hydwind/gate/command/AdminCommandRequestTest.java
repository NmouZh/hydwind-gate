package me.hydwind.gate.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        assertEquals(AdminCommandRequest.Type.NONE, AdminCommandRequest.parseLine("/login ABC"));
        assertEquals(AdminCommandRequest.Type.NONE, AdminCommandRequest.parseLine("hello there"));
        assertEquals(AdminCommandRequest.Type.NONE, AdminCommandRequest.parseLine(""));
        assertEquals(AdminCommandRequest.Type.NONE, AdminCommandRequest.parseLine(null));
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
}