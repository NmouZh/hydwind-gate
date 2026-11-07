package me.hydwind.gate.login;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import me.hydwind.gate.HydwindGatePlugin;
import me.hydwind.gate.auth.KaomojiCode;
import me.hydwind.gate.auth.LoginSession;
import me.hydwind.gate.auth.LoginSessionStore;
import me.hydwind.gate.command.AdminCommandRequest;
import me.hydwind.gate.config.PluginConfig;
import me.hydwind.gate.ui.Messages;
import me.hydwind.gate.web.Urls;
import me.hydwind.gate.web.VerifyResult;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;

/**
 * 单个玩家在 limbo 中的登录过程。
 *
 * <p>LimboAPI 吞掉 limbo 内的聊天与命令，统一交给 {@link #onChat(String)}，
 * 因此登录码在这里解析，Velocity 的 CommandManager 不参与。登录阶段
 * （LOGIN 状态）不能发包，所有提示都在 {@link #onSpawn} 之后发送。</p>
 */
public final class LoginSessionHandler implements LimboSessionHandler {

    private final HydwindGatePlugin plugin;
    private final ProxyServer server;
    private final LoginSessionStore sessionStore;
    private final Clock clock;
    private final Logger logger;
    private final Player proxyPlayer;
    private final UUID uuid;
    private final String gameId;
    private final String ip;
    private final LimboLoginService limboService;

    private final AtomicBoolean authenticated = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile LimboPlayer limboPlayer;
    private volatile ScheduledFuture<?> promptTask;
    private volatile ScheduledFuture<?> timeoutTask;

    public LoginSessionHandler(HydwindGatePlugin plugin,
                               Player proxyPlayer,
                               LimboLoginService limboService) {
        this.plugin = plugin;
        this.server = plugin.proxyServer();
        this.sessionStore = plugin.sessionStore();
        this.clock = plugin.clock();
        this.logger = plugin.logger();
        this.proxyPlayer = proxyPlayer;
        this.uuid = proxyPlayer.getUniqueId();
        this.gameId = proxyPlayer.getUsername();
        this.ip = remoteIp(proxyPlayer);
        this.limboService = limboService;
    }

    public UUID uuid() {
        return uuid;
    }

    public String gameId() {
        return gameId;
    }

    @Override
    public void onSpawn(Limbo server, LimboPlayer player) {
        this.limboPlayer = player;
        limboService.register(this);
        player.disableFalling();
        PluginConfig config = plugin.pluginConfig();

        sendPrompt();

        long interval = config.promptInterval().toMillis();
        promptTask = player.getScheduledExecutor().scheduleWithFixedDelay(
                this::sendPrompt, interval, interval, TimeUnit.MILLISECONDS);
        timeoutTask = player.getScheduledExecutor().schedule(
                this::timeOut, config.loginTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void onChat(String message) {
        if (closed.get()) {
            return;
        }

        AdminCommandRequest adminRequest = AdminCommandRequest.parseLine(message);
        if (adminRequest.type() != AdminCommandRequest.Type.NONE) {
            plugin.adminCommandExecutor().execute(proxyPlayer, adminRequest);
            return;
        }

        if (authenticated.get()) {
            send(plugin.messages().alreadyAuthenticated());
            return;
        }

        LoginCommand command = LoginCommand.parse(message);
        switch (command.status()) {
            case NOT_LOGIN -> {
                // 其他聊天/命令由 LimboAPI 吞掉，忽略。
            }
            case EMPTY_CODE -> send(plugin.messages().emptyCode());
            case TOO_LONG -> send(plugin.messages().codeTooLong());
            case LOGIN -> submit(command.code());
        }
    }

    @Override
    public void onDisconnect() {
        closed.set(true);
        limboService.unregister(uuid);
        cancelTasks();
    }

    /** 管理员手动放行：停止提示与超时，提示玩家后转到主服。 */
    public void approveByAdmin(String message) {
        if (closed.get() || !authenticated.compareAndSet(false, true)) {
            return;
        }
        cancelTasks();
        send(message);
        transferToMainServer();
    }

    /** 管理员踢出：停止所有任务并断开连接。 */
    public void kickByAdmin(String message) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelTasks();
        proxyPlayer.disconnect(Component.text(message));
    }

    private void submit(String rawCode) {
        if (KaomojiCode.isBlankOrTooLong(rawCode)) {
            send(plugin.messages().codeTooLong());
            return;
        }

        LoginSession session = sessionStore.find(uuid).orElseGet(
                () -> sessionStore.join(uuid, gameId, ip, clock.instant()));
        if (session.isAuthenticated()) {
            send(plugin.messages().alreadyAuthenticated());
            return;
        }
        if (!sessionStore.beginVerification(uuid, clock.instant())) {
            if (session.status() == LoginSession.Status.VERIFYING) {
                send(plugin.messages().verifying());
            }
            return;
        }

        String code = KaomojiCode.normalize(rawCode);
        send(plugin.messages().verifying());
        server.getScheduler().buildTask(plugin, () -> verify(code)).schedule();
    }

    private void verify(String code) {
        if (closed.get()) {
            return;
        }

        VerifyResult result;
        try {
            result = plugin.websiteClient().verify(code, gameId, uuid);
        } catch (RuntimeException exception) {
            logger.warn("Website verification threw for player {}: {}", gameId, exception.toString());
            result = VerifyResult.unreachable();
        }

        LoginFeedback feedback = LoginFeedback.from(result, plugin.messages(), gameId);
        if (feedback.authenticated()) {
            completeAuthentication(feedback.message());
        } else {
            failAuthentication(result, feedback);
        }
    }

    private void completeAuthentication(String successMessage) {
        if (!sessionStore.authenticationSucceeded(uuid, clock.instant())) {
            // 超时或断线已让会话失效：迟到的成功结果不得放行。
            return;
        }
        authenticated.set(true);
        cancelTasks();
        send(successMessage);
        transferToMainServer();
    }

    private void failAuthentication(VerifyResult result, LoginFeedback feedback) {
        sessionStore.authenticationFailed(uuid, clock.instant());
        if (result.status() == VerifyResult.Status.UNAUTHORIZED) {
            logger.warn("Website rejected the plugin token (401); check website.token / {}.",
                    PluginConfig.TOKEN_ENV);
        }
        send(feedback.message());
        if (feedback.triggerRestart() && !closed.get()) {
            // 健康检查与写文件可能耗时，放到调度线程，避免阻塞 Limbo 线程。
            server.getScheduler().buildTask(plugin, plugin.restartTrigger()::onUnreachable).schedule();
        }
    }

    private void transferToMainServer() {
        String mainServer = plugin.pluginConfig().mainServer();
        Optional<RegisteredServer> target = server.getServer(mainServer);
        if (target.isEmpty()) {
            logger.error("Configured main server '{}' was not found in velocity.toml; "
                    + "authenticated player {} cannot be transferred.", mainServer, gameId);
            proxyPlayer.disconnect(Component.text(plugin.messages().serviceMisconfigured()));
            return;
        }
        LimboPlayer player = limboPlayer;
        if (player == null) {
            return;
        }
        player.disconnect(target.get());
    }

    private void timeOut() {
        if (closed.get() || authenticated.get()) {
            return;
        }
        PluginConfig config = plugin.pluginConfig();
        if (sessionStore.timeOut(uuid, clock.instant(), config.loginTimeout())) {
            closed.set(true);
            cancelTasks();
            String message = plugin.messages().sessionTimeout();
            proxyPlayer.disconnect(Component.text(message));
        }
    }

    private void sendPrompt() {
        if (closed.get() || authenticated.get()) {
            return;
        }
        Messages messages = plugin.messages();
        String url = Urls.stripTrailingSlashes(plugin.pluginConfig().websiteBaseUrl()) + "/gate";
        PromptText prompt = PromptText.fromTemplate(messages.gatePrompt(), url);

        Component link = Component.text(prompt.url())
                .clickEvent(ClickEvent.openUrl(prompt.url()))
                .color(NamedTextColor.AQUA)
                .decorate(TextDecoration.UNDERLINED);
        Component instruction = Component.text(prompt.beforeUrl())
                .append(link)
                .append(Component.text(prompt.afterUrl()));

        if (!closed.get()) {
            proxyPlayer.sendMessage(Component.text(messages.gateTitle()).color(NamedTextColor.GOLD));
            proxyPlayer.sendMessage(instruction);
        }
    }

    private void send(String message) {
        if (!closed.get()) {
            proxyPlayer.sendMessage(Component.text(message));
        }
    }

    private void cancelTasks() {
        ScheduledFuture<?> prompt = promptTask;
        if (prompt != null) {
            prompt.cancel(false);
        }
        ScheduledFuture<?> timeout = timeoutTask;
        if (timeout != null) {
            timeout.cancel(false);
        }
    }

    private static String remoteIp(Player player) {
        var address = player.getRemoteAddress();
        if (address == null) {
            return "unknown";
        }
        return address.getAddress() == null
                ? address.getHostString()
                : address.getAddress().getHostAddress();
    }
}