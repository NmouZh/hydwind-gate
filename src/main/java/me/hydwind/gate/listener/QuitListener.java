package me.hydwind.gate.listener;

import java.util.UUID;
import me.hydwind.gate.auth.LoginManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 玩家退出：已认证会话保留断线宽限，未完成会话立即清除；
 * 同时丢弃冻结状态，避免内存中长期残留 UUID。
 */
public final class QuitListener implements Listener {

    private final LoginManager loginManager;
    private final FreezeController freezeController;

    public QuitListener(LoginManager loginManager, FreezeController freezeController) {
        this.loginManager = loginManager;
        this.freezeController = freezeController;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        loginManager.handleQuit(uuid);
        freezeController.discard(uuid);
    }
}