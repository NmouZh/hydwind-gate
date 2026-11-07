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
 * <p>只负责把参数转交给 {@link AdminCommandExecutor}；权限判断与输出也在执行器中，
 * 以便 limbo 内的 {@code onChat} 复用完全相同的逻辑。</p>
 */
public final class HydwindCommand implements SimpleCommand {

    private static final List<String> SUBCOMMANDS = List.of("status", "reload", "approve", "kick");

    private final HydwindGatePlugin plugin;
    private final AdminCommandExecutor executor;

    public HydwindCommand(HydwindGatePlugin plugin, AdminCommandExecutor executor) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public void execute(Invocation invocation) {
        executor.execute(invocation.source(),
                AdminCommandRequest.parseArgs(invocation.arguments()));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!invocation.source().hasPermission(plugin.pluginConfig().adminPermission())) {
            return List.of();
        }

        String[] args = invocation.arguments();
        if (args.length == 0) {
            return SUBCOMMANDS;
        }
        if (args.length == 1) {
            return filterSubcommands(args[0]);
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if ((subcommand.equals("approve") || subcommand.equals("kick")) && args.length == 2) {
            return playerNames(args[1]);
        }
        return List.of();
    }

    private static List<String> filterSubcommands(String prefix) {
        String folded = prefix.toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream().filter(name -> name.startsWith(folded)).toList();
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