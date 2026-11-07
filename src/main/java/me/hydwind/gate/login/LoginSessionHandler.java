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
import me.hydwind.gate.util.Throwables;
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
        this.ip = RemoteAddress.remoteIp(proxyPlayer.getRemoteAddress());
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

        logger.info("玩家进入 limbo：玩家={}，uuid={}，IP={}，gameId={}，limbo 世界={}，主服={}，网站={}",
                gameId, uuid, ip, gameId, config.limboWorld(), config.mainServer(),
                config.websiteBaseUrl());

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
            logger.debug("limbo 收到管理命令（hydwind）：玩家={}，uuid={}，类型={}",
                    gameId, uuid, adminRequest.type());
            plugin.adminCommandExecutor().execute(proxyPlayer, adminRequest);
            return;
        }

        if (authenticated.get()) {
            logger.debug("玩家 {} 已认证，忽略 limbo 聊天", gameId);
            send(plugin.messages().alreadyAuthenticated());
            return;
        }

        LoginCommand command = LoginCommand.parse(message);
        logger.debug("limbo 收到聊天：玩家={}，类型={}，解析结果={}", gameId,
                command.status() == LoginCommand.Status.NOT_LOGIN ? "其他" : "login",
                command.status());
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
        logger.debug("玩家离开 limbo：玩家={}，uuid={}，IP={}", gameId, uuid, ip);
        limboService.unregister(uuid);
        cancelTasks();
    }

    /** 管理员手动放行：停止提示与超时，提示玩家后转到主服。 */
    public void approveByAdmin(String message) {
        if (closed.get() || !authenticated.compareAndSet(false, true)) {
            logger.debug("忽略管理员放行：玩家={} 已关闭或已认证", gameId);
            return;
        }
        logger.info("管理员放行玩家 {}（uuid={}），准备转入主服", gameId, uuid);
        cancelTasks();
        send(message);
        transferToMainServer();
    }

    /** 管理员踢出：停止所有任务并断开连接。 */
    public void kickByAdmin(String message) {
        if (!closed.compareAndSet(false, true)) {
            logger.debug("忽略管理员踢出：玩家={} 已关闭", gameId);
            return;
        }
        logger.info("管理员踢出玩家 {}（uuid={}，IP={}）", gameId, uuid, ip);
        cancelTasks();
        proxyPlayer.disconnect(Component.text(message));
    }

    private void submit(String rawCode) {
        if (KaomojiCode.isBlankOrTooLong(rawCode)) {
            logger.debug("玩家 {} 提交的登录码为空或超长，已拒绝", gameId);
            send(plugin.messages().codeTooLong());
            return;
        }

        LoginSession session = sessionStore.find(uuid).orElseGet(
                () -> sessionStore.join(uuid, gameId, ip, clock.instant()));
        if (session.isAuthenticated()) {
            logger.debug("玩家 {} 已认证，忽略重复登录码", gameId);
            send(plugin.messages().alreadyAuthenticated());
            return;
        }
        if (!sessionStore.beginVerification(uuid, clock.instant())) {
            if (session.status() == LoginSession.Status.VERIFYING) {
                logger.debug("玩家 {} 的登录码校验仍在进行，忽略重复提交", gameId);
                send(plugin.messages().verifying());
            } else {
                logger.debug("玩家 {} 当前状态为 {}，拒绝提交登录码", gameId, session.status());
            }
            return;
        }

        String code = KaomojiCode.normalize(rawCode);
        logger.info("收到登录码：玩家={}，uuid={}，码长度={}，码形态={}",
                gameId, uuid, code.codePointCount(0, code.length()), codeShape(code));
        send(plugin.messages().verifying());
        server.getScheduler().buildTask(plugin, () -> verify(code)).schedule();
    }

    private void verify(String code) {
        if (closed.get()) {
            logger.debug("玩家 {} 的登录会话已关闭，跳过网站校验", gameId);
            return;
        }

        PluginConfig config = plugin.pluginConfig();
        long startedAt = System.nanoTime();
        logger.info("登录校验开始：玩家={}，uuid={}，网站={}，码长度={}，码形态={}",
                gameId, uuid, config.websiteBaseUrl(), code.codePointCount(0, code.length()),
                codeShape(code));

        VerifyResult result;
        try {
            result = plugin.websiteClient().verify(code, gameId, uuid);
        } catch (RuntimeException exception) {
            logger.warn("玩家 {} 的网站校验抛出异常：URL={}，耗时={}ms，异常={}",
                    gameId, config.websiteBaseUrl(), elapsedMillis(startedAt),
                    Throwables.describe(exception));
            result = VerifyResult.unreachable();
        }

        logger.info("登录校验结果：玩家={}，uuid={}，结果={}，耗时={}ms",
                gameId, uuid, result.status(), elapsedMillis(startedAt));

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
            logger.debug("玩家 {} 的认证成功结果迟到，会话已失效，不放行", gameId);
            return;
        }
        authenticated.set(true);
        cancelTasks();
        logger.info("玩家 {} 认证成功，准备转入主服 '{}'", gameId,
                plugin.pluginConfig().mainServer());
        send(successMessage);
        transferToMainServer();
    }

    private void failAuthentication(VerifyResult result, LoginFeedback feedback) {
        sessionStore.authenticationFailed(uuid, clock.instant());
        if (result.status() == VerifyResult.Status.UNAUTHORIZED) {
            logger.warn("网站拒绝了插件令牌（401）；请检查 website.token / {}。玩家={}（uuid={}）",
                    PluginConfig.TOKEN_ENV, gameId, uuid);
        }
        logger.info("玩家 {} 登录校验未通过：结果={}，触发重启检查={}",
                gameId, result.status(), feedback.triggerRestart());
        send(feedback.message());
        if (feedback.triggerRestart() && !closed.get()) {
            logger.info("网站不可达，提交受控重启检查：玩家={}，网站={}",
                    gameId, plugin.pluginConfig().websiteBaseUrl());
            // 健康检查与写文件可能耗时，放到调度线程，避免阻塞 Limbo 线程。
            server.getScheduler().buildTask(plugin, plugin.restartTrigger()::onUnreachable).schedule();
        }
    }

    private void transferToMainServer() {
        String mainServer = plugin.pluginConfig().mainServer();
        Optional<RegisteredServer> target = server.getServer(mainServer);
        if (target.isEmpty()) {
            logger.error("配置的主服 '{}' 在 velocity.toml 中不存在；"
                    + "已认证玩家 {}（uuid={}，IP={}）无法转服。", mainServer, gameId, uuid, ip);
            proxyPlayer.disconnect(Component.text(plugin.messages().serviceMisconfigured()));
            return;
        }
        LimboPlayer player = limboPlayer;
        if (player == null) {
            logger.warn("玩家 {} 的 limbo 句柄为空，无法转入主服 '{}'", gameId, mainServer);
            return;
        }
        logger.info("玩家 {} 转入主服 '{}'（uuid={}）", gameId, mainServer, uuid);
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
            logger.info("玩家 {} 登录超时（{} 秒），已断开并清理 limbo 会话",
                    gameId, config.loginTimeout().toSeconds());
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

        logger.debug("向玩家 {} 发送登录提示：URL={}", gameId, prompt.url());

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
            // 明确告诉玩家当前流程位置，避免只看到网址不知道还要做什么。
            proxyPlayer.sendMessage(Component.text(messages.statusUnverified()));
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

    /** 只判断形态，不记录登录码本身。 */
    private static String codeShape(String code) {
        boolean hasNonAlphanumeric = code.codePoints()
                .anyMatch(codePoint -> !Character.isLetterOrDigit(codePoint));
        return hasNonAlphanumeric ? "颜文字" : "备用码";
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}