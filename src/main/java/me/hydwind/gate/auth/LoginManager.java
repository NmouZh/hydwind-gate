package me.hydwind.gate.auth;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import me.hydwind.gate.ui.Messages;
import me.hydwind.gate.ui.PlayerGateway;
import me.hydwind.gate.web.VerifyResult;
import me.hydwind.gate.web.WebsiteClient;

/**
 * 登录流程编排。此类型只依赖纯 Java 接口，Bukkit 副作用由 {@link PlayerGateway} 承担。
 *
 * <p>会话状态流转由 {@link LoginSessionStore} 保证：只有处于 VERIFYING 的会话才能被
 * 标记为成功，因此超时踢出后迟到的校验结果不会解冻玩家。
 */
public final class LoginManager {

    private final WebsiteClient websiteClient;
    private final LoginSessionStore sessionStore;
    private final PlayerGateway playerGateway;
    private final Messages messages;
    private final Duration loginTimeout;
    private final Clock clock;
    private final Executor asyncExecutor;
    private final Consumer<UUID> unreachableCallback;

    public LoginManager(WebsiteClient websiteClient,
                        LoginSessionStore sessionStore,
                        PlayerGateway playerGateway,
                        Messages messages,
                        Duration loginTimeout,
                        Clock clock,
                        Executor asyncExecutor) {
        this(websiteClient, sessionStore, playerGateway, messages, loginTimeout, clock, asyncExecutor,
                uuid -> { });
    }

    public LoginManager(WebsiteClient websiteClient,
                        LoginSessionStore sessionStore,
                        PlayerGateway playerGateway,
                        Messages messages,
                        Duration loginTimeout,
                        Clock clock,
                        Executor asyncExecutor,
                        Consumer<UUID> unreachableCallback) {
        this.websiteClient = Objects.requireNonNull(websiteClient, "websiteClient");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.playerGateway = Objects.requireNonNull(playerGateway, "playerGateway");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.loginTimeout = Objects.requireNonNull(loginTimeout, "loginTimeout");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.asyncExecutor = Objects.requireNonNull(asyncExecutor, "asyncExecutor");
        this.unreachableCallback = Objects.requireNonNull(unreachableCallback, "unreachableCallback");
    }

    /**
     * 玩家进服。宽限期内命中的认证会话直接放行，否则创建 PENDING 会话并冻结、弹窗。
     */
    public LoginSession handleJoin(UUID uuid, String gameId, String ip) {
        LoginSession session = sessionStore.join(uuid, gameId, ip, clock.instant());
        if (!session.isAuthenticated()) {
            playerGateway.freeze(uuid);
            playerGateway.showLoginDialog(uuid);
        }
        return session;
    }

    /** 玩家退出。已认证会话转为宽限记录，其余会话立即清除。 */
    public void handleQuit(UUID uuid) {
        sessionStore.quit(uuid, clock.instant());
    }

    /**
     * 同步提交登录码。运行时通过 {@link #submitCodeAsync(UUID, String)} 在异步线程调用，
     * 单元测试可直接调用以便断言。
     */
    public void submitCode(UUID uuid, String rawCode) {
        LoginSession session = sessionStore.find(uuid).orElse(null);
        if (session == null) {
            return;
        }
        switch (session.status()) {
            case AUTHENTICATED -> {
                playerGateway.sendMessage(uuid, messages.alreadyAuthenticated());
                return;
            }
            case VERIFYING -> {
                playerGateway.sendMessage(uuid, messages.verifying());
                return;
            }
            case TIMED_OUT -> {
                return;
            }
            case PENDING -> {
                // 继续校验。
            }
        }

        String code = normalize(rawCode);
        if (code.isEmpty()) {
            playerGateway.sendMessage(uuid, messages.emptyCode());
            return;
        }
        if (!sessionStore.beginVerification(uuid, clock.instant())) {
            return;
        }

        VerifyResult result;
        try {
            result = websiteClient.verify(code, session.gameId(), uuid);
        } catch (RuntimeException exception) {
            result = VerifyResult.unreachable();
        }
        applyResult(uuid, session, result);
    }

    /** 在注入的异步执行器上提交登录码。 */
    public void submitCodeAsync(UUID uuid, String rawCode) {
        asyncExecutor.execute(() -> submitCode(uuid, rawCode));
    }

    /** {@code /login} 无参数：重新弹出登录对话框。 */
    public void reopenDialog(UUID uuid) {
        LoginSession session = sessionStore.find(uuid).orElse(null);
        if (session == null) {
            return;
        }
        switch (session.status()) {
            case PENDING -> playerGateway.showLoginDialog(uuid);
            case AUTHENTICATED -> playerGateway.sendMessage(uuid, messages.alreadyAuthenticated());
            case VERIFYING -> playerGateway.sendMessage(uuid, messages.verifying());
            case TIMED_OUT -> {
                // 已超时，等待踢出，不再重开对话框。
            }
        }
    }

    /** 每秒调用一次：清理过期宽限记录并踢出登录超时的玩家。 */
    public void tick() {
        Instant now = clock.instant();
        sessionStore.expireGrace(now);
        for (LoginSession session : sessionStore.timeOutAll(now, loginTimeout)) {
            playerGateway.kick(session.uuid(), messages.sessionTimeout());
        }
    }

    private void applyResult(UUID uuid, LoginSession session, VerifyResult result) {
        switch (result.status()) {
            case OK -> {
                if (!sessionStore.authenticationSucceeded(uuid, clock.instant())) {
                    return;
                }
                playerGateway.sendMessage(uuid, messages.loginSuccess()
                        .replace("{name}", displayName(session, result)));
                playerGateway.unfreeze(uuid);
            }
            case INVALID_CODE -> reject(uuid, messages.invalidCode());
            case EXPIRED -> reject(uuid, messages.expiredCode());
            case USED -> reject(uuid, messages.usedCode());
            case GAME_ID_MISMATCH -> reject(uuid, messages.gameIdMismatch());
            case UNREACHABLE -> {
                notifyUnreachable(uuid);
                reject(uuid, messages.serviceUnavailable());
            }
            case UNAUTHORIZED -> reject(uuid, messages.serviceMisconfigured());
        }
    }

    /**
     * 通知外部「网站不可达」。回调异常必须吞掉，登录流程仍按 fail-closed 处理。
     */
    private void notifyUnreachable(UUID uuid) {
        try {
            unreachableCallback.accept(uuid);
        } catch (RuntimeException ignored) {
            // 重启触发、日志写入等旁路失败不影响玩家登录。
        }
    }

    /** 失败统一回到 PENDING，允许玩家重新提交；若已超时则不打扰玩家。 */
    private void reject(UUID uuid, String message) {
        if (!sessionStore.authenticationFailed(uuid, clock.instant())) {
            return;
        }
        playerGateway.sendMessage(uuid, message);
        playerGateway.showLoginDialog(uuid);
    }

    private static String displayName(LoginSession session, VerifyResult result) {
        String nickname = result.nickname();
        if (nickname != null && !nickname.isBlank()) {
            return nickname;
        }
        String username = result.username();
        if (username != null && !username.isBlank()) {
            return username;
        }
        return session.gameId();
    }

    /** 统一使用 NFC 并去除首尾空白，避免颜文字因输入法差异无法匹配。 */
    private static String normalize(String rawCode) {
        if (rawCode == null) {
            return "";
        }
        return Normalizer.normalize(rawCode, Normalizer.Form.NFC).trim();
    }
}