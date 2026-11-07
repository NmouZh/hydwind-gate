package me.hydwind.gate;

import java.time.Clock;
import java.util.concurrent.Executor;
import me.hydwind.gate.auth.AdminService;
import me.hydwind.gate.auth.LoginManager;
import me.hydwind.gate.auth.LoginSessionStore;
import me.hydwind.gate.command.AdminCommand;
import me.hydwind.gate.command.LoginCommand;
import me.hydwind.gate.config.PluginConfig;
import me.hydwind.gate.config.RestartSettings;
import me.hydwind.gate.config.SpawnPoint;
import me.hydwind.gate.listener.DialogListener;
import me.hydwind.gate.listener.FreezeController;
import me.hydwind.gate.listener.JoinListener;
import me.hydwind.gate.listener.QuitListener;
import me.hydwind.gate.ui.BukkitPlayerGateway;
import me.hydwind.gate.ui.DialogFactory;
import me.hydwind.gate.ui.Messages;
import me.hydwind.gate.ui.PlayerGateway;
import me.hydwind.gate.web.HttpWebsiteClient;
import me.hydwind.gate.web.RestartPolicy;
import me.hydwind.gate.web.RestartTrigger;
import me.hydwind.gate.web.WebsiteClient;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class HydwindGatePlugin extends JavaPlugin {

    private static final long TICK_INTERVAL = 20L;

    private final Clock clock = Clock.systemUTC();

    private PluginConfig pluginConfig;
    private Messages messages;
    private LoginSessionStore sessionStore;
    private WebsiteClient websiteClient;
    private RestartPolicy restartPolicy;
    private RestartTrigger restartTrigger;
    private FreezeController freezeController;
    private DialogFactory dialogFactory;
    private LoginManager loginManager;
    private BukkitTask timeoutTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        sessionStore = new LoginSessionStore(PluginConfig.from(getConfig()).reconnectGrace());
        freezeController = new FreezeController(this, this::resolveSpawn);
        rebuildRuntime();

        // 任务只启动一次；lambda 每次读取当前 loginManager，重载后自动使用新实例。
        timeoutTask = getServer().getScheduler().runTaskTimer(this,
                () -> {
                    LoginManager current = loginManager;
                    if (current != null) {
                        current.tick();
                    }
                }, TICK_INTERVAL, TICK_INTERVAL);
        getLogger().info("HydwindGate enabled.");
    }

    @Override
    public void onDisable() {
        if (timeoutTask != null) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
        HandlerList.unregisterAll(this);
        getLogger().info("HydwindGate disabled.");
    }

    /**
     * 读取配置并重建所有依赖配置的组件。会话存储与冻结状态被保留，
     * 因此重载不会打断进行中的登录，也不会解冻已冻结的玩家。
     */
    public void reloadRuntime() {
        reloadConfig();
        rebuildRuntime();
        getLogger().info("Configuration reloaded; active login sessions were preserved.");
    }

    private void rebuildRuntime() {
        pluginConfig = PluginConfig.from(getConfig());
        messages = Messages.from(getConfig());
        if (pluginConfig.pluginToken().isBlank()) {
            getLogger().warning("website.token 为空，且未设置 " + PluginConfig.TOKEN_ENV
                    + " 环境变量；网站校验会返回 401。");
        }

        websiteClient = new HttpWebsiteClient(pluginConfig);
        RestartSettings restartSettings = pluginConfig.restart();
        restartPolicy = new RestartPolicy(restartSettings.cooldown(), restartSettings.window(),
                restartSettings.maxPerWindow());
        restartTrigger = new RestartTrigger(websiteClient, restartPolicy, restartSettings,
                clock, getLogger());
        dialogFactory = new DialogFactory(pluginConfig, messages);

        PlayerGateway playerGateway = new BukkitPlayerGateway(this, freezeController, dialogFactory);
        Executor asyncExecutor = runnable ->
                getServer().getScheduler().runTaskAsynchronously(this, runnable);
        loginManager = new LoginManager(websiteClient, sessionStore, playerGateway, messages,
                pluginConfig.loginTimeout(), clock, asyncExecutor, uuid -> restartTrigger.onUnreachable());

        registerListeners();
        registerCommands(playerGateway, asyncExecutor);
        getLogger().info("Configuration loaded; website base URL: " + pluginConfig.websiteBaseUrl()
                + ", health path: " + pluginConfig.healthPath()
                + ", restart request: " + restartSettings.requestFile());
    }

    private void registerListeners() {
        // 重载时先清空旧监听器，避免同一事件被新旧实例各处理一次。
        HandlerList.unregisterAll(this);
        getServer().getPluginManager().registerEvents(new JoinListener(loginManager), this);
        getServer().getPluginManager().registerEvents(new QuitListener(loginManager, freezeController), this);
        getServer().getPluginManager().registerEvents(new DialogListener(loginManager), this);
        getServer().getPluginManager().registerEvents(freezeController, this);
    }

    private void registerCommands(PlayerGateway playerGateway, Executor asyncExecutor) {
        PluginCommand loginCommand = getCommand("login");
        if (loginCommand == null) {
            getLogger().severe("plugin.yml 缺少 login 命令声明，/login 将不可用。");
        } else {
            loginCommand.setExecutor(new LoginCommand(loginManager));
        }

        PluginCommand adminCommand = getCommand("hydwind");
        if (adminCommand == null) {
            getLogger().severe("plugin.yml 缺少 hydwind 命令声明，/hydwind 将不可用。");
            return;
        }
        AdminService adminService = new AdminService(sessionStore, playerGateway,
                websiteClient, restartPolicy, clock);
        AdminCommand executor = new AdminCommand(
                adminService,
                () -> pluginConfig.adminPermission(),
                this::reloadRuntime,
                asyncExecutor,
                runnable -> getServer().getScheduler().runTask(this, runnable));
        adminCommand.setExecutor(executor);
        adminCommand.setTabCompleter(executor);
    }

    private Location resolveSpawn() {
        SpawnPoint point = pluginConfig.spawn();
        World world = getServer().getWorld(point.world());
        if (world == null) {
            if (getServer().getWorlds().isEmpty()) {
                getLogger().warning("没有已加载的世界，无法解析登录出生点。");
                return null;
            }
            world = getServer().getWorlds().get(0);
            getLogger().warning("配置的世界 " + point.world() + " 不存在，改用 "
                    + world.getName() + " 作为登录出生点。");
        }
        return new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
    }

    public PluginConfig pluginConfig() {
        return pluginConfig;
    }

    public Messages messages() {
        return messages;
    }

    public LoginManager loginManager() {
        return loginManager;
    }

    public FreezeController freezeController() {
        return freezeController;
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
}