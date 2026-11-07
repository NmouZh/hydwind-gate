package me.hydwind.gate.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import me.hydwind.gate.HydwindGatePlugin;
import me.hydwind.gate.auth.LoginSession;
import me.hydwind.gate.login.LimboLoginService;
import me.hydwind.gate.ui.AdminMessages;
import me.hydwind.gate.web.HealthResult;
import net.kyori.adventure.text.Component;

/**
 * 执行 {@code /hydwind} 子命令。Velocity CommandManager 与 limbo {@code onChat}
 * 都调用同一个执行器，保证两个入口行为一致。
 */
public final class AdminCommandExecutor {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final HydwindGatePlugin plugin;
    private final LimboLoginService limboService;

    public AdminCommandExecutor(HydwindGatePlugin plugin, LimboLoginService limboService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.limboService = Objects.requireNonNull(limboService, "limboService");
    }

    public void execute(CommandSource source, AdminCommandRequest request) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(request, "request");

        String permission = plugin.pluginConfig().adminPermission();
        AdminMessages admin = plugin.messages().admin();
        if (!source.hasPermission(permission)) {
            send(source, fill(admin.noPermission(), "permission", permission));
            return;
        }

        switch (request.type()) {
            case STATUS -> status(source, admin, permission);
            case RELOAD -> reload(source, admin);
            case APPROVE -> approve(source, admin, request.target());
            case KICK -> kick(source, admin, request.target());
            case USAGE -> send(source, admin.usage());
            case UNKNOWN -> send(source, admin.unknownSubcommand());
            case NONE -> {
                // limbo 聊天中的非管理命令，由调用方过滤；这里不做任何事。
            }
        }
    }

    private void status(CommandSource source, AdminMessages admin, String permission) {
        // 健康探针是阻塞 HTTP 调用，放到代理调度线程，避免卡住 limbo/命令线程。
        plugin.proxyServer().getScheduler().buildTask(plugin, () -> {
            HealthResult health;
            try {
                health = plugin.websiteClient().health();
            } catch (RuntimeException exception) {
                plugin.logger().warn("Health probe failed during /hydwind status: {}",
                        exception.toString());
                health = HealthResult.unhealthy();
            }

            Instant now = plugin.clock().instant();
            AdminStatus status = AdminStatus.from(
                    plugin.sessionStore().snapshot(),
                    limboService.limboPlayerCount(),
                    health,
                    plugin.restartPolicy(),
                    now);
            for (String line : status.lines(admin, permission, TIME_FORMAT)) {
                send(source, line);
            }
        }).schedule();
    }

    private void reload(CommandSource source, AdminMessages admin) {
        plugin.proxyServer().getScheduler().buildTask(plugin, () -> {
            try {
                plugin.reloadConfiguration();
                send(source, admin.reloaded());
            } catch (RuntimeException exception) {
                plugin.logger().error("Configuration reload failed.", exception);
                send(source, fill(admin.reloadFailed(), "error",
                        exception.getMessage() == null ? exception.getClass().getSimpleName()
                                : exception.getMessage()));
            }
        }).schedule();
    }

    private void approve(CommandSource source, AdminMessages admin, String playerName) {
        if (playerName == null || playerName.isBlank()) {
            send(source, admin.approveUsage());
            return;
        }

        Optional<LoginSession> session = plugin.sessionStore().findByGameId(playerName);
        if (session.isEmpty()) {
            send(source, fill(admin.sessionNotFound(), "player", playerName));
            return;
        }
        if (session.get().isAuthenticated()) {
            send(source, fill(admin.alreadyAuthenticated(), "player", session.get().gameId()));
            return;
        }

        UUID uuid = session.get().uuid();
        // 先写会话状态再转服：ServerPreConnectEvent 会拒绝未认证会话。
        boolean inLimbo = limboService.approve(uuid, admin.approvedPlayer());
        if (!inLimbo) {
            // 玩家可能已断线但会话仍在；管理员放行仍然生效。
            plugin.sessionStore().forceAuthenticate(uuid, plugin.clock().instant());
        }
        send(source, fill(admin.approved(), "player", session.get().gameId()));
    }

    private void kick(CommandSource source, AdminMessages admin, String playerName) {
        if (playerName == null || playerName.isBlank()) {
            send(source, admin.kickUsage());
            return;
        }

        Optional<LoginSession> session = plugin.sessionStore().findByGameId(playerName);
        Optional<Player> online = plugin.proxyServer().getPlayer(playerName);
        if (session.isEmpty() && online.isEmpty()) {
            send(source, fill(admin.sessionNotFound(), "player", playerName));
            return;
        }

        UUID uuid = session.map(LoginSession::uuid)
                .or(() -> online.map(Player::getUniqueId))
                .orElseThrow();
        String displayName = session.map(LoginSession::gameId)
                .or(() -> online.map(Player::getUsername))
                .orElse(playerName);

        boolean handledInLimbo = limboService.kick(uuid, admin.kickedPlayer());
        if (!handledInLimbo) {
            plugin.sessionStore().remove(uuid);
            online.or(() -> plugin.proxyServer().getPlayer(uuid))
                    .ifPresent(player -> player.disconnect(Component.text(admin.kickedPlayer())));
        }
        send(source, fill(admin.kicked(), "player", displayName));
    }

    private static void send(CommandSource source, String message) {
        source.sendMessage(Component.text(message));
    }

    private static String fill(String template, String placeholder, String value) {
        return template.replace("{" + placeholder + "}", value == null ? "" : value);
    }
}