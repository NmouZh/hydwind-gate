package me.hydwind.gate;

import java.time.Clock;
import java.util.concurrent.Executor;
import me.hydwind.gate.auth.LoginManager;
import me.hydwind.gate.auth.LoginSessionStore;
import me.hydwind.gate.command.LoginCommand;
import me.hydwind.gate.config.PluginConfig;
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
import me.hydwind.gate.web.WebsiteClient;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class HydwindGatePlugin extends JavaPlugin {

    private PluginConfig pluginConfig;
    private Messages messages;
    private LoginSessionStore sessionStore;
    private WebsiteClient websiteClient;
    private RestartPolicy restartPolicy;
    private FreezeController freezeController;
    private DialogFactory dialogFactory;
    private LoginManager loginManager;
    private BukkitTask timeoutTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPluginConfig();

        sessionStore = new LoginSessionStore(pluginConfig.reconnectGrace());
        websiteClient = new HttpWebsiteClient(pluginConfig);
        restartPolicy = new RestartPolicy();
        freezeController = new FreezeController(this, this::resolveSpawn);
        dialogFactory = new DialogFactory(pluginConfig, messages);
        PlayerGateway playerGateway = new BukkitPlayerGateway(this, freezeController, dialogFactory);

        Executor asyncExecutor = runnable -> getServer().getScheduler().runTaskAsynchronously(this, runnable);
        loginManager = new LoginManager(websiteClient, sessionStore, playerGateway, messages,
                pluginConfig.loginTimeout(), Clock.systemUTC(), asyncExecutor);

        getServer().getPluginManager().registerEvents(new JoinListener(loginManager), this);
        getServer().getPluginManager().registerEvents(new QuitListener(loginManager, freezeController), this);
        getServer().getPluginManager().registerEvents(new DialogListener(loginManager), this);
        getServer().getPluginManager().registerEvents(freezeController, this);

        PluginCommand loginCommand = getCommand("login");
        if (loginCommand == null) {
            getLogger().severe("plugin.yml 缺少 login 命令声明，/login 将不可用。");
        } else {
            loginCommand.setExecutor(new LoginCommand(loginManager));
        }

        timeoutTask = getServer().getScheduler().runTaskTimer(this, loginManager::tick, 20L, 20L);
        getLogger().info("HydwindGate enabled.");
    }

    @Override
    public void onDisable() {
        if (timeoutTask != null) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
        getLogger().info("HydwindGate disabled.");
    }

    private void reloadPluginConfig() {
        pluginConfig = PluginConfig.from(getConfig());
        messages = Messages.from(getConfig());
        if (pluginConfig.pluginToken().isBlank()) {
            getLogger().warning("website.token 为空，且未设置 " + PluginConfig.TOKEN_ENV
                    + " 环境变量；网站校验会返回 401。");
        }
        getLogger().info("Configuration loaded; website base URL: " + pluginConfig.websiteBaseUrl());
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
}