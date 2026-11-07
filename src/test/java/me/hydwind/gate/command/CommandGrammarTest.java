package me.hydwind.gate.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link CommandGrammar} 的解析、补全与 requiresTarget 契约。
 *
 * <p>Velocity CommandManager 与 limbo {@code onChat} 共用这套规则。</p>
 */
class CommandGrammarTest {

    // ---------- SPECS 与枚举的一致性 ----------

    @Test
    void specsListSubcommandsInCompletionOrder() {
        assertEquals(List.of(
                new CommandGrammar.Spec("status", false),
                new CommandGrammar.Spec("reload", false),
                new CommandGrammar.Spec("approve", true),
                new CommandGrammar.Spec("kick", true)), CommandGrammar.SPECS);
    }

    @Test
    void specsAndTypeEnumAreOneToOne() {
        Set<String> specTypes = CommandGrammar.SPECS.stream()
                .map(spec -> spec.name().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        Set<String> enumTypes = Arrays.stream(AdminCommandRequest.Type.values())
                .filter(type -> type != AdminCommandRequest.Type.USAGE)
                .filter(type -> type != AdminCommandRequest.Type.UNKNOWN)
                .map(Enum::name)
                .collect(Collectors.toSet());

        // 新增 Type 忘记加 Spec（或反之）时这里会失败。
        assertEquals(enumTypes, specTypes);
        assertEquals(CommandGrammar.SPECS.size(), specTypes.size(), "子命令名不能重复");
    }

    @ParameterizedTest
    @EnumSource(value = AdminCommandRequest.Type.class,
            names = {"STATUS", "RELOAD", "APPROVE", "KICK"})
    void everySubcommandTypeHasASpec(AdminCommandRequest.Type type) {
        String name = type.name().toLowerCase(Locale.ROOT);
        assertTrue(CommandGrammar.SPECS.stream().anyMatch(spec -> spec.name().equals(name)),
                () -> "缺少 Spec: " + name);
    }

    // ---------- parseArgs ----------

    @Test
    void emptyArgumentsAreUsage() {
        assertEquals(AdminCommandRequest.Type.USAGE, CommandGrammar.parseArgs().type());
        assertEquals(AdminCommandRequest.Type.USAGE, CommandGrammar.parseArgs(new String[0]).type());
        assertEquals(AdminCommandRequest.Type.USAGE, CommandGrammar.parseArgs((String[]) null).type());
        assertEquals(AdminCommandRequest.Type.USAGE, CommandGrammar.parseArgs((String) null).type());
        assertEquals(AdminCommandRequest.Type.USAGE, CommandGrammar.parseArgs("  ").type());
        assertEquals(AdminCommandRequest.Type.USAGE, CommandGrammar.parseArgs("\t").type());
    }

    @Test
    void statusAndReloadTakeNoTarget() {
        AdminCommandRequest status = CommandGrammar.parseArgs("status");
        assertEquals(AdminCommandRequest.Type.STATUS, status.type());
        assertNull(status.target());

        AdminCommandRequest reload = CommandGrammar.parseArgs("reload");
        assertEquals(AdminCommandRequest.Type.RELOAD, reload.type());
        assertNull(reload.target());
    }

    @Test
    void subcommandCaseIsFolded() {
        assertEquals(AdminCommandRequest.Type.STATUS, CommandGrammar.parseArgs("STATUS").type());
        assertEquals(AdminCommandRequest.Type.RELOAD, CommandGrammar.parseArgs(" Reload ").type());
        assertEquals(AdminCommandRequest.Type.APPROVE,
                CommandGrammar.parseArgs("APPROVE", "Alice").type());
        assertEquals(AdminCommandRequest.Type.KICK, CommandGrammar.parseArgs("Kick", "Bob").type());
    }

    @Test
    void statusAndReloadRejectExtraArguments() {
        assertEquals(AdminCommandRequest.Type.USAGE, CommandGrammar.parseArgs("status", "x").type());
        assertEquals(AdminCommandRequest.Type.USAGE, CommandGrammar.parseArgs("reload", "x").type());
        assertEquals(AdminCommandRequest.Type.USAGE,
                CommandGrammar.parseArgs("status", "x", "y").type());
    }

    @Test
    void approveAndKickCarryTheTarget() {
        AdminCommandRequest approve = CommandGrammar.parseArgs("approve", "Alice");
        assertEquals(AdminCommandRequest.Type.APPROVE, approve.type());
        assertEquals("Alice", approve.target());

        AdminCommandRequest kick = CommandGrammar.parseArgs("kick", "Bob");
        assertEquals(AdminCommandRequest.Type.KICK, kick.type());
        assertEquals("Bob", kick.target());
    }

    @Test
    void targetIsTrimmedAndBlankBecomesNull() {
        assertEquals("Alice", CommandGrammar.parseArgs("approve", "  Alice  ").target());
        assertEquals("Alice", CommandGrammar.parseArgs("approve", "\tAlice").target());

        AdminCommandRequest blank = CommandGrammar.parseArgs("approve", "   ");
        assertEquals(AdminCommandRequest.Type.APPROVE, blank.type());
        assertNull(blank.target());

        AdminCommandRequest nullTarget = CommandGrammar.parseArgs("kick", (String) null);
        assertEquals(AdminCommandRequest.Type.KICK, nullTarget.type());
        assertNull(nullTarget.target());
    }

    @Test
    void missingTargetIsKeptForCommandSpecificUsage() {
        AdminCommandRequest approve = CommandGrammar.parseArgs("approve");
        assertEquals(AdminCommandRequest.Type.APPROVE, approve.type());
        assertNull(approve.target());

        AdminCommandRequest kick = CommandGrammar.parseArgs("kick");
        assertEquals(AdminCommandRequest.Type.KICK, kick.type());
        assertNull(kick.target());
    }

    @Test
    void tooManyArgumentsFallBackToUsage() {
        assertEquals(AdminCommandRequest.Type.USAGE,
                CommandGrammar.parseArgs("approve", "Alice", "extra").type());
        assertEquals(AdminCommandRequest.Type.USAGE,
                CommandGrammar.parseArgs("kick", "Bob", "extra").type());
    }

    @Test
    void unknownSubcommandIsReported() {
        assertEquals(AdminCommandRequest.Type.UNKNOWN, CommandGrammar.parseArgs("wat").type());
        assertEquals(AdminCommandRequest.Type.UNKNOWN, CommandGrammar.parseArgs("wat", "x").type());
        // 前缀近似不算匹配。
        assertEquals(AdminCommandRequest.Type.UNKNOWN, CommandGrammar.parseArgs("stat").type());
    }

    // ---------- parseLine ----------

    @Test
    void parseLineIgnoresOtherLines() {
        assertEquals(Optional.empty(), CommandGrammar.parseLine("/login ABC"));
        assertEquals(Optional.empty(), CommandGrammar.parseLine("hello there"));
        assertEquals(Optional.empty(), CommandGrammar.parseLine(""));
        assertEquals(Optional.empty(), CommandGrammar.parseLine("   "));
        assertEquals(Optional.empty(), CommandGrammar.parseLine("/"));
        assertEquals(Optional.empty(), CommandGrammar.parseLine((String) null));
    }

    @Test
    void commandPrefixMustBeExactlyHydwind() {
        assertEquals(Optional.empty(), CommandGrammar.parseLine("/hydwindx status"));
        assertEquals(Optional.empty(), CommandGrammar.parseLine("not-hydwind status"));
        assertEquals(Optional.empty(), CommandGrammar.parseLine("hydwindx"));
    }

    @Test
    void parseLineHandlesSlashWhitespaceAndFoldedCase() {
        assertEquals(Optional.of(AdminCommandRequest.Type.STATUS),
                CommandGrammar.parseLine("/hydwind status").map(AdminCommandRequest::type));
        assertEquals(Optional.of(AdminCommandRequest.Type.STATUS),
                CommandGrammar.parseLine("  HYDWIND   STATUS  ").map(AdminCommandRequest::type));
        assertEquals(Optional.of(AdminCommandRequest.Type.RELOAD),
                CommandGrammar.parseLine("hydwind reload").map(AdminCommandRequest::type));

        Optional<AdminCommandRequest> approve = CommandGrammar.parseLine("/hydwind approve Alice");
        assertTrue(approve.isPresent());
        assertEquals(AdminCommandRequest.Type.APPROVE, approve.get().type());
        assertEquals("Alice", approve.get().target());
    }

    @Test
    void bareHydwindLineIsUsage() {
        assertEquals(Optional.of(AdminCommandRequest.Type.USAGE),
                CommandGrammar.parseLine("/hydwind").map(AdminCommandRequest::type));
        assertEquals(Optional.of(AdminCommandRequest.Type.USAGE),
                CommandGrammar.parseLine("/hydwind   ").map(AdminCommandRequest::type));
        assertEquals(Optional.of(AdminCommandRequest.Type.USAGE),
                CommandGrammar.parseLine("hydwind").map(AdminCommandRequest::type));
    }

    @Test
    void parseLineCarriesParseArgsSemantics() {
        assertEquals(AdminCommandRequest.Type.USAGE,
                CommandGrammar.parseLine("/hydwind status extra").orElseThrow().type());
        assertEquals(AdminCommandRequest.Type.UNKNOWN,
                CommandGrammar.parseLine("/hydwind wat").orElseThrow().type());
        assertEquals(AdminCommandRequest.Type.APPROVE,
                CommandGrammar.parseLine("/hydwind approve").orElseThrow().type());
        assertNull(CommandGrammar.parseLine("/hydwind kick").orElseThrow().target());
    }

    // ---------- suggestSubcommands ----------

    @Test
    void emptyPrefixSuggestsEverythingInSpecOrder() {
        List<String> expected = CommandGrammar.SPECS.stream().map(CommandGrammar.Spec::name).toList();
        assertEquals(expected, CommandGrammar.suggestSubcommands(""));
        assertEquals(expected, CommandGrammar.suggestSubcommands(null));
    }

    @Test
    void suggestionsUseCaseInsensitivePrefix() {
        assertEquals(List.of("status"), CommandGrammar.suggestSubcommands("s"));
        assertEquals(List.of("status"), CommandGrammar.suggestSubcommands("S"));
        assertEquals(List.of("status"), CommandGrammar.suggestSubcommands("ST"));
        assertEquals(List.of("reload"), CommandGrammar.suggestSubcommands("R"));
        assertEquals(List.of("approve"), CommandGrammar.suggestSubcommands("a"));
        assertEquals(List.of("kick"), CommandGrammar.suggestSubcommands("K"));
        assertEquals(List.of(), CommandGrammar.suggestSubcommands("x"));
        // 补全的是命令名，不是整段输入。
        assertEquals(List.of(), CommandGrammar.suggestSubcommands("/s"));
    }

    @Test
    void exactAndTrailingSpacePrefixesBehaveAsPlainPrefixes() {
        assertEquals(List.of("approve"), CommandGrammar.suggestSubcommands("approve"));
        assertEquals(List.of(), CommandGrammar.suggestSubcommands("status "));
        assertEquals(List.of(), CommandGrammar.suggestSubcommands("statuses"));
    }

    // ---------- requiresTarget ----------

    @Test
    void requiresTargetMatchesSpecs() {
        for (CommandGrammar.Spec spec : CommandGrammar.SPECS) {
            AdminCommandRequest.Type type =
                    AdminCommandRequest.Type.valueOf(spec.name().toUpperCase(Locale.ROOT));
            assertEquals(spec.requiresTarget(), CommandGrammar.requiresTarget(type),
                    () -> "requiresTarget 不一致: " + spec.name());
        }

        assertFalse(CommandGrammar.requiresTarget(AdminCommandRequest.Type.STATUS));
        assertFalse(CommandGrammar.requiresTarget(AdminCommandRequest.Type.RELOAD));
        assertTrue(CommandGrammar.requiresTarget(AdminCommandRequest.Type.APPROVE));
        assertTrue(CommandGrammar.requiresTarget(AdminCommandRequest.Type.KICK));
    }

    @Test
    void nonSubcommandTypesDoNotRequireTarget() {
        assertFalse(CommandGrammar.requiresTarget(AdminCommandRequest.Type.USAGE));
        assertFalse(CommandGrammar.requiresTarget(AdminCommandRequest.Type.UNKNOWN));
    }

    @Test
    void requiresTargetRejectsNull() {
        assertThrows(NullPointerException.class, () -> CommandGrammar.requiresTarget(null));
    }
}
