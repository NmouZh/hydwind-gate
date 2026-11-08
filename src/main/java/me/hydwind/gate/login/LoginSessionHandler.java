package me.hydwind.gate.login;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import me.hydwind.gate.HydwindGatePlugin;
import me.hydwind.gate.auth.LoginSession;
import me.hydwind.gate.auth.LoginSessionStore;
import me.hydwind.gate.command.AdminCommandExecutor;
import me.hydwind.gate.command.AdminCommandRequest;
import me.hydwind.gate.command.CommandGrammar;
import me.hydwind.gate.config.PluginConfig;
import me.hydwind.gate.flow.LoginFlow;
import me.hydwind.gate.flow.LoginIdentity;
import me.hydwind.gate.flow.SubmitResult;
import me.hydwind.gate.flow.VerifyOutcome;
import me.hydwind.gate.runtime.RuntimeSnapshot;
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
 * 因此登录码与 {@code /hydwind} 命令在这里分流。所有登录决策（提交、校验、超时）
 * 都委托给 {@link LoginFlow}；本类只保留平台动作：发消息、转服、断开与调度。</p>
 *
 * <p>每次操作（提交、校验、超时、提示、转服）开始时取一次当前代际快照
 * （{@link RuntimeSnapshot}），玩家可见文案与网站客户端都在本次操作内复用同一代际，
 * 因此 {@code /hydwind reload} 之后，已在 limbo 中的玩家也会立即看到新文案。
 * 只有本会话的提示间隔与超时排期在构造时按会话冻结：这两个定时器无法安全重排。</p>
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
    private final LoginIdentity identity;
    private final AdminCommandExecutor adminCommandExecutor;
    /** 仅用于本会话的提示间隔与超时排期；这两项按设计不随 reload 重排。 */
    private final PluginConfig config;
    private final LimboLoginService limboService;

    private final AtomicBoolean authenticated = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /**
     * 转服动作只发起一次。和 {@link #authenticated} 分开：管理员可能在 spawn
     * 之前放行，此时认证已写入但 limboPlayer 还没就绪，不能把“已发起转服”
     * 也标记掉，否则 onSpawn 会永远不再转服。
     */
    private final AtomicBoolean transferStarted = new AtomicBoolean(false);
    /** 认证成功后等待玩家离开 limbo 的上限；超时说明转服静默失败。 */
    private static final long TRANSFER_WATCHDOG_SECONDS = 30L;

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
        this.identity = new LoginIdentity(uuid, gameId, RemoteAddress.remoteIp(proxyPlayer.getRemoteAddress()));
        // 配置只冻结提示间隔与超时排期；文案、客户端与触发器在每次操作时取当前代际。
        this.config = plugin.pluginConfig();
        this.adminCommandExecutor = plugin.adminCommandExecutor();
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
        if (closed.get()) {
            return;
        }

        // 会话可能已经被旧连接的迟到断线事件删掉（或过期 sweep 清掉）。
        // 没有会话就不能继续提示/排队，否则玩家会永久空转，超时也永远不会生效。
        if (sessionStore.find(uuid).isEmpty()) {
            closed.set(true);
            limboService.unregister(uuid, this);
            cancelTasks();
            proxyPlayer.disconnect(Component.text(
                    me.hydwind.gate.ui.Messages.defaults().serviceMisconfigured()));
            return;
        }

        // 管理员可能在 spawn 之前就放行了（approve-before-spawn）：会话已是
        // AUTHENTICATED 时不再提示、不再排超时任务，直接转服，避免卡在 limbo。
        if (sessionAuthenticated()) {
            authenticated.set(true);
            transferToMainServer();
            return;
        }

        limboService.register(this);

        // register 之后再加两道检查：
        // 1. 管理员可能在 register 前后放行；
        // 2. 连接可能在 register 之前已经断开（closed），不能留下僵尸登记。
        if (closed.get()) {
            limboService.unregister(uuid, this);
            return;
        }
        if (authenticated.get() || sessionAuthenticated()) {
            authenticated.set(true);
            transferToMainServer();
            return;
        }

        player.disableFalling();

        sendPrompt();

        long interval = config.promptInterval().toMillis();
        promptTask = player.getScheduledExecutor().scheduleWithFixedDelay(
                this::sendPrompt, interval, interval, TimeUnit.MILLISECONDS);

        // 一次性超时任务按会话自己的 deadline 排；之后 reload 改配置不影响本会话。
        Instant now = clock.instant();
        long timeoutMillis = sessionStore.find(uuid)
                .map(session -> Duration.between(now, session.loginDeadline()))
                .orElse(config.loginTimeout())
                .toMillis();
        timeoutTask = player.getScheduledExecutor().schedule(
                this::timeOut, Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);

        // 取消可能发生在任务赋值之前（approve/kick/断开），那时 cancelTasks 读到 null；
        // 赋值后再复核一次，避免任务在已经结束的会话上永久空转。
        if (closed.get() || authenticated.get()) {
            cancelTasks();
        }
    }

    @Override
    public void onChat(String message) {
        if (closed.get()) {
            return;
        }
        try {
            handleChat(message);
        } catch (RuntimeException exception) {
            // LimboAPI 的回调抛异常会打断该玩家的聊天处理且玩家得不到提示；
            // 统一记录并按 fail-closed 断开，避免留下半处理状态。
            logger.error("Failed to handle chat for player {}; denying access.",
                    gameId, exception);
            closed.set(true);
            cancelTasks();
            proxyPlayer.disconnect(Component.text(
                    me.hydwind.gate.ui.Messages.defaults().serviceMisconfigured()));
        }
    }

    private void handleChat(String message) {
        Optional<AdminCommandRequest> adminRequest = CommandGrammar.parseLine(message);
        if (adminRequest.isPresent()) {
            adminCommandExecutor.execute(proxyPlayer, adminRequest.get());
            return;
        }

        // 本次提交只取一次快照：文案、客户端与 flow 都来自同一代际。flow 无状态，
        // 每次提交现装配，避免 Handler 持有过期客户端或旧文案。
        RuntimeSnapshot runtime = runtime();
        Messages messages = runtime.messages();
        LoginFlow flow = new LoginFlow(sessionStore, runtime.websiteClient(), messages, clock);
        SubmitResult result = flow.submit(identity, message);
        switch (result.kind()) {
            case IGNORED -> {
                // 其他聊天/命令由 LimboAPI 吞掉，忽略。
            }
            case EMPTY_CODE -> send(messages.emptyCode());
            case TOO_LONG -> send(messages.codeTooLong());
            case ALREADY_AUTHENTICATED -> send(messages.alreadyAuthenticated());
            case VERIFYING -> {
                send(messages.verifying());
                long verificationId = result.verificationId();
                String code = result.normalizedCode();
                // 网站调用是阻塞的：交给专用虚拟线程执行器，Velocity 调度池只负责
                // 在结果回来后执行发消息、转服、断开等平台动作。
                if (!plugin.submitWebsiteTask(() -> verify(verificationId, code))) {
                    // 并发已满：把会话退回 PENDING，玩家可以立刻重试。
                    sessionStore.authenticationFailed(uuid, verificationId, clock.instant());
                    send(messages.serviceUnavailable());
                }
            }
            case REJECTED -> {
                // 只有仍在验证中才提示“正在验证”；超时/断线后的提交保持静默。
                boolean verifying = sessionStore.find(uuid)
                        .map(session -> session.status() == LoginSession.Status.VERIFYING)
                        .orElse(false);
                if (verifying) {
                    send(messages.verifying());
                }
            }
        }
    }

    @Override
    public void onDisconnect() {
        closed.set(true);
        // 只注销自己：同一 UUID 的新连接可能已经登记，不能被旧连接的迟到事件删掉。
        limboService.unregister(uuid, this);
        cancelTasks();
    }

    /** 管理员手动放行：提示玩家后转到主服。会话状态已由编排层写入。 */
    public void approveByAdmin(String message) {
        if (closed.get()) {
            return;
        }
        // 已经认证但还卡在 limbo（转服静默失败/尚未 spawn）时，管理员再次
        // approve 应重试转服，而不是直接返回“已经认证”。
        if (authenticated.compareAndSet(false, true)) {
            send(message);
        }
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

    /**
     * 校验登录码。运行在专用虚拟线程上：这里只做 HTTP 与状态机推进，
     * 发消息、转服、断开等平台动作回到 Velocity 调度线程执行。
     *
     * <p>校验可能跨越一次 {@code /hydwind reload}，因此本次操作的 flow、
     * 网站客户端、重启触发器与成功/失败文案全部取自同一个当前代际快照。</p>
     */
    private void verify(long verificationId, String code) {
        if (closed.get()) {
            return;
        }

        RuntimeSnapshot runtime;
        VerifyOutcome outcome;
        try {
            runtime = runtime();
            LoginFlow activeFlow = new LoginFlow(
                    sessionStore, runtime.websiteClient(), runtime.messages(), clock);
            outcome = activeFlow.verify(identity, verificationId, code);
        } catch (RuntimeException exception) {
            // WebsiteClient 已把网络异常映射成结果；这里兜住状态机或装配异常，
            // 不让虚拟线程静默死掉。
            logger.error("Website verification failed for player {}; keeping session PENDING.",
                    gameId, exception);
            sessionStore.authenticationFailed(uuid, verificationId, clock.instant());
            return;
        }

        if (outcome.status() == VerifyResult.Status.UNAUTHORIZED) {
            logger.warn("Website rejected the plugin token (401); check website.token / HYDWIND_PLUGIN_TOKEN.");
        } else if (outcome.status() == VerifyResult.Status.CONTRACT_MISMATCH) {
            logger.warn("Website verify response does not match the plugin contract (player {}); check website/plugin versions.",
                    gameId);
        }

        try {
            server.getScheduler()
                    .buildTask(plugin, () -> applyVerifyOutcome(outcome, runtime))
                    .schedule();
        } catch (RuntimeException exception) {
            // 插件正在关闭，调度器拒绝任务；此时没有需要通知的在线玩家。
            logger.debug("Could not schedule verify result for player {}: {}", gameId,
                    exception.toString());
        }
    }

    private void applyVerifyOutcome(VerifyOutcome outcome, RuntimeSnapshot runtime) {
        if (closed.get()) {
            return;
        }
        if (outcome.authenticated()) {
            // 管理员可能已经并发放行；只让第一个结果发起转服。
            if (!authenticated.compareAndSet(false, true)) {
                return;
            }
            send(outcome.message());
            transferToMainServer();
            return;
        }

        if (!outcome.message().isEmpty()) {
            send(outcome.message());
        }
        if (outcome.triggerRestart()) {
            if (!plugin.submitWebsiteTask(runtime.restartTrigger()::onUnreachable)) {
                logger.debug("Restart trigger skipped: website request slots are full.");
            }
        }
    }

    private void transferToMainServer() {
        if (closed.get()) {
            return;
        }
        LimboPlayer player = limboPlayer;
        if (player == null) {
            // onSpawn 还没拿到 LimboPlayer（例如管理员在 spawn 前放行）。
            // onSpawn 会再调用一次；这里不能提前占用 transferStarted。
            return;
        }
        if (!transferStarted.compareAndSet(false, true)) {
            return;
        }

        // 认证成功到真正离开 limbo 之间可能静默失败：保留一个看门狗，
        // 避免玩家收到成功文案后永久留在已认证的 limbo 里。
        scheduleTransferWatchdog(player);

        RuntimeSnapshot runtime = runtime();
        String mainServer = runtime.config().mainServer();
        Messages activeMessages = runtime.messages();
        Optional<RegisteredServer> target = server.getServer(mainServer);
        if (target.isEmpty()) {
            logger.error("Configured main server '{}' was not found in velocity.toml; "
                    + "authenticated player {} cannot be transferred.", mainServer, gameId);
            proxyPlayer.disconnect(Component.text(activeMessages.serviceMisconfigured()));
            return;
        }
        try {
            player.disconnect(target.get());
        } catch (RuntimeException exception) {
            // LimboAPI 在关闭/连接状态异常时可能抛错；不要把玩家留在 limbo。
            logger.error("Failed to transfer player {} to server '{}'; disconnecting.",
                    gameId, mainServer, exception);
            closed.set(true);
            cancelTasks();
            proxyPlayer.disconnect(Component.text(activeMessages.serviceMisconfigured()));
        }
    }

    private void scheduleTransferWatchdog(LimboPlayer player) {
        ScheduledFuture<?> prompt = promptTask;
        if (prompt != null) {
            prompt.cancel(false);
        }
        ScheduledFuture<?> timeout = timeoutTask;
        if (timeout != null) {
            timeout.cancel(false);
        }
        try {
            timeoutTask = player.getScheduledExecutor().schedule(
                    this::transferWatchdog,
                    TRANSFER_WATCHDOG_SECONDS, TimeUnit.SECONDS);
        } catch (RuntimeException exception) {
            // 玩家连接已关闭时调度会失败；断开事件自会清理 handler。
            logger.debug("Could not schedule transfer watchdog for player {}: {}",
                    gameId, exception.toString());
        }
    }

    private void transferWatchdog() {
        if (closed.get()) {
            return;
        }
        // 转服成功但 onDisconnect 尚未回调时，玩家已经连到后端；不能把他从主服踢掉。
        if (proxyPlayer.getCurrentServer().isPresent()) {
            return;
        }
        logger.error("Player {} did not leave limbo within {}s after authentication; disconnecting.",
                gameId, TRANSFER_WATCHDOG_SECONDS);
        closed.set(true);
        cancelTasks();
        String message;
        try {
            message = runtime().messages().serviceMisconfigured();
        } catch (IllegalStateException runtimeGone) {
            message = me.hydwind.gate.ui.Messages.defaults().serviceMisconfigured();
        }
        proxyPlayer.disconnect(Component.text(message));
    }

    private void timeOut() {
        if (closed.get() || authenticated.get()) {
            return;
        }
        // 会话已被其它路径删除（例如旧连接的迟到断线事件）：handler 不能继续
        // 空转，按超时断开，否则提示任务会一直刷新到玩家手动退出。
        RuntimeSnapshot runtime = runtime();
        Messages messages = runtime.messages();
        if (sessionStore.find(uuid).isEmpty()) {
            closed.set(true);
            cancelTasks();
            proxyPlayer.disconnect(Component.text(messages.sessionTimeout()));
            return;
        }
        // 超时可能由 reload 前排下的任务触发；文案仍取触发时的当前代际。
        LoginFlow flow = new LoginFlow(sessionStore, runtime.websiteClient(), messages, clock);
        if (flow.timeout(identity)) {
            closed.set(true);
            cancelTasks();
            proxyPlayer.disconnect(Component.text(messages.sessionTimeout()));
        }
    }

    private void sendPrompt() {
        if (closed.get() || authenticated.get()) {
            return;
        }
        // 提示每 10 秒重发一次，可能跨越 reload；文案与网址取当前代际。
        RuntimeSnapshot runtime = runtime();
        Messages promptMessages = runtime.messages();
        String baseUrl = runtime.config().websiteBaseUrl();
        String url = Urls.stripTrailingSlashes(baseUrl) + "/gate";
        PromptText prompt = PromptText.fromTemplate(promptMessages.gatePrompt(), url);

        Component link = Component.text(prompt.url())
                .clickEvent(ClickEvent.openUrl(prompt.url()))
                .color(NamedTextColor.AQUA)
                .decorate(TextDecoration.UNDERLINED);
        Component instruction = Component.text(prompt.beforeUrl())
                .append(link)
                .append(Component.text(prompt.afterUrl()));

        if (!closed.get()) {
            proxyPlayer.sendMessage(Component.text(promptMessages.gateTitle()).color(NamedTextColor.GOLD));
            proxyPlayer.sendMessage(instruction);
            // 明确告诉玩家当前流程位置，避免只看到网址不知道还要做什么。
            proxyPlayer.sendMessage(Component.text(promptMessages.statusUnverified()));
        }
    }

    private boolean sessionAuthenticated() {
        return sessionStore.find(uuid)
                .map(LoginSession::isAuthenticated)
                .orElse(false);
    }

    /**
     * 本次操作的当前运行时代际。插件启动后必然已发布快照；未发布说明生命周期错乱，
     * 直接抛出而不是回落到可能过期的引用。
     */
    private RuntimeSnapshot runtime() {
        RuntimeSnapshot snapshot = plugin.runtime().current();
        if (snapshot == null) {
            throw new IllegalStateException(
                    "运行时配置尚未发布；登录会话处理器应在插件初始化完成后创建。");
        }
        return snapshot;
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

    /**
     * 插件关闭时调用：取消本会话的提示与超时任务并标记关闭，避免插件卸载后
     * 仍有任务在 LimboAPI 的执行器上运行。
     */
    public void shutdown() {
        closed.set(true);
        cancelTasks();
    }

    /**
     * 60 秒 sweep 发现该会话已过期时调用。与 per-player 定时任务使用同一
     * 终止路径，但由 sweep 驱动，保证定时器丢失时玩家仍会被踢出。
     *
     * <p>sweep 持有的是扫描时刻的快照，这之后玩家可能已被管理员放行、或已换新会话；
     * 因此这里必须复核本 handler 的认证标志，且只在实际仍超时时才断开。</p>
     *
     * @return 本 handler 是否因此被关闭
     */
    public boolean timeOutNow(String message) {
        if (authenticated.get()) {
            return false;
        }
        // 先让会话 Module 做唯一判定：只有确实仍处于超时状态才继续。
        // 顺序不能反过来——先 CAS 再复核，若管理员 approve/kick 插在中间，
        // 回滚 closed 会留下“会话已认证但 handler 仍拒绝转服”或
        // “玩家在线却已从两个登记表消失”的不可恢复状态。
        if (!sessionStore.timeOut(uuid, clock.instant())) {
            return false;
        }
        if (!closed.compareAndSet(false, true)) {
            return false;
        }
        cancelTasks();
        proxyPlayer.disconnect(Component.text(message));
        return true;
    }
}