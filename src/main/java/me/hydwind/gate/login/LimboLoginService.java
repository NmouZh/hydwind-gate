package me.hydwind.gate.login;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.List;
import java.util.Objects;
import me.hydwind.gate.HydwindGatePlugin;
import me.hydwind.gate.config.PluginConfig;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.command.LimboCommandMeta;
import net.elytrium.limboapi.api.player.GameMode;
import org.slf4j.Logger;

/**
 * 创建并持有未认证玩家的虚拟 limbo 世界，负责把玩家放进去或放行登录。
 *
 * <p>LimboAPI 1.2.0 与 Velocity 版本强绑定；工厂从已安装的 {@code limboapi}
 * 插件实例取得，缺失时启动失败并给出明确原因。</p>
 */
public final class LimboLoginService {

    private final HydwindGatePlugin plugin;
    private final ProxyServer server;
    private final Logger logger;

    private LimboFactory factory;
    private Limbo limbo;

    public LimboLoginService(HydwindGatePlugin plugin, ProxyServer server, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = Objects.requireNonNull(server, "server");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** 读取配置并创建虚拟世界与 limbo。应在代理初始化事件中调用一次。 */
    public void initialize() {
        this.factory = resolveFactory();
        PluginConfig config = plugin.pluginConfig();
        LimboSpawn spawn = LimboSpawn.parse(config.limboSpawn());

        VirtualWorld world = factory.createVirtualWorld(
                Dimension.OVERWORLD, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());

        if (limbo != null) {
            limbo.dispose();
        }
        limbo = factory.createLimbo(world)
                .setName(config.limboWorld())
                .setGameMode(GameMode.ADVENTURE)
                .setShouldRejoin(true)
                .setShouldRespawn(false)
                // 仅用于客户端 Tab 补全；真正的解析发生在 onChat。
                .registerCommand(new LimboCommandMeta(List.of("login")));

        logger.info("Limbo '{}' created at ({}, {}, {}).",
                config.limboWorld(), spawn.x(), spawn.y(), spawn.z());
    }

    /** 让玩家跳过 limbo，按正常流程完成登录。 */
    public void passLogin(Player player) {
        factory.passLoginLimbo(player);
    }

    /** 把玩家放进 limbo；无论调用线程为何，LimboAPI 会切回玩家事件循环。 */
    public void spawnInLimbo(Player player) {
        if (limbo == null || factory == null) {
            throw new IllegalStateException("Limbo is not initialized");
        }
        limbo.spawnPlayer(player, new LoginSessionHandler(plugin, player));
    }

    public void shutdown() {
        if (limbo != null) {
            limbo.dispose();
            limbo = null;
        }
    }

    private LimboFactory resolveFactory() {
        Object instance = server.getPluginManager().getPlugin("limboapi")
                .flatMap(PluginContainer::getInstance)
                .orElseThrow(() -> new IllegalStateException(
                        "LimboAPI is not loaded; install limboapi-1.2.0.jar in the proxy plugins directory"));
        if (!(instance instanceof LimboFactory limboFactory)) {
            throw new IllegalStateException(
                    "limboapi plugin did not expose a LimboFactory (got " + instance.getClass().getName() + ")");
        }
        return limboFactory;
    }
}