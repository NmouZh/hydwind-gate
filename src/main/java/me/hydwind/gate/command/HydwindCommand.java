package me.hydwind.gate.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import me.hydwind.gate.HydwindGatePlugin;

/**
 * Velocity {@code CommandManager} 注册的 {@code /hydwind} 命令。
 *
 * <p>只负责把参数转交给 {@link AdminCommandExecutor}；子命令列表与补全规则来自
 * {@link CommandGrammar}，权限判断只在这里做一次。</p>
 */
public final class HydwindCommand implements SimpleCommand {

    private final HydwindGatePlugin plugin;
    private final AdminCommandExecutor executor;

    public HydwindCommand(HydwindGatePlugin plugin, AdminCommandExecutor executor) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public void execute(Invocation invocation) {
        executor.execute(invocation.source(), CommandGrammar.parseArgs(invocation.arguments()));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!invocation.source().hasPermission(plugin.pluginConfig().adminPermission())) {
            return List.of();
        }

        String[] args = invocation.arguments();
        if (args.length == 0) {
            return CommandGrammar.suggestSubcommands("");
        }
        if (args.length == 1) {
            return CommandGrammar.suggestSubcommands(args[0]);
        }
        if (args.length == 2
                && CommandGrammar.requiresTarget(CommandGrammar.parseArgs(args[0]).type())) {
            return playerNames(args[1]);
        }
        return List.of();
    }

    private List<String> playerNames(String prefix) {
        String folded = prefix.toLowerCase(Locale.ROOT);
        return plugin.proxyServer().getAllPlayers().stream()
                .map(Player::getUsername)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(folded))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}
