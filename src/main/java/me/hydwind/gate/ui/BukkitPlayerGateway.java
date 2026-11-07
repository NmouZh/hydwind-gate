package me.hydwind.gate.ui;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import me.hydwind.gate.listener.FreezeController;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * {@link PlayerGateway} 的 Bukkit 实现。所有玩家操作都会切回主线程，
 * 因为 {@link me.hydwind.gate.auth.LoginManager} 可能在异步线程上完成 HTTP 校验。
 */
public final class BukkitPlayerGateway implements PlayerGateway {

    private final Plugin plugin;
    private final FreezeController freezeController;
    private final DialogFactory dialogFactory;

    public BukkitPlayerGateway(Plugin plugin, FreezeController freezeController, DialogFactory dialogFactory) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.freezeController = Objects.requireNonNull(freezeController, "freezeController");
        this.dialogFactory = Objects.requireNonNull(dialogFactory, "dialogFactory");
    }

    @Override
    public void sendMessage(UUID playerId, String message) {
        onMain(playerId, player -> player.sendMessage(Component.text(message)));
    }

    @Override
    public void kick(UUID playerId, String reason) {
        onMain(playerId, player -> player.kick(Component.text(reason)));
    }

    @Override
    public void freeze(UUID playerId) {
        onMain(playerId, freezeController::freeze);
    }

    @Override
    public void unfreeze(UUID playerId) {
        if (Bukkit.isPrimaryThread()) {
            freezeController.unfreeze(playerId);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> freezeController.unfreeze(playerId));
        }
    }

    @Override
    public void showLoginDialog(UUID playerId) {
        onMain(playerId, player -> player.showDialog(dialogFactory.create()));
    }

    private void onMain(UUID playerId, Consumer<Player> action) {
        if (!plugin.isEnabled()) {
            return;
        }
        Runnable task = () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                action.accept(player);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}