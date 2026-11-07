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
import me.hydwind.gate.config.LimboSettings;
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
 * <p>同时以 {@link LimboResidents} 窄接口对外暴露 limbo 中的玩家。管理员 approve / kick
 * 的会话写入顺序由 {@code AdminActionService} 独占，本类只负责 limbo 侧的平台动作，
 * 不再触碰 {@code LoginSessionStore}。</p>
 *
 * <p>LimboAPI 1.2.0 与 Velocity 版本强绑定；工厂从已安装的 {@code limboapi}
 * 插件实例取得，缺失时启动失败并给出明确原因。</p>
 */
public final class LimboLoginService implements LimboResidents {

    private final HydwindGatePlugin plugin;
    private final ProxyServer server;
    private final Logger logger;

    /** 当前仍在 limbo 的玩家；供管理命令按名字查找与状态统计。 */
    private final Map<UUID, LoginSessionHandler> residents = new ConcurrentHashMap<>();

    /**
     * {@link #initialize()} 时固定的 limbo 设置。
     *
     * <p>热重载只比较并告警，不替换这里的值，因此已创建的世界与玩家不受影响。
     * Java 的 blank final 只能由构造器赋值，而设置需要在 {@code initialize()} 读取，
     * 所以这里用 volatile 字段并在加载前先放默认值，保证读取方永不为 null。</p>
     */
    private volatile LimboSettings activeSettings =
            new LimboSettings(PluginConfig.DEFAULT_LIMBO_WORLD, PluginConfig.DEFAULT_LIMBO_SPAWN);

    private LimboFactory factory;
    private Limbo limbo;

    public LimboLoginService(HydwindGatePlugin plugin, ProxyServer server, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = Objects.requireNonNull(server, "server");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * 读取一次配置并创建虚拟世界与 limbo。应在代理初始化事件中调用一次。
     *
     * <p>生效的 {@link LimboSettings} 在这里固定下来；之后的 reload 不再读取本类。</p>
     */
    public void initialize() {
        this.factory = resolveFactory();
        // 一次性取窄视图，避免在后续运行期反复穿过 plugin 的宽访问器。
        LimboSettings settings = plugin.pluginConfig().limbo();
        this.activeSettings = settings;
        LimboSpawn spawn = LimboSpawn.parse(settings.spawn());

        VirtualWorld world = factory.createVirtualWorld(
                Dimension.OVERWORLD, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());

        if (limbo != null) {
            limbo.dispose();
        }
        limbo = factory.createLimbo(world)
                .setName(settings.world())
                .setGameMode(GameMode.ADVENTURE)
                .setShouldRejoin(true)
                .setShouldRespawn(false)
                // 仅用于客户端 Tab 补全；真正的解析发生在 onChat。
                .registerCommand(new LimboCommandMeta(List.of("login")));

        logger.info("Limbo '{}' created at ({}, {}, {}).",
                settings.world(), spawn.x(), spawn.y(), spawn.z());
    }

    /**
     * 本代际生效的 limbo 设置；供启动日志与状态展示使用。
     *
     * <p>值在 {@link #initialize()} 时固定，reload 不会替换，因此与当前
     * {@code config.yml} 可能不同；调用方需要区分“配置值”与“生效值”。</p>
     */
    public LimboSettings activeLimboSettings() {
        return activeSettings;
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
        limbo.spawnPlayer(player, new LoginSessionHandler(plugin, player, this));
    }

    /** limbo 玩家在 {@code onSpawn} 后登记，供管理命令与状态统计使用。 */
    public void register(LoginSessionHandler handler) {
        residents.put(handler.uuid(), handler);
    }

    /**
     * 注销 limbo 登记。
     *
     * <p>只移除指定 handler：玩家断开后立即重连时，新 handler 可能已经用同一 UUID
     * 登记；旧连接的迟到 {@code onDisconnect} 不能把新 handler 一并删掉，
     * 否则新会话会变成管理视图里看不见、也踢不掉的幽灵。</p>
     */
    public void unregister(UUID uuid, LoginSessionHandler handler) {
        residents.remove(uuid, handler);
    }

    /** 按游戏 ID（大小写不敏感）查找仍在 limbo 的玩家。 */
    @Override
    public Optional<Resident> find(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            return Optional.empty();
        }
        return residents.values().stream()
                .filter(handler -> handler.gameId().equalsIgnoreCase(gameId))
                .map(handler -> new Resident(handler.uuid(), handler.gameId()))
                .findFirst();
    }

    /** 当前 limbo 中的全部玩家快照。 */
    @Override
    public List<Resident> all() {
        return residents.values().stream()
                .map(handler -> new Resident(handler.uuid(), handler.gameId()))
                .toList();
    }

    /**
     * 让 60 秒 sweep 能真正踢掉已过期的 limbo 玩家：per-player 定时器仍是主要路径，
     * 但定时器丢失时由这里兜底，避免只标记状态却没人断开。
     *
     * @return 该玩家是否仍在 limbo 并被断开
     */
    public boolean timeOut(UUID uuid, String message) {
        LoginSessionHandler handler = residents.get(uuid);
        if (handler == null) {
            return false;
        }
        // 断开路径会触发 onDisconnect → unregister，这里只负责调用。
        return handler.timeOutNow(message);
    }

    /**
     * 管理员放行：让 session handler 停止提示并转到主服。
     *
     * <p>只做 limbo 侧动作；把会话写成 AUTHENTICATED 由调用方
     * （{@code AdminActionService}）先行完成，避免双写。</p>
     *
     * @return 玩家此刻是否仍在 limbo 并被处理
     */
    @Override
    public boolean approve(UUID uuid, String message) {
        LoginSessionHandler handler = residents.get(uuid);
        if (handler == null) {
            return false;
        }
        handler.approveByAdmin(message);
        return true;
    }

    /**
     * 管理员踢出：只断开 limbo 中的玩家；会话清理由调用方负责。
     *
     * @return 玩家此刻是否仍在 limbo 并被处理
     */
    @Override
    public boolean kick(UUID uuid, String message) {
        LoginSessionHandler handler = residents.remove(uuid);
        if (handler == null) {
            return false;
        }
        handler.kickByAdmin(message);
        return true;
    }

    /**
     * 关闭 limbo 并清空登记表。
     *
     * <p>先逐个关闭仍登记的会话（取消各自的提示与超时任务），再 dispose limbo，
     * 避免插件卸载后任务继续在 LimboAPI 的执行器上运行。</p>
     */
    public void shutdown() {
        List<LoginSessionHandler> handlers = List.copyOf(residents.values());
        residents.clear();
        for (LoginSessionHandler handler : handlers) {
            handler.shutdown();
        }
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
