package me.hydwind.gate.login;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    /** 当前仍在 limbo 的玩家；供管理命令按名字查找与状态统计。 */
    private final Map<UUID, LoginSessionHandler> residents = new ConcurrentHashMap<>();

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

        logger.info("已创建 Limbo '{}'，坐标 ({}, {}, {})，维度 OVERWORLD，游戏模式 ADVENTURE。",
                config.limboWorld(), spawn.x(), spawn.y(), spawn.z());
    }

    /** 让玩家跳过 limbo，按正常流程完成登录。 */
    public void passLogin(Player player) {
        logger.debug("已放行玩家 {}（uuid={}，IP={}）跳过 limbo",
                player.getUsername(), player.getUniqueId(),
                RemoteAddress.remoteIp(player.getRemoteAddress()));
        factory.passLoginLimbo(player);
    }

    /** 把玩家放进 limbo；无论调用线程为何，LimboAPI 会切回玩家事件循环。 */
    public void spawnInLimbo(Player player) {
        String ip = RemoteAddress.remoteIp(player.getRemoteAddress());
        if (limbo == null || factory == null) {
            logger.error("无法把玩家 {}（uuid={}，IP={}）放入 limbo：Limbo 尚未初始化。",
                    player.getUsername(), player.getUniqueId(), ip);
            throw new IllegalStateException("Limbo 尚未初始化");
        }
        logger.info("尝试把玩家放入 limbo：玩家={}，uuid={}，IP={}，当前 limbo 人数={}",
                player.getUsername(), player.getUniqueId(), ip, residents.size());
        try {
            limbo.spawnPlayer(player, new LoginSessionHandler(plugin, player, this));
            logger.debug("已提交 limbo spawn 请求：玩家={}，uuid={}",
                    player.getUsername(), player.getUniqueId());
        } catch (RuntimeException exception) {
            logger.error("玩家 {}（uuid={}，IP={}）limbo spawn 失败。",
                    player.getUsername(), player.getUniqueId(), ip, exception);
            throw exception;
        }
    }

    /** limbo 玩家在 {@code onSpawn} 后登记，供管理命令与状态统计使用。 */
    public void register(LoginSessionHandler handler) {
        residents.put(handler.uuid(), handler);
        logger.info("玩家 {} 已在 limbo 注册（uuid={}），当前 limbo 人数={}",
                handler.gameId(), handler.uuid(), residents.size());
    }

    public void unregister(UUID uuid) {
        LoginSessionHandler removed = residents.remove(uuid);
        if (removed == null) {
            logger.debug("注销 limbo 玩家 uuid={}：未找到登记记录", uuid);
            return;
        }
        logger.info("玩家 {} 已从 limbo 注销（uuid={}），当前 limbo 人数={}",
                removed.gameId(), uuid, residents.size());
    }

    public Optional<LimboResident> findLimboPlayer(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return Optional.empty();
        }
        return residents.values().stream()
                .filter(handler -> handler.gameId().equalsIgnoreCase(playerName))
                .map(handler -> new LimboResident(handler.uuid(), handler.gameId()))
                .findFirst();
    }

    public List<LimboResident> limboPlayers() {
        return residents.values().stream()
                .map(handler -> new LimboResident(handler.uuid(), handler.gameId()))
                .toList();
    }

    /**
     * 管理员手动放行 limbo 中的玩家：先写会话状态（否则转服会被门禁拒绝），
     * 再让 session handler 停止提示并转到主服。
     *
     * @return 玩家当前是否仍在 limbo
     */
    public boolean approve(UUID uuid, String message) {
        LoginSessionHandler handler = residents.get(uuid);
        if (handler == null) {
            logger.debug("管理员放行 uuid={}：玩家不在 limbo，仅更新会话状态", uuid);
            return false;
        }
        plugin.sessionStore().forceAuthenticate(uuid, plugin.clock().instant());
        handler.approveByAdmin(message);
        logger.info("管理员放行 limbo 玩家 {}（uuid={}），当前 limbo 人数={}",
                handler.gameId(), uuid, residents.size());
        return true;
    }

    /** 管理员踢出：清理会话并断开 limbo 玩家。 */
    public boolean kick(UUID uuid, String message) {
        LoginSessionHandler handler = residents.remove(uuid);
        plugin.sessionStore().remove(uuid);
        if (handler == null) {
            logger.debug("管理员踢出 uuid={}：玩家不在 limbo，仅移除会话", uuid);
            return false;
        }
        handler.kickByAdmin(message);
        logger.info("管理员踢出 limbo 玩家 {}（uuid={}），当前 limbo 人数={}",
                handler.gameId(), uuid, residents.size());
        return true;
    }

    public void shutdown() {
        int residentCount = residents.size();
        residents.clear();
        if (limbo != null) {
            limbo.dispose();
            limbo = null;
        }
        logger.info("limbo 已关闭，清理了 {} 名仍在 limbo 的玩家。", residentCount);
    }

    /** 只读的 limbo 玩家视图。 */
    public record LimboResident(UUID uuid, String gameId) {
        public LimboResident {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(gameId, "gameId");
        }
    }

    private LimboFactory resolveFactory() {
        Object instance = server.getPluginManager().getPlugin("limboapi")
                .flatMap(PluginContainer::getInstance)
                .orElseThrow(() -> new IllegalStateException(
                        "LimboAPI 未加载；请把 limboapi-1.2.0.jar 安装到代理的 plugins 目录"));
        if (!(instance instanceof LimboFactory limboFactory)) {
            throw new IllegalStateException(
                    "limboapi 插件未暴露 LimboFactory（实际为 " + instance.getClass().getName() + "）");
        }
        return limboFactory;
    }
}