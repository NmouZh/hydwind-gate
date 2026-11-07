package me.hydwind.gate.command;

import com.velocitypowered.api.command.CommandSource;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import me.hydwind.gate.HydwindGatePlugin;
import me.hydwind.gate.admin.AdminActionService;
import me.hydwind.gate.admin.AdminResult;
import me.hydwind.gate.auth.LoginSessionStore;
import me.hydwind.gate.login.LimboResidents;
import me.hydwind.gate.runtime.RuntimeSnapshot;
import me.hydwind.gate.ui.AdminMessages;
import me.hydwind.gate.ui.AdminStatusRenderer;
import me.hydwind.gate.ui.Templates;
import me.hydwind.gate.web.HealthResult;
import me.hydwind.gate.web.RestartPolicy;
import me.hydwind.gate.web.WebsiteClient;
import net.kyori.adventure.text.Component;

/**
 * 执行 {@code /hydwind} 子命令。Velocity CommandManager 与 limbo {@code onChat}
 * 都调用同一个执行器，保证两个入口行为一致。
 *
 * <p>本类只做 Adapter 的工作：权限判断、调度、把 {@link AdminResult} 映射成文案。
 * 放行与踢出的顺序不变量由 {@link AdminActionService} 维护，本类不再直接写会话存储
 * 或 limbo。</p>
 *
 * <p>权限、文案与网站客户端必须来自同一代 {@link RuntimeSnapshot}，因此每次
 * {@link #execute} 只取一次快照并在整条命令内复用，避免 reload 期间混用新旧配置。</p>
 */
public final class AdminCommandExecutor {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final HydwindGatePlugin plugin;
    private final AdminActionService actions;
    private final LoginSessionStore sessions;
    private final LimboResidents residents;
    private final Runnable reloadConfiguration;

    /**
     * @param plugin               插件实例，用作调度器任务所有者，并提供时钟、日志与重启策略
     * @param actions              approve / kick 的编排层
     * @param sessions             会话存储，仅用于 status 统计
     * @param residents            当前 limbo 玩家视图，仅用于 status 统计
     * @param reloadConfiguration  配置重载动作；插件侧保证方法本身是 synchronized
     */
    public AdminCommandExecutor(HydwindGatePlugin plugin,
                                AdminActionService actions,
                                LoginSessionStore sessions,
                                LimboResidents residents,
                                Runnable reloadConfiguration) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.residents = Objects.requireNonNull(residents, "residents");
        this.reloadConfiguration = Objects.requireNonNull(reloadConfiguration, "reloadConfiguration");
    }

    public void execute(CommandSource source, AdminCommandRequest request) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(request, "request");

        // 一次取快照：权限、文案与网站客户端都来自同一代配置。
        RuntimeSnapshot runtime = plugin.runtime().current();
        if (runtime == null) {
            // 命令注册发生在首次发布代际之后；走到这里说明插件装配顺序被破坏。
            // 命令处理器位于用户可见边界，记错误并给默认用法提示，不把异常抛回代理。
            plugin.logger().error("HydwindGate 运行时尚未发布，无法处理 /hydwind 命令。");
            send(source, AdminMessages.defaults().usage());
            return;
        }

        String permission = runtime.config().adminPermission();
        AdminMessages admin = runtime.messages().admin();
        if (!HydwindCommand.hasAdminPermission(source, permission)) {
            send(source, Templates.fill(admin.noPermission(), "permission", permission));
            return;
        }

        // 穷尽 switch 表达式：AdminCommandRequest.Type 删除 NONE 后，
        // 以后新增子命令却忘记处理时会在编译期直接失败。
        Runnable action = switch (request.type()) {
            case STATUS -> () -> status(source, runtime, admin, permission);
            case RELOAD -> () -> reload(source, admin);
            case APPROVE -> () -> approve(source, admin, request.target());
            case KICK -> () -> kick(source, admin, request.target());
            case USAGE -> () -> send(source, admin.usage());
            case UNKNOWN -> () -> send(source, admin.unknownSubcommand());
        };
        action.run();
    }

    private void status(CommandSource source,
                        RuntimeSnapshot runtime,
                        AdminMessages admin,
                        String permission) {
        WebsiteClient website = runtime.websiteClient();
        RestartPolicy policy = plugin.restartPolicy();

        // 健康探针是阻塞 HTTP 调用，放到代理调度线程，避免卡住 limbo/命令线程。
        plugin.proxyServer().getScheduler().buildTask(plugin, () -> {
            HealthResult health;
            try {
                health = website.health();
            } catch (RuntimeException exception) {
                plugin.logger().warn("Health probe failed during /hydwind status: {}",
                        exception.toString());
                health = HealthResult.unhealthy();
            }

            Instant now = plugin.clock().instant();
            List<String> limboPlayerNames = residents.all().stream()
                    .map(LimboResidents.Resident::gameId)
                    .toList();
            AdminStatus status = AdminStatus.from(
                    sessions.snapshot(), limboPlayerNames, health, policy, now);
            // 传输层不可达与契约不兼容目前共用 statusHealthUnhealthy 文案：
            // AdminStatus 不携带 transportFailure，避免为一条提示扩展字段。
            for (String line : AdminStatusRenderer.render(status, admin, permission, TIME_FORMAT)) {
                send(source, line);
            }
        }).schedule();
    }

    private void reload(CommandSource source, AdminMessages admin) {
        plugin.proxyServer().getScheduler().buildTask(plugin, () -> {
            try {
                // 重载本身由插件保证 synchronized；这里只负责调度与错误反馈。
                reloadConfiguration.run();
                send(source, admin.reloaded());
            } catch (RuntimeException exception) {
                plugin.logger().error("Configuration reload failed.", exception);
                send(source, Templates.fill(admin.reloadFailed(), "error",
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

        // 放行的顺序不变量（先写会话、再转服）由 AdminActionService 维护。
        sendResult(source, admin, actions.approve(playerName, admin.approvedPlayer()));
    }

    private void kick(CommandSource source, AdminMessages admin, String playerName) {
        if (playerName == null || playerName.isBlank()) {
            send(source, admin.kickUsage());
            return;
        }

        // 踢出的顺序不变量（先清会话、再断开连接）由 AdminActionService 维护。
        sendResult(source, admin, actions.kick(playerName, admin.kickedPlayer()));
    }

    /**
     * 把 {@link AdminResult.Kind} 映射成现有管理员文案，不再自行判断玩家在 limbo
     * 还是已断线。两个子命令共用一个映射，保证同类结果在两个入口显示一致。
     */
    private static void sendResult(CommandSource source, AdminMessages admin, AdminResult result) {
        // 穷尽表达式：AdminResult.Kind 新增分类时这里会编译失败，避免漏配文案。
        String template = switch (result.kind()) {
            case APPROVED_IN_LIMBO, APPROVED_OFFLINE -> admin.approved();
            case KICKED_IN_LIMBO, KICKED_BACKEND -> admin.kicked();
            case ALREADY_AUTHENTICATED -> admin.alreadyAuthenticated();
            case NOT_FOUND -> admin.sessionNotFound();
        };
        send(source, Templates.fill(template, "player", result.playerName()));
    }

    private static void send(CommandSource source, String message) {
        source.sendMessage(Component.text(message));
    }
}
