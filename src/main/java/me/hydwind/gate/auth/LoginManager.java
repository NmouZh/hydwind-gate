package me.hydwind.gate.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executor;
import me.hydwind.gate.ui.Messages;
import me.hydwind.gate.ui.PlayerGateway;
import me.hydwind.gate.web.WebsiteClient;

/**
 * 登录流程编排。此类型只依赖纯 Java 接口，Bukkit 副作用由 {@link PlayerGateway} 承担。
 */
public final class LoginManager {

    public LoginManager(WebsiteClient websiteClient,
                        LoginSessionStore sessionStore,
                        PlayerGateway playerGateway,
                        Messages messages,
                        Duration loginTimeout,
                        Clock clock,
                        Executor asyncExecutor) {
    }

    public LoginSession handleJoin(UUID uuid, String gameId, String ip) {
        return null;
    }

    public void handleQuit(UUID uuid) {
    }

    public void submitCode(UUID uuid, String rawCode) {
    }

    public void submitCodeAsync(UUID uuid, String rawCode) {
    }

    public void reopenDialog(UUID uuid) {
    }

    public void tick() {
    }
}