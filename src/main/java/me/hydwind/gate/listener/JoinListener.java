package me.hydwind.gate.listener;

import java.net.InetSocketAddress;
import me.hydwind.gate.auth.LoginManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 玩家进服时建立登录会话。未认证玩家会被冻结并弹出登录对话框；
 * 断线宽限内重连的已认证玩家直接放行。
 */
public final class JoinListener implements Listener {

    private final LoginManager loginManager;

    public JoinListener(LoginManager loginManager) {
        this.loginManager = loginManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        loginManager.handleJoin(player.getUniqueId(), player.getName(), remoteIp(player));
    }

    private static String remoteIp(Player player) {
        InetSocketAddress address = player.getAddress();
        if (address != null && address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        return "unknown";
    }
}