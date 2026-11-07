package me.hydwind.gate.login;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import java.util.Optional;
import java.util.UUID;
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
            logger.debug("强制离线模式：玩家={}，IP={}", event.getUsername(),
                    RemoteAddress.remoteIp(event.getConnection().getRemoteAddress()));
        }
    }

    @Subscribe
    public void onLoginLimboRegister(LoginLimboRegisterEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String ip = RemoteAddress.remoteIp(player.getRemoteAddress());
        Optional<LoginSession> previous = plugin.sessionStore().find(uuid);
        boolean hadDisconnectedSession = previous
                .map(session -> session.isAuthenticated() && session.disconnectedAt() != null)
                .orElse(false);

        LoginSession session = plugin.sessionStore().join(
                uuid, player.getUsername(), ip, plugin.clock().instant());

        if (session.isAuthenticated()) {
            // LimboAPI 要等事件处理完才建立登录队列；队列存在前 passLoginLimbo 是空操作，
            // 因此必须在 join callback（队列已就绪）里调用。
            logger.info("登录门禁决策：玩家={}，uuid={}，IP={}，断线宽限={}，结果=直接放行到主服",
                    player.getUsername(), uuid, ip, hadDisconnectedSession ? "命中" : "未命中（会话仍在线）");
            event.addOnJoinCallback(() -> limboLoginService.passLogin(player));
            return;
        }
        logger.info("登录门禁决策：玩家={}，uuid={}，IP={}，断线宽限=未命中，结果=进入 limbo",
                player.getUsername(), uuid, ip);
        event.addOnJoinCallback(() -> spawnInLimbo(player));
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        boolean authenticated = plugin.sessionStore()
                .find(player.getUniqueId())
                .map(LoginSession::isAuthenticated)
                .orElse(false);
        if (!authenticated) {
            logger.debug("拒绝未认证玩家连接后端：玩家={}，uuid={}，IP={}，目标={}",
                    player.getUsername(), player.getUniqueId(),
                    RemoteAddress.remoteIp(player.getRemoteAddress()),
                    event.getOriginalServer().getServerInfo().getName());
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        logger.debug("玩家断开连接：玩家={}，uuid={}，IP={}",
                event.getPlayer().getUsername(), event.getPlayer().getUniqueId(),
                RemoteAddress.remoteIp(event.getPlayer().getRemoteAddress()));
        plugin.sessionStore().quit(event.getPlayer().getUniqueId(), plugin.clock().instant());
    }

    private void spawnInLimbo(Player player) {
        try {
            limboLoginService.spawnInLimbo(player);
        } catch (RuntimeException exception) {
            logger.error("无法把玩家 {}（uuid={}，IP={}）放入 limbo，已拒绝其访问。",
                    player.getUsername(), player.getUniqueId(),
                    RemoteAddress.remoteIp(player.getRemoteAddress()), exception);
            plugin.sessionStore().quit(player.getUniqueId(), plugin.clock().instant());
            player.disconnect(Component.text(plugin.messages().serviceMisconfigured()));
        }
    }
}