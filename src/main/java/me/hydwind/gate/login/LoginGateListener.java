package me.hydwind.gate.login;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import java.time.Clock;
import me.hydwind.gate.HydwindGatePlugin;
import me.hydwind.gate.auth.LoginSession;
import me.hydwind.gate.auth.LoginSessionStore;
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

    /** 会话存储与时钟在插件生命周期内不变，构造时缓存，避免事件处理里反复穿透插件对象。 */
    private final LoginSessionStore sessionStore;
    private final Clock clock;

    public LoginGateListener(HydwindGatePlugin plugin,
                             LimboLoginService limboLoginService,
                             Logger logger) {
        this.plugin = plugin;
        this.limboLoginService = limboLoginService;
        this.logger = logger;
        this.sessionStore = plugin.sessionStore();
        this.clock = plugin.clock();
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
        // loginTimeout 在这里固定进会话；之后热重载不会改变本会话的截止时刻。
        LoginSession session = sessionStore.join(
                player.getUniqueId(), player.getUsername(),
                RemoteAddress.remoteIp(player.getRemoteAddress()), clock.instant(),
                plugin.pluginConfig().loginTimeout());

        if (session.isAuthenticated()) {
            // 管理员在进 limbo 前已放行：不创建 session handler，直接按正常流程放行登录。
            // LimboAPI 要等事件处理完才建立登录队列；队列存在前 passLoginLimbo 是空操作，
            // 因此必须在 join callback（队列已就绪）里调用。
            event.addOnJoinCallback(() -> limboLoginService.passLogin(player));
            return;
        }
        event.addOnJoinCallback(() -> spawnInLimbo(player));
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        // 唯一的放行判据在会话 Module 里；本 Adapter 只负责把拒绝转成事件结果。
        if (!sessionStore.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        // 同名重复登录被代理拒绝时，这个事件携带的是**目标玩家**的 UUID，而不是
        // 攻击者连接的 UUID。若照常 quit，任何未认证的人都能用别人的名字连接一次
        // 就清掉对方的会话（PENDING 会被直接删除，玩家永久卡在 limbo）。
        // 被拒绝的连接从未建立过会话，这里必须忽略。
        if (!shouldReleaseSession(event.getLoginStatus())) {
            return;
        }
        sessionStore.quit(event.getPlayer().getUniqueId(), clock.instant());
    }

    /**
     * 该断线事件是否应释放会话。
     *
     * <p>{@code CONFLICTING_LOGIN} 表示“同名重复登录被代理拒绝”：事件携带的是
     * 已在线的目标玩家 UUID，而不是被拒绝连接的 UUID。释放它会让任何未认证的人
     * 用别人的名字连接一次就清掉对方会话。这个判据是纯函数，便于回归测试。</p>
     */
    static boolean shouldReleaseSession(DisconnectEvent.LoginStatus status) {
        return status != DisconnectEvent.LoginStatus.CONFLICTING_LOGIN;
    }

    private void spawnInLimbo(Player player) {
        try {
            limboLoginService.spawnInLimbo(player);
        } catch (RuntimeException exception) {
            logger.error("Failed to spawn player {} into limbo; denying access.", player.getUsername(), exception);
            sessionStore.quit(player.getUniqueId(), clock.instant());
            player.disconnect(Component.text(plugin.messages().serviceMisconfigured()));
        }
    }
}