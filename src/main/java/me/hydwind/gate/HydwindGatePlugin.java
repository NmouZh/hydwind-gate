package me.hydwind.gate;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import me.hydwind.gate.auth.LoginSessionStore;
import me.hydwind.gate.command.AdminCommandExecutor;
import me.hydwind.gate.command.HydwindCommand;
import me.hydwind.gate.compat.VersionCompat;
import me.hydwind.gate.config.PluginConfig;
import me.hydwind.gate.config.RestartSettings;
import me.hydwind.gate.login.LimboLoginService;
import me.hydwind.gate.login.LoginGateListener;
import me.hydwind.gate.ui.Messages;
import me.hydwind.gate.web.HttpWebsiteClient;
import me.hydwind.gate.web.RestartPolicy;
import me.hydwind.gate.web.RestartTrigger;
import me.hydwind.gate.web.Urls;
import me.hydwind.gate.web.WebsiteClient;
import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * HydwindGate 的 Velocity 插件入口。
 *
 * <p>启动时检查 Velocity 版本与 LimboAPI 是否满足要求，然后装配网站客户端、
 * 会话存储、受控重启组件、limbo 登录流程与 {@code /hydwind} 管理命令。
 * 任何关键组件初始化失败都会关闭代理，避免在没有门禁的情况下放行玩家。</p>
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
    public static final String VERSION = "0.1.0";

    private static final String CONFIG_FILE = "config.yml";
    /** 清理过期断线宽限记录的间隔。 */
    private static final long SESSION_SWEEP_SECONDS = 60L;

    private final Clock clock = Clock.systemUTC();
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private PluginConfig pluginConfig;
    private Messages messages;
    private LoginSessionStore sessionStore;
    private WebsiteClient websiteClient;
    private RestartPolicy restartPolicy;
    private RestartTrigger restartTrigger;
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
        loadConfiguration();

        limboLoginService = new LimboLoginService(this, server, logger);
        try {
            limboLoginService.initialize();
        } catch (RuntimeException exception) {
            // 没有 limbo 就无法拦截未认证玩家；宁可关闭代理也不放行。
            logger.error("初始化 limbo 登录门禁失败，正在关闭代理。", exception);
            server.shutdown();
            return;
        }

        server.getEventManager().register(this, new LoginGateListener(this, limboLoginService, logger));

        adminCommandExecutor = new AdminCommandExecutor(this, limboLoginService);
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

        logger.info("HydwindGate {} 已启用：主服={}，limbo 世界={}，token={}。",
                VERSION, pluginConfig.mainServer(), pluginConfig.limboWorld(),
                tokenState(pluginConfig));
        logger.info("网站：base-url={}，健康检查路径={}，连接超时={}ms，读取超时={}ms，"
                        + "登录超时={}s，断线宽限={}s，提示间隔={}s。",
                pluginConfig.websiteBaseUrl(), pluginConfig.healthPath(),
                pluginConfig.connectTimeoutMillis(), pluginConfig.readTimeoutMillis(),
                pluginConfig.loginTimeout().toSeconds(), pluginConfig.reconnectGrace().toSeconds(),
                pluginConfig.promptInterval().toSeconds());
        logger.info("受控重启：请求文件={}，冷却={}s，窗口={}s，窗口内上限={}。",
                pluginConfig.restart().requestFile(),
                pluginConfig.restart().cooldown().toSeconds(),
                pluginConfig.restart().window().toSeconds(),
                pluginConfig.restart().maxPerWindow());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (sessionMaintenanceTask != null) {
            sessionMaintenanceTask.cancel();
            sessionMaintenanceTask = null;
        }
        if (limboLoginService != null) {
            limboLoginService.shutdown();
        }
        logger.info("HydwindGate 已停用。");
    }

    /**
     * 重新读取 {@code config.yml} 并重建依赖配置的组件。会话存储与重启计数被保留，
     * 因此热重载不会打断进行中的登录，也不会清空重启冷却。
     *
     * <p>{@code auth.session-grace-seconds} 立即生效；{@code auth.prompt-interval-seconds}
     * 与 {@code auth.login-timeout-seconds} 只对之后进入 limbo 的会话生效，因为已 spawn
     * 的会话定时器无法安全重排。</p>
     */
    public void reloadConfiguration() {
        PluginConfig previous = pluginConfig;
        String previousLimbo = limboKey(previous);
        loadConfiguration();
        if (previousLimbo != null && !previousLimbo.equals(limboKey(pluginConfig))) {
            logger.warn("limbo.world 或 limbo.spawn 已变更；需重启代理后生效"
                    + "（当前 limbo 及其中的玩家会保留）。");
        }
        logger.info("配置已重载：website.base-url {} -> {}，token {} -> {}，主服 {} -> {}，limbo 世界 {} -> {}；"
                        + "活动登录会话已保留。",
                previous == null ? "-" : previous.websiteBaseUrl(),
                pluginConfig.websiteBaseUrl(),
                previous == null ? "未配置" : tokenState(previous),
                tokenState(pluginConfig),
                previous == null ? "-" : previous.mainServer(),
                pluginConfig.mainServer(),
                previous == null ? "-" : previous.limboWorld(),
                pluginConfig.limboWorld());
    }

    private void expireDisconnectedSessions() {
        int removed = sessionStore.expireGrace(clock.instant());
        if (removed > 0) {
            logger.info("已清理 {} 条过期断线宽限记录。", removed);
        }
    }

    private static String tokenState(PluginConfig config) {
        return config == null || config.pluginToken().isBlank() ? "未配置" : "已配置";
    }

    private static String limboKey(PluginConfig config) {
        return config == null ? null : config.limboWorld() + "|" + config.limboSpawn();
    }

    private void loadConfiguration() {
        Map<String, Object> raw = readConfigFile();
        PluginConfig config = PluginConfig.from(raw);
        Messages newMessages = Messages.from(raw);

        if (config.pluginToken().isBlank()) {
            logger.warn("website.token 为空且未设置 {}；网站校验将返回 401 失败。",
                    PluginConfig.TOKEN_ENV);
        } else {
            logger.debug("website.token 已配置（值不打印）。");
        }
        if (config.websiteBaseUrl().isBlank()) {
            String message = "website.base-url 不能为空";
            logger.error(message);
            throw new IllegalStateException(message);
        }
        if (!Urls.isAbsoluteHttpUrl(config.websiteBaseUrl())) {
            String message = "website.base-url 必须是 http:// 或 https:// 开头的绝对地址，当前值："
                    + config.websiteBaseUrl();
            logger.error(message);
            throw new IllegalStateException(message);
        }

        WebsiteClient client = new HttpWebsiteClient(config);
        RestartSettings restartSettings = config.restart();
        if (restartPolicy == null) {
            restartPolicy = new RestartPolicy(restartSettings);
        } else {
            restartPolicy.reconfigure(restartSettings);
        }

        // 只在首次装配，重载时保留内存中的认证会话与断线宽限记录。
        if (sessionStore == null) {
            sessionStore = new LoginSessionStore(config.reconnectGrace());
        } else {
            // 宽限时长可以在 reload 后立即生效，不影响已有会话。
            sessionStore.setReconnectGrace(config.reconnectGrace());
        }

        this.pluginConfig = config;
        this.messages = newMessages;
        this.websiteClient = client;
        this.restartTrigger = new RestartTrigger(client, restartPolicy, restartSettings,
                clock, logger);
    }

    /**
     * 启动前的运行时检查。Velocity 没有插件级“禁用” API，因此不兼容时记录明确错误
     * 并关闭代理：宁可停机，也不要在没有门禁的情况下继续对外服务。
     */
    private boolean ensureCompatibleRuntime() {
        String velocityVersion = server.getVersion().getVersion();
        if (!VersionCompat.isSupportedVelocity(velocityVersion)) {
            logger.error("HydwindGate 需要 Velocity {} 或更高版本，但当前代理报告 '{}'。"
                            + "为避免在没有登录门禁的情况下运行，将关闭代理。",
                    VersionCompat.MINIMUM_VELOCITY_VERSION, velocityVersion);
            server.shutdown();
            return false;
        }

        if (server.getPluginManager().getPlugin("limboapi").isEmpty()) {
            logger.error("LimboAPI 未加载；请把 limboapi-1.2.0.jar 安装到代理的 plugins "
                    + "目录。没有 limbo 时 HydwindGate 无法拦截玩家，将关闭代理。");
            server.shutdown();
            return false;
        }

        logger.info("运行时检查通过：Velocity {}，LimboAPI 已安装。", velocityVersion);
        return true;
    }

    private Map<String, Object> readConfigFile() {
        Path configFile = dataDirectory.resolve(CONFIG_FILE);
        try {
            Files.createDirectories(dataDirectory);
            if (Files.notExists(configFile)) {
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
                    throw new IllegalStateException("config.yml 必须包含 YAML 映射：" + configFile);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("读取配置失败：" + configFile, exception);
        }
    }

    private void copyDefaultConfig(Path configFile) throws IOException {
        try (InputStream defaultConfig = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (defaultConfig == null) {
                throw new IllegalStateException("插件 jar 中缺少内置的 " + CONFIG_FILE);
            }
            Files.copy(defaultConfig, configFile);
            logger.info("已创建默认配置文件：{}。", configFile);
        }
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
        return pluginConfig;
    }

    public Messages messages() {
        return messages;
    }

    public LoginSessionStore sessionStore() {
        return sessionStore;
    }

    public WebsiteClient websiteClient() {
        return websiteClient;
    }

    public RestartPolicy restartPolicy() {
        return restartPolicy;
    }

    public RestartTrigger restartTrigger() {
        return restartTrigger;
    }

    public AdminCommandExecutor adminCommandExecutor() {
        return adminCommandExecutor;
    }
}