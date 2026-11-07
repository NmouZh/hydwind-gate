package me.hydwind.gate.login;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import me.hydwind.gate.HydwindGatePlugin;
import me.hydwind.gate.auth.LoginSession;
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

/**
 * 门禁的四个 Velocity 监听点：
 *
 * <ul>
 *   <li>{@code PreLoginEvent}：强制离线模式；</li>
 *   <li>{@code LoginLimboRegisterEvent}：宽限内的已认证玩家直接放行，其余进 limbo；</li>
 *   <li>{@code ServerPreConnectEvent}：没有已认证会话的玩家禁止连接任何后端；</li>
 *   <li>{@code DisconnectEvent}：记录断线时刻，保留宽限。</li>
 * </ul>
 */
public final class LoginGateListener {

    private final HydwindGatePlugin plugin;
    private final LimboLoginService limboLoginService;
    private final Logger logger;

    public LoginGateListener(HydwindGatePlugin plugin,
                             LimboLoginService limboLoginService,
                             Logger logger) {
        this.plugin = plugin;
        this.limboLoginService = limboLoginService;
        this.logger = logger;
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        if (event.getResult().isAllowed()) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
        }
    }

    @Subscribe
    public void onLoginLimboRegister(LoginLimboRegisterEvent event) {
        Player player = event.getPlayer();
        LoginSession session = plugin.sessionStore().join(
                player.getUniqueId(), player.getUsername(),
                RemoteAddress.remoteIp(player.getRemoteAddress()), plugin.clock().instant());

        if (session.isAuthenticated()) {
            // LimboAPI 要等事件处理完才建立登录队列；队列存在前 passLoginLimbo 是空操作，
            // 因此必须在 join callback（队列已就绪）里调用。
            event.addOnJoinCallback(() -> limboLoginService.passLogin(player));
            return;
        }
        event.addOnJoinCallback(() -> spawnInLimbo(player));
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        boolean authenticated = plugin.sessionStore()
                .find(event.getPlayer().getUniqueId())
                .map(LoginSession::isAuthenticated)
                .orElse(false);
        if (!authenticated) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        plugin.sessionStore().quit(event.getPlayer().getUniqueId(), plugin.clock().instant());
    }

    private void spawnInLimbo(Player player) {
        try {
            limboLoginService.spawnInLimbo(player);
        } catch (RuntimeException exception) {
            logger.error("Failed to spawn player {} into limbo; denying access.", player.getUsername(), exception);
            plugin.sessionStore().quit(player.getUniqueId(), plugin.clock().instant());
            player.disconnect(Component.text(plugin.messages().serviceMisconfigured()));
        }
    }
}