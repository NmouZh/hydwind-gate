package me.hydwind.gate.command;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import me.hydwind.gate.auth.AdminService;
import me.hydwind.gate.auth.AdminService.Result;
import me.hydwind.gate.auth.AdminService.Status;
import me.hydwind.gate.ui.AdminMessages;
import me.hydwind.gate.ui.Messages;
import me.hydwind.gate.web.HealthResult;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * {@code /hydwind <status|reload|approve <玩家>|kick <玩家>>} 管理员命令。
 *
 * <p>权限默认为 {@code hydwind.admin}，可通过 {@code admin.permission} 配置。
 * 状态探活会访问网站，放在异步线程执行；结果再切回主线程发送。
 */
public final class AdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("status", "reload", "approve", "kick");

    private final AdminService adminService;
    private final Supplier<String> permission;
    private final Runnable reloadAction;
    private final Executor asyncExecutor;
    private final Consumer<Runnable> mainThreadExecutor;
    private final Messages messages;

    public AdminCommand(AdminService adminService,
                        Supplier<String> permission,
                        Runnable reloadAction,
                        Executor asyncExecutor,
                        Consumer<Runnable> mainThreadExecutor,
                        Messages messages) {
        this.adminService = adminService;
        this.permission = permission;
        this.reloadAction = reloadAction;
        this.asyncExecutor = asyncExecutor;
        this.mainThreadExecutor = mainThreadExecutor;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        AdminMessages admin = messages.admin();
        if (!sender.hasPermission(permission.get())) {
            sender.sendMessage(admin.noPermission().replace("{permission}", permission.get()));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(admin.usage());
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> runStatus(sender);
            case "reload" -> {
                reloadAction.run();
                sender.sendMessage(admin.reloaded());
            }
            case "approve" -> {
                if (args.length < 2) {
                    sender.sendMessage(admin.approveUsage());
                } else {
                    sendResult(sender, adminService.approve(args[1]));
                }
            }
            case "kick" -> {
                if (args.length < 2) {
                    sender.sendMessage(admin.kickUsage());
                } else {
                    sendResult(sender, adminService.kick(args[1]));
                }
            }
            default -> sender.sendMessage(admin.unknownSubcommand());
        }
        return true;
    }

    private void runStatus(CommandSender sender) {
        asyncExecutor.execute(() -> {
            Status status = adminService.status();
            List<String> lines = formatStatus(status, permission.get());
            mainThreadExecutor.accept(() -> lines.forEach(sender::sendMessage));
        });
    }

    private List<String> formatStatus(Status status, String permission) {
        AdminMessages admin = messages.admin();
        HealthResult health = status.health();
        String healthLine = health != null && health.healthy()
                ? admin.statusHealthHealthy().replace("{uptime}", Long.toString(health.uptimeSeconds()))
                : admin.statusHealthUnhealthy();
        String restartLine = admin.statusRestartQuota()
                .replace("{remaining}", Integer.toString(status.restartRemaining()));
        String nextAllowed = status.nextRestartAllowedAt()
                .map(AdminCommand::formatInstant)
                .map(value -> admin.statusNextAllowed().replace("{time}", value))
                .orElse("");
        String authenticated = status.grace() > 0
                ? admin.statusAuthenticatedGrace()
                        .replace("{count}", Integer.toString(status.authenticated()))
                        .replace("{grace}", Integer.toString(status.grace()))
                : admin.statusAuthenticated().replace("{count}", Integer.toString(status.authenticated()));
        return List.of(
                admin.statusPending().replace("{count}", Integer.toString(status.pending())),
                authenticated,
                healthLine,
                restartLine + nextAllowed,
                admin.statusPermission().replace("{permission}", permission));
    }

    private static String formatInstant(Instant instant) {
        return instant.toString();
    }

    private static void sendResult(CommandSender sender, Result result) {
        sender.sendMessage(result.message());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(value -> value.startsWith(prefix)).toList();
        }
        return List.of();
    }
}