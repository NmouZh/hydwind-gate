package me.hydwind.gate.command;

import me.hydwind.gate.auth.LoginManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /login}：无参数时重新弹出对话框；带参数时直接提交登录码。
 *
 * <p>参数用空格连接，以兼容包含空格的颜文字登录码。
 */
public final class LoginCommand implements CommandExecutor {

    private final LoginManager loginManager;

    public LoginCommand(LoginManager loginManager) {
        this.loginManager = loginManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("只有玩家可以使用 /login。");
            return true;
        }
        if (args.length == 0) {
            loginManager.reopenDialog(player.getUniqueId());
            return true;
        }
        String code = String.join(" ", args).trim();
        if (code.isEmpty()) {
            loginManager.reopenDialog(player.getUniqueId());
            return true;
        }
        loginManager.submitCodeAsync(player.getUniqueId(), code);
        return true;
    }
}