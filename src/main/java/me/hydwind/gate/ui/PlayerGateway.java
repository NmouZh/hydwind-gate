package me.hydwind.gate.ui;

import java.util.UUID;

/**
 * 登录流程需要的 Bukkit 副作用。{@link me.hydwind.gate.auth.LoginManager} 只依赖此接口，
 * 因而状态机可以脱离 Bukkit 在单元测试中运行。
 *
 * <p>实现必须保证所有玩家操作都落到主线程，调用方可能位于异步线程。
 */
public interface PlayerGateway {

    /** 向玩家发送一条纯文本提示。 */
    void sendMessage(UUID playerId, String message);

    /** 将玩家踢出服务器并显示原因。 */
    void kick(UUID playerId, String reason);

    /** 冻结玩家：传送到出生点、冒险模式、隐身、无敌，并限制各类操作。 */
    void freeze(UUID playerId);

    /** 解除冻结并恢复冻结前的状态。 */
    void unfreeze(UUID playerId);

    /** 弹出登录对话框。 */
    void showLoginDialog(UUID playerId);
}