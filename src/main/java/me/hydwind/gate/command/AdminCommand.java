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

    public AdminCommand(AdminService adminService,
                        Supplier<String> permission,
                        Runnable reloadAction,
                        Executor asyncExecutor,
                        Consumer<Runnable> mainThreadExecutor) {
        this.adminService = adminService;
        this.permission = permission;
        this.reloadAction = reloadAction;
        this.asyncExecutor = asyncExecutor;
        this.mainThreadExecutor = mainThreadExecutor;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(permission.get())) {
            sender.sendMessage("你没有权限使用 /hydwind（需要 " + permission.get() + "）。");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("用法: /hydwind <status|reload|approve <玩家>|kick <玩家>>");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> runStatus(sender);
            case "reload" -> {
                reloadAction.run();
                sender.sendMessage("配置已重载；进行中的登录会话保持不变。");
            }
            case "approve" -> {
                if (args.length < 2) {
                    sender.sendMessage("用法: /hydwind approve <玩家>");
                } else {
                    sendResult(sender, adminService.approve(args[1]));
                }
            }
            case "kick" -> {
                if (args.length < 2) {
                    sender.sendMessage("用法: /hydwind kick <玩家>");
                } else {
                    sendResult(sender, adminService.kick(args[1]));
                }
            }
            default -> sender.sendMessage("未知子命令。用法: /hydwind <status|reload|approve <玩家>|kick <玩家>>");
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

    private static List<String> formatStatus(Status status, String permission) {
        HealthResult health = status.health();
        String healthLine = health != null && health.healthy()
                ? "网站: 健康（uptime " + health.uptimeSeconds() + " 秒）"
                : "网站: 不可达";
        String restartLine = "重启额度: " + status.restartRemaining() + " 次可用";
        String nextAllowed = status.nextRestartAllowedAt()
                .map(AdminCommand::formatInstant)
                .map(value -> "（下次允许 " + value + "）")
                .orElse("");
        return List.of(
                "待认证: " + status.pending(),
                "已认证: " + status.authenticated()
                        + (status.grace() > 0 ? "（其中离线宽限 " + status.grace() + "）" : ""),
                healthLine,
                restartLine + nextAllowed,
                "管理员权限: " + permission);
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