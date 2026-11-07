package me.hydwind.gate.listener;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import me.hydwind.gate.auth.LoginManager;
import me.hydwind.gate.ui.DialogFactory;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * 处理登录对话框上的自定义按钮点击。
 *
 * <p>只接受本插件定义的 identifier，并通过 {@link PlayerGameConnection} 取回玩家；
 * 登录码从 {@link DialogResponseView#getText(String)} 读取。
 */
public final class DialogListener implements Listener {

    private final LoginManager loginManager;

    public DialogListener(LoginManager loginManager) {
        this.loginManager = loginManager;
    }

    @EventHandler
    public void onCustomClick(PlayerCustomClickEvent event) {
        Key identifier = event.getIdentifier();
        if (!DialogFactory.SUBMIT_ACTION.equals(identifier)) {
            return;
        }
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) {
            return;
        }
        Player player = connection.getPlayer();
        if (player == null) {
            return;
        }

        DialogResponseView response = event.getDialogResponseView();
        String code = response == null ? "" : response.getText(DialogFactory.CODE_INPUT_KEY);
        loginManager.submitCodeAsync(player.getUniqueId(), code);
    }
}