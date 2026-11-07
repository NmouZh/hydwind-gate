package me.hydwind.gate.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.Plugin;

/**
 * 登录前的玩家冻结。冻结时保存原状态，解冻时完整恢复。
 *
 * <p>玩家被传送到配置的出生点、切换冒险模式、隐身且无敌；移动会被拉回（保留视角），
 * 聊天、未授权命令、交互、伤害、拾取与丢弃都会被取消。
 */
public final class FreezeController implements Listener {

    /** 冻结期间允许使用的命令，避免玩家连 /login 都被拦下。 */
    private static final Set<String> ALLOWED_COMMANDS = Set.of("login", "hydwind");

    private final Plugin plugin;
    private final Supplier<Location> spawnSupplier;
    private final Map<UUID, FrozenState> frozen = new ConcurrentHashMap<>();

    public FreezeController(Plugin plugin, Supplier<Location> spawnSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.spawnSupplier = Objects.requireNonNull(spawnSupplier, "spawnSupplier");
    }

    public boolean isFrozen(UUID uuid) {
        return frozen.containsKey(uuid);
    }

    public void freeze(Player player) {
        Objects.requireNonNull(player, "player");
        FrozenState previous = frozen.putIfAbsent(player.getUniqueId(), snapshot(player));
        if (previous != null) {
            return;
        }
        Location spawn = spawnSupplier.get();
        if (spawn != null && spawn.getWorld() != null) {
            player.teleport(spawn);
        }
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true);
        player.setFlying(false);
        player.setWalkSpeed(0.0f);
        player.setFlySpeed(0.0f);
        player.setInvisible(true);
        player.setInvulnerable(true);
        player.setCollidable(false);
    }

    public void unfreeze(UUID uuid) {
        FrozenState state = frozen.remove(uuid);
        if (state == null) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        player.setGameMode(state.gameMode());
        player.setAllowFlight(state.allowFlight());
        player.setFlying(state.allowFlight() && state.flying());
        player.setWalkSpeed(state.walkSpeed());
        player.setFlySpeed(state.flySpeed());
        player.setInvisible(state.invisible());
        player.setInvulnerable(state.invulnerable());
        player.setCollidable(state.collidable());
        Location original = state.location();
        if (original != null && original.getWorld() != null) {
            try {
                player.teleport(original);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("无法把 " + player.getName() + " 传送回原位置: "
                        + exception.getMessage());
            }
        }
    }

    /** 玩家退出时丢弃冻结状态，避免 UUID 映射长期残留。 */
    public void discard(UUID uuid) {
        frozen.remove(uuid);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isFrozen(event.getPlayer().getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return; // 只改变视角，允许。
        }
        Location corrected = from.clone();
        corrected.setYaw(to.getYaw());
        corrected.setPitch(to.getPitch());
        event.setTo(corrected);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!isFrozen(event.getPlayer().getUniqueId())) {
            return;
        }
        if (!ALLOWED_COMMANDS.contains(commandLabel(event.getMessage()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDropItem(PlayerDropItemEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (isFrozen(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (isFrozen(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamageByFrozen(EntityDamageByEntityEvent event) {
        if (isFrozen(event.getDamager().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private static String commandLabel(String message) {
        String command = message.startsWith("/") ? message.substring(1) : message;
        int space = command.indexOf(' ');
        if (space >= 0) {
            command = command.substring(0, space);
        }
        int namespace = command.indexOf(':');
        if (namespace >= 0) {
            command = command.substring(namespace + 1);
        }
        return command.toLowerCase(Locale.ROOT);
    }

    private static FrozenState snapshot(Player player) {
        return new FrozenState(
                player.getGameMode(),
                player.getLocation().clone(),
                player.getAllowFlight(),
                player.isFlying(),
                player.getWalkSpeed(),
                player.getFlySpeed(),
                player.isInvisible(),
                player.isInvulnerable(),
                player.isCollidable()
        );
    }

    private record FrozenState(
            GameMode gameMode,
            Location location,
            boolean allowFlight,
            boolean flying,
            float walkSpeed,
            float flySpeed,
            boolean invisible,
            boolean invulnerable,
            boolean collidable
    ) {
    }
}