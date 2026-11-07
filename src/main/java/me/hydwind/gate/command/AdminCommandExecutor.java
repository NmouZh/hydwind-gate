package me.hydwind.gate.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import me.hydwind.gate.HydwindGatePlugin;
import me.hydwind.gate.auth.LoginSession;
import me.hydwind.gate.login.LimboLoginService;
import me.hydwind.gate.ui.AdminMessages;
import me.hydwind.gate.ui.Templates;
import me.hydwind.gate.util.Throwables;
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
        String executor = describeSource(source);
        String target = request.target() == null || request.target().isBlank()
                ? "-" : request.target();
        plugin.logger().debug("管理命令：执行者={}，命令={}，目标={}", executor, request.type(), target);

        if (!source.hasPermission(permission)) {
            plugin.logger().warn("管理命令被拒绝：执行者={}，命令={}，缺少权限={}",
                    executor, request.type(), permission);
            send(source, Templates.fill(admin.noPermission(), "permission", permission));
            return;
        }

        switch (request.type()) {
            case STATUS -> status(source, admin, permission, executor);
            case RELOAD -> reload(source, admin, executor);
            case APPROVE -> approve(source, admin, request.target(), executor);
            case KICK -> kick(source, admin, request.target(), executor);
            case USAGE -> {
                plugin.logger().debug("管理命令用法提示：执行者={}", executor);
                send(source, admin.usage());
            }
            case UNKNOWN -> {
                plugin.logger().debug("未知管理子命令：执行者={}", executor);
                send(source, admin.unknownSubcommand());
            }
            case NONE -> {
                // limbo 聊天中的非管理命令，由调用方过滤；这里不做任何事。
            }
        }
    }

    private void status(CommandSource source, AdminMessages admin, String permission, String executor) {
        // 健康探针是阻塞 HTTP 调用，放到代理调度线程，避免卡住 limbo/命令线程。
        plugin.proxyServer().getScheduler().buildTask(plugin, () -> {
            HealthResult health;
            try {
                health = plugin.websiteClient().health();
            } catch (RuntimeException exception) {
                plugin.logger().warn("/hydwind status 执行期间健康检查失败：执行者={}，异常={}",
                        executor, Throwables.describe(exception));
                health = HealthResult.unhealthy();
            }

            Instant now = plugin.clock().instant();
            List<String> limboPlayerNames = limboService.limboPlayers().stream()
                    .map(LimboLoginService.LimboResident::gameId)
                    .toList();
            AdminStatus status = AdminStatus.from(
                    plugin.sessionStore().snapshot(),
                    limboPlayerNames,
                    health,
                    plugin.restartPolicy(),
                    now);
            plugin.logger().info("状态查询完成：执行者={}，网站健康={}，limbo 人数={}，会话数={}",
                    executor, health.healthy() ? "通过" : "失败", limboPlayerNames.size(),
                    plugin.sessionStore().snapshot().size());
            for (String line : status.lines(admin, permission, TIME_FORMAT)) {
                send(source, line);
            }
        }).schedule();
    }

    private void reload(CommandSource source, AdminMessages admin, String executor) {
        plugin.proxyServer().getScheduler().buildTask(plugin, () -> {
            try {
                plugin.reloadConfiguration();
                plugin.logger().info("配置重载完成：执行者={}", executor);
                send(source, admin.reloaded());
            } catch (RuntimeException exception) {
                plugin.logger().error("配置重载失败：执行者={}，异常={}",
                        executor, Throwables.describe(exception), exception);
                send(source, Templates.fill(admin.reloadFailed(), "error",
                        exception.getMessage() == null ? exception.getClass().getSimpleName()
                                : exception.getMessage()));
            }
        }).schedule();
    }

    private void approve(CommandSource source, AdminMessages admin, String playerName, String executor) {
        if (playerName == null || playerName.isBlank()) {
            plugin.logger().debug("管理员放行缺少目标玩家：执行者={}", executor);
            send(source, admin.approveUsage());
            return;
        }

        Optional<LoginSession> session = plugin.sessionStore().findByGameId(playerName);
        if (session.isEmpty()) {
            plugin.logger().info("管理员放行未找到会话：执行者={}，目标={}", executor, playerName);
            send(source, Templates.fill(admin.sessionNotFound(), "player", playerName));
            return;
        }
        if (session.get().isAuthenticated()) {
            plugin.logger().info("管理员放行跳过：执行者={}，目标={}，原因=已认证",
                    executor, session.get().gameId());
            send(source, Templates.fill(admin.alreadyAuthenticated(), "player", session.get().gameId()));
            return;
        }

        UUID uuid = session.get().uuid();
        // 先写会话状态再转服：ServerPreConnectEvent 会拒绝未认证会话。
        boolean inLimbo = limboService.approve(uuid, admin.approvedPlayer());
        if (!inLimbo) {
            // 玩家可能已断线但会话仍在；管理员放行仍然生效。
            plugin.sessionStore().forceAuthenticate(uuid, plugin.clock().instant());
        }
        plugin.logger().info("管理员放行成功：执行者={}，目标={}（uuid={}），在 limbo={}",
                executor, session.get().gameId(), uuid, inLimbo);
        send(source, Templates.fill(admin.approved(), "player", session.get().gameId()));
    }

    private void kick(CommandSource source, AdminMessages admin, String playerName, String executor) {
        if (playerName == null || playerName.isBlank()) {
            plugin.logger().debug("管理员踢出缺少目标玩家：执行者={}", executor);
            send(source, admin.kickUsage());
            return;
        }

        Optional<LoginSession> session = plugin.sessionStore().findByGameId(playerName);
        Optional<Player> online = plugin.proxyServer().getPlayer(playerName);
        if (session.isEmpty() && online.isEmpty()) {
            plugin.logger().info("管理员踢出未找到玩家：执行者={}，目标={}", executor, playerName);
            send(source, Templates.fill(admin.sessionNotFound(), "player", playerName));
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
        plugin.logger().info("管理员踢出成功：执行者={}，目标={}（uuid={}），在 limbo={}",
                executor, displayName, uuid, handledInLimbo);
        send(source, Templates.fill(admin.kicked(), "player", displayName));
    }

    private static String describeSource(CommandSource source) {
        if (source instanceof Player player) {
            return player.getUsername() + "（uuid=" + player.getUniqueId() + "）";
        }
        return "控制台（" + source.getClass().getSimpleName() + "）";
    }

    private static void send(CommandSource source, String message) {
        source.sendMessage(Component.text(message));
    }
}