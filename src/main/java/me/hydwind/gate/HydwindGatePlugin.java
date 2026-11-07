package me.hydwind.gate;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import me.hydwind.gate.auth.LoginSessionStore;
import me.hydwind.gate.config.PluginConfig;
import me.hydwind.gate.config.RestartSettings;
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
 * <p>V1 只负责加载配置并装配可复用的网站客户端、会话存储与受控重启组件；
 * limbo 登录流程（V2）与管理命令（V3）会在此基础上注册监听器与命令。</p>
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
    private static final java.util.logging.Logger RESTART_LOGGER =
            java.util.logging.Logger.getLogger("me.hydwind.gate.RestartTrigger");

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

    @Inject
    public HydwindGatePlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = Objects.requireNonNull(server, "server");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        loadConfiguration();
        logger.info("HydwindGate {} enabled (main server: {}, limbo world: {}).",
                VERSION, pluginConfig.mainServer(), pluginConfig.limboWorld());
        logger.info("Website: {}, health path: {}, restart request: {}.",
                pluginConfig.websiteBaseUrl(), pluginConfig.healthPath(),
                pluginConfig.restart().requestFile());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("HydwindGate disabled.");
    }

    /**
     * 重新读取 {@code config.yml} 并重建依赖配置的组件。会话存储与重启计数被保留，
     * 因此热重载不会打断进行中的登录，也不会清空重启冷却。
     */
    public void reloadConfiguration() {
        loadConfiguration();
        logger.info("Configuration reloaded; active login sessions were preserved.");
    }

    private void loadConfiguration() {
        Map<String, Object> raw = readConfigFile();
        PluginConfig config = PluginConfig.from(raw);
        Messages newMessages = Messages.from(raw);

        if (config.pluginToken().isBlank()) {
            logger.warn("website.token is blank and {} is not set; website verification will fail with 401.",
                    PluginConfig.TOKEN_ENV);
        }
        if (config.websiteBaseUrl().isBlank()) {
            throw new IllegalStateException("website.base-url must not be blank");
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
        }

        this.pluginConfig = config;
        this.messages = newMessages;
        this.websiteClient = client;
        this.restartTrigger = new RestartTrigger(client, restartPolicy, restartSettings,
                clock, RESTART_LOGGER);
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

    public ProxyServer proxyServer() {
        return server;
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
}