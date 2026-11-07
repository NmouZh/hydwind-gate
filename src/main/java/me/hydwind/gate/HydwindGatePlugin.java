package me.hydwind.gate;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import me.hydwind.gate.admin.AdminActionService;
import me.hydwind.gate.auth.LoginSession;
import me.hydwind.gate.auth.LoginSessionStore;
import me.hydwind.gate.command.AdminCommandExecutor;
import me.hydwind.gate.command.HydwindCommand;
import me.hydwind.gate.compat.LimboCompat;
import me.hydwind.gate.compat.VersionCompat;
import me.hydwind.gate.config.LimboSettings;
import me.hydwind.gate.config.PluginConfig;
import me.hydwind.gate.config.RestartSettings;
import me.hydwind.gate.login.LimboLoginService;
import me.hydwind.gate.login.LimboSpawn;
import me.hydwind.gate.login.LoginGateListener;
import me.hydwind.gate.runtime.RuntimeHolder;
import me.hydwind.gate.runtime.RuntimeSnapshot;
import me.hydwind.gate.ui.Messages;
import me.hydwind.gate.web.HttpWebsiteClient;
import me.hydwind.gate.web.RestartPolicy;
import me.hydwind.gate.web.RestartTrigger;
import me.hydwind.gate.web.WebsiteClient;
import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * HydwindGate 的 Velocity 插件入口。
 *
 * <p>启动时检查 Velocity 版本与 LimboAPI 版本，然后装配网站客户端、会话存储、
 * 受控重启组件、limbo 登录流程与 {@code /hydwind} 管理命令。任何关键组件初始化
 * 失败都会关闭代理，避免在没有门禁的情况下放行玩家。</p>
 *
 * <p>配置、文案、网站客户端与重启触发器组成一整代 {@link RuntimeSnapshot}；
 * 热重载时构建完整的新代际、一次发布并关闭旧代际。读取方通过 {@link #runtime()}
 * 或各个 accessor 取值，未发布时访问会抛出 {@link IllegalStateException}。</p>
 */
@Plugin(
        id = "hydwind-gate",
        name = "HydwindGate",
        version = HydwindGatePlugin.VERSION,
        description = "氢风进服门禁（Velocity 代理 + LimboAPI）",
        authors = {"NmouZh"},
        url = "https://github.com/NmouZh/hydwind-gate",
        dependencies = {@Dependency(id = "limboapi")}
)
public final class HydwindGatePlugin {

    /** 与 build.gradle.kts 的 project.version 保持一致。 */
    public static final String VERSION = "0.2.0";

    private static final String CONFIG_FILE = "config.yml";
    /** 清理过期断线宽限记录的间隔。 */
    private static final long SESSION_SWEEP_SECONDS = 60L;

    private final Clock clock = Clock.systemUTC();
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    /** 运行时代际；配置、文案、网站客户端与重启触发器整代替换。 */
    private final RuntimeHolder runtimeHolder = new RuntimeHolder();

    // 以下两项跨代际保留：会话不能因 reload 丢失，重启冷却与计数也不能被 reload 重置。
    private LoginSessionStore sessionStore;
    private RestartPolicy restartPolicy;

    private LimboLoginService limboLoginService;
    private AdminCommandExecutor adminCommandExecutor;
    private ScheduledTask sessionMaintenanceTask;

    @Inject
    public HydwindGatePlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = Objects.requireNonNull(server, "server");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        if (!ensureCompatibleRuntime()) {
            return;
        }

        // 装配体的任何一步失败都不能继续以“无门禁”状态运行；统一关闭代理。
        try {
            // 首次启动允许 config.yml 缺失：从 jar 内复制默认配置后再读取。
            loadConfiguration(true);

            limboLoginService = new LimboLoginService(this, server, logger);
            limboLoginService.initialize();

            server.getEventManager().register(this, new LoginGateListener(this, limboLoginService, logger));

            adminCommandExecutor = new AdminCommandExecutor(this,
                    new AdminActionService(sessionStore, limboLoginService, backendPlayers(), clock),
                    sessionStore, limboLoginService, this::reloadConfiguration);
            CommandMeta commandMeta = server.getCommandManager()
                    .metaBuilder("hydwind")
                    .plugin(this)
                    .build();
            server.getCommandManager().register(commandMeta, new HydwindCommand(this, adminCommandExecutor));

            sessionMaintenanceTask = server.getScheduler()
                    .buildTask(this, this::expireDisconnectedSessions)
                    .delay(SESSION_SWEEP_SECONDS, TimeUnit.SECONDS)
                    .repeat(SESSION_SWEEP_SECONDS, TimeUnit.SECONDS)
                    .schedule();

            PluginConfig config = pluginConfig();
            logger.info("HydwindGate {} enabled (main server: {}, limbo world: {}).",
                    VERSION, config.mainServer(), config.limboWorld());
            // 同时打印“配置值”与“生效值”：limbo 设置在 initialize 时固定，reload 只告警。
            LimboSettings activeLimbo = limboLoginService.activeLimboSettings();
            logger.info("Limbo settings: configured world '{}', spawn '{}'; active world '{}', spawn '{}'.",
                    config.limboWorld(), config.limboSpawn(),
                    activeLimbo.world(), activeLimbo.spawn());
            if (!config.limboWorld().equals(activeLimbo.world())
                    || !config.limboSpawn().equals(activeLimbo.spawn())) {
                logger.warn("Configured limbo settings differ from the active ones; "
                        + "restart the proxy to apply limbo.world / limbo.spawn.");
            }
            logger.info("Website: {}, health path: {}, restart request: {}.",
                    config.websiteBaseUrl(), config.healthPath(),
                    config.restart().requestFile());
        } catch (RuntimeException exception) {
            logger.error("Failed to initialize HydwindGate; shutting the proxy down instead of "
                    + "running without a login gate.", exception);
            server.shutdown();
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (sessionMaintenanceTask != null) {
            sessionMaintenanceTask.cancel();
            sessionMaintenanceTask = null;
        }
        // 先释放 limbo（会清理仍留在 limbo 的玩家），再关闭当前运行时资源（HTTP 连接池）。
        if (limboLoginService != null) {
            limboLoginService.shutdown();
        }
        runtimeHolder.close();
        logger.info("HydwindGate disabled.");
    }

    /**
     * 重新读取 {@code config.yml} 并构建完整的新运行时代际。会话存储与重启计数被保留，
     * 因此热重载不会打断进行中的登录，也不会清空重启冷却。
     *
     * <p>新代际一次发布，旧代际随后关闭；发布之后到达的读取方要么看到完整旧代际，
     * 要么看到完整新代际。方法加 {@code synchronized}，避免并发 reload 交错替换。</p>
     *
     * <p>{@code auth.session-grace-seconds} 立即生效；{@code auth.prompt-interval-seconds}
     * 与 {@code auth.login-timeout-seconds} 只对之后进入 limbo 的会话生效，因为已 spawn
     * 的会话定时器无法安全重排。</p>
     *
     * <p>reload 期间 {@code config.yml} 缺失视为错误：不会重建默认配置，也不会替换
     * 当前运行时代际。所有校验都在 {@link RuntimeHolder#publish} 之前完成，失败时
     * 旧代际继续服务。</p>
     */
    public synchronized void reloadConfiguration() {
        PluginConfig previous = currentConfigOrNull();
        // reload 不创建默认配置：误删文件时保留当前配置，而不是悄悄恢复默认值。
        loadConfiguration(false);
        PluginConfig current = pluginConfig();
        if (previous != null && !limboKey(previous).equals(limboKey(current))) {
            logger.warn("limbo.world or limbo.spawn changed; restart the proxy to apply them "
                    + "(the current limbo and its players are kept).");
        }
        logger.info("Configuration reloaded; active login sessions were preserved.");
    }

    /**
     * 把 Velocity 的在线玩家视图适配成 {@link AdminActionService.BackendPlayers}。
     * 管理动作 Module 只依赖这个窄 Interface，不直接认识 {@link ProxyServer}。
     */
    private AdminActionService.BackendPlayers backendPlayers() {
        return new AdminActionService.BackendPlayers() {
            @Override
            public Optional<UUID> findOnline(String gameId) {
                return server.getPlayer(gameId).map(Player::getUniqueId);
            }

            @Override
            public void disconnect(UUID uuid, String message) {
                server.getPlayer(uuid).ifPresent(player ->
                        player.disconnect(net.kyori.adventure.text.Component.text(message)));
            }

            @Override
            public Optional<String> nameOf(UUID uuid) {
                return server.getPlayer(uuid).map(Player::getUsername);
            }
        };
    }

    /**
     * 60 秒兜底 sweep：先按会话自己的 {@code loginDeadline} 标记超时（主要踢出路径仍是
     * per-player 定时任务），再清理超过断线宽限的认证记录。
     */
    private void expireDisconnectedSessions() {
        Instant now = clock.instant();
        List<LoginSession> timedOut = sessionStore.timeOutAll(now);
        if (!timedOut.isEmpty()) {
            logger.info("Timed out {} pending login session(s): {}",
                    timedOut.size(), timedOut.stream().map(LoginSession::gameId).toList());
            // 只标记不踢人会让玩家卡在 limbo：sweep 必须驱动断开。
            // per-player 定时器仍是主要路径；这里覆盖定时器丢失或先于它执行的情况。
            String message = messages().sessionTimeout();
            for (LoginSession session : timedOut) {
                if (!limboLoginService.timeOut(session.uuid(), message)) {
                    // 不在 limbo 时只需清掉过期会话；用状态比较避免删掉玩家在
                    // timeOutAll 之后重连建立的崭新会话。
                    sessionStore.removeIfTimedOut(session.uuid());
                }
            }
        }
        int removed = sessionStore.expireGrace(now);
        if (removed > 0) {
            logger.info("Expired {} reconnect grace record(s).", removed);
        }
    }

    private static String limboKey(PluginConfig config) {
        return config.limboWorld() + "|" + config.limboSpawn();
    }

    /**
     * 读取配置并构建、发布一整代运行时依赖。会话存储与重启策略是长生命周期对象：
     * 首次创建，之后只更新各自的配置项，不随代际替换。
     *
     * @param allowCreatingDefaultConfig 首次启动为 {@code true}，允许缺失时复制默认配置；
     *                                   reload 为 {@code false}，缺失时直接失败
     */
    private void loadConfiguration(boolean allowCreatingDefaultConfig) {
        Map<String, Object> raw = readConfigFile(allowCreatingDefaultConfig);
        PluginConfig config = PluginConfig.from(raw);
        Messages newMessages = Messages.from(raw);

        // LimboSpawn.parse 对畸形文本静默回落默认出生点；这里提前提示，避免管理员
        // 直到下次重启看到玩家出现在错误坐标才发现配置写错。只告警，不阻断加载。
        LimboSettings limbo = config.limbo();
        if (LimboSpawn.DEFAULT.equals(LimboSpawn.parse(limbo.spawn()))
                && !limbo.spawn().equals(PluginConfig.DEFAULT_LIMBO_SPAWN)) {
            logger.warn("limbo.spawn '{}' is not a valid spawn (expected 'x, y, z[, yaw, pitch]'); "
                            + "the default {} will be used until the proxy restarts with a fixed value.",
                    limbo.spawn(), PluginConfig.DEFAULT_LIMBO_SPAWN);
        }

        if (config.pluginToken().isBlank()) {
            logger.warn("website.token is blank and {} is not set; website verification will fail with 401.",
                    PluginConfig.TOKEN_ENV);
        }
        // 自定义文案缺占位符时玩家会看到原样的 {xxx}；加载时提示管理员，但不改变替换行为。
        Messages.placeholderWarnings(raw).forEach((key, placeholders) ->
                logger.warn("Message '{}' is missing placeholder(s) {}; players will see them literally.",
                        key, placeholders));

        RestartSettings restartSettings = config.restart();
        if (restartPolicy == null) {
            restartPolicy = new RestartPolicy(restartSettings);
        }

        HttpWebsiteClient client = new HttpWebsiteClient(config);
        RestartTrigger trigger;
        try {
            trigger = new RestartTrigger(client, restartPolicy, restartSettings, clock, logger);
        } catch (RuntimeException exception) {
            // 新客户端尚未发布；构造失败时立即关闭，避免连接池泄漏。
            client.close();
            throw exception;
        }

        // 长生命周期状态在新客户端构造成功之后、发布之前更新：构造失败时旧代际保持原样。
        // 重启策略只更新配置并保留已记录的触发历史，避免 reload 清空冷却与窗口内计数。
        restartPolicy.reconfigure(restartSettings);
        // 只在首次装配，重载时保留内存中的认证会话与断线宽限记录。
        if (sessionStore == null) {
            sessionStore = new LoginSessionStore(config.reconnectGrace());
        } else {
            // 宽限时长可以在 reload 后立即生效，不影响已有会话。
            sessionStore.setReconnectGrace(config.reconnectGrace());
        }

        RuntimeSnapshot previous = runtimeHolder.publish(
                new RuntimeSnapshot(config, newMessages, client, trigger));
        if (previous != null) {
            previous.close();
        }
    }

    /**
     * 启动前的运行时检查。Velocity 没有插件级“禁用” API，因此不兼容时记录明确错误
     * 并关闭代理：宁可停机，也不要在没有门禁的情况下继续对外服务。
     */
    private boolean ensureCompatibleRuntime() {
        String velocityVersion = server.getVersion().getVersion();
        if (!VersionCompat.isSupportedVelocity(velocityVersion)) {
            logger.error("HydwindGate requires Velocity {} or newer, but this proxy reports '{}'. "
                            + "Shutting the proxy down instead of running without a login gate.",
                    VersionCompat.MINIMUM_VELOCITY_VERSION, velocityVersion);
            server.shutdown();
            return false;
        }

        Optional<PluginContainer> limboPlugin = server.getPluginManager().getPlugin("limboapi");
        if (limboPlugin.isEmpty()) {
            logger.error("LimboAPI is not loaded; install limboapi-{}.jar in the proxy plugins "
                            + "directory. HydwindGate cannot gate players without limbo and will shut down.",
                    LimboCompat.MINIMUM_LIMBO_VERSION);
            server.shutdown();
            return false;
        }

        String limboVersion = limboPlugin.get().getDescription().getVersion().orElse("");
        if (!LimboCompat.isSupported(limboVersion)) {
            logger.error("HydwindGate requires LimboAPI {} or newer, but this proxy reports '{}'. "
                            + "Shutting the proxy down instead of running without a login gate.",
                    LimboCompat.MINIMUM_LIMBO_VERSION,
                    limboVersion.isBlank() ? "<unknown>" : limboVersion);
            server.shutdown();
            return false;
        }

        // 构建期与运行时 LimboAPI 不一致时只告警：版本号相同（忽略 -SNAPSHOT 等后缀）视为一致。
        LimboCompat.bundledVersion().ifPresent(bundled -> {
            if (!sameVersion(bundled, limboVersion)) {
                logger.warn("HydwindGate was built against LimboAPI {} but this proxy runs {}; "
                                + "LimboAPI is tightly coupled to the proxy and Minecraft version, "
                                + "verify the installed jar.",
                        bundled, limboVersion);
            }
        });

        logger.info("Runtime checks passed: Velocity {}, LimboAPI {}.", velocityVersion, limboVersion);
        return true;
    }

    /** 只比较版本数字部分，避免 {@code 1.2.0} 与 {@code 1.2.0-SNAPSHOT} 被误报为不一致。 */
    private static boolean sameVersion(String left, String right) {
        return VersionCompat.isAtLeast(left, right) && VersionCompat.isAtLeast(right, left);
    }

    /**
     * 读取 {@code config.yml}。
     *
     * @param createDefaultIfMissing 启动时为 {@code true}，缺失则复制 jar 内默认配置；
     *                               reload 时为 {@code false}，缺失直接失败，避免误删文件后
     *                               把所有配置静默恢复默认值
     * @throws IllegalStateException reload 时文件缺失，或读取失败
     */
    private Map<String, Object> readConfigFile(boolean createDefaultIfMissing) {
        Path configFile = dataDirectory.resolve(CONFIG_FILE);
        try {
            Files.createDirectories(dataDirectory);
            if (Files.notExists(configFile)) {
                if (!createDefaultIfMissing) {
                    throw new IllegalStateException(
                            "config.yml is missing; reload aborted, keeping the current runtime");
                }
                copyDefaultConfig(configFile);
            }
            try (InputStream input = Files.newInputStream(configFile)) {
                LoaderOptions options = new LoaderOptions();
                options.setAllowDuplicateKeys(false);
                Object loaded = new Yaml(new SafeConstructor(options)).load(input);
                if (loaded == null) {
                    return Map.of();
                }
                if (!(loaded instanceof Map<?, ?> map)) {
                    throw new IllegalStateException("config.yml must contain a YAML mapping: " + configFile);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + configFile, exception);
        }
    }

    private void copyDefaultConfig(Path configFile) throws IOException {
        try (InputStream defaultConfig = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (defaultConfig == null) {
                throw new IllegalStateException("Bundled " + CONFIG_FILE + " is missing from the plugin jar");
            }
            Files.copy(defaultConfig, configFile);
            logger.info("Created default configuration at {}.", configFile);
        }
    }

    private PluginConfig currentConfigOrNull() {
        RuntimeSnapshot snapshot = runtimeHolder.current();
        return snapshot == null ? null : snapshot.config();
    }

    /** 取当前已发布的代际；尚未发布时抛出明确异常，而不是返回 null 让调用方在远处失败。 */
    private RuntimeSnapshot publishedRuntime() {
        RuntimeSnapshot snapshot = runtimeHolder.current();
        if (snapshot == null) {
            throw new IllegalStateException(
                    "运行时配置尚未发布；请在代理初始化完成后再访问 HydwindGate。");
        }
        return snapshot;
    }

    /** 运行时代际持有者；读取方应在一次操作内取一次快照并复用。 */
    public RuntimeHolder runtime() {
        return runtimeHolder;
    }

    public ProxyServer proxyServer() {
        return server;
    }

    public Clock clock() {
        return clock;
    }

    public Logger logger() {
        return logger;
    }

    public PluginConfig pluginConfig() {
        return publishedRuntime().config();
    }

    public Messages messages() {
        return publishedRuntime().messages();
    }

    public LoginSessionStore sessionStore() {
        return sessionStore;
    }

    public WebsiteClient websiteClient() {
        return publishedRuntime().websiteClient();
    }

    public RestartPolicy restartPolicy() {
        return restartPolicy;
    }

    public RestartTrigger restartTrigger() {
        return publishedRuntime().restartTrigger();
    }

    public AdminCommandExecutor adminCommandExecutor() {
        return adminCommandExecutor;
    }
}