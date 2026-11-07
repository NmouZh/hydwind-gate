package me.hydwind.gate.admin;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import me.hydwind.gate.auth.LoginSession;
import me.hydwind.gate.auth.LoginSessionStore;
import me.hydwind.gate.login.LimboResidents;

/**
 * 管理员 approve / kick 的编排层。
 *
 * <p>所有跨组件的顺序不变量只在这里维护：放行必须先把会话写成 AUTHENTICATED，
 * 再由 limbo 侧转服；踢人必须先清理会话，再断开 limbo 或后端连接。
 * {@code AdminCommandExecutor} 只把 {@link AdminResult} 映射成文案。</p>
 *
 * <p>为了可单测，这里不直接依赖 Velocity 的 {@code ProxyServer}，而是通过
 * {@link BackendPlayers} 窄接口访问后端在线玩家。</p>
 */
public final class AdminActionService {

    private final LoginSessionStore sessions;
    private final LimboResidents residents;
    private final BackendPlayers backend;
    private final Clock clock;

    public AdminActionService(LoginSessionStore sessions,
                              LimboResidents residents,
                              BackendPlayers backend,
                              Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.residents = Objects.requireNonNull(residents, "residents");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 管理员放行。
     *
     * <p>顺序不变量：只要找到会话，就先 {@code forceAuthenticate}，再决定是否让
     * limbo 中的玩家转服；玩家已断线时仅更新会话，重连后可凭宽限恢复认证。</p>
     *
     * @param playerName 目标玩家游戏 ID
     * @param message    放行时发给玩家的提示（由 Adapter 从配置取得）
     */
    public AdminResult approve(String playerName, String message) {
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(message, "message");

        Optional<LoginSession> session = sessions.findByGameId(playerName);
        Optional<LimboResidents.Resident> resident = residents.find(playerName);

        if (session.isEmpty() && resident.isEmpty()) {
            return new AdminResult(AdminResult.Kind.NOT_FOUND, playerName);
        }
        if (session.filter(LoginSession::isAuthenticated).isPresent()) {
            return new AdminResult(AdminResult.Kind.ALREADY_AUTHENTICATED, session.get().gameId());
        }

        UUID uuid = session.map(LoginSession::uuid)
                .or(() -> resident.map(LimboResidents.Resident::uuid))
                .orElseThrow();
        String displayName = resident.map(LimboResidents.Resident::gameId)
                .or(() -> session.map(LoginSession::gameId))
                .orElse(playerName);

        // 先写会话状态：ServerPreConnectEvent 会拒绝未认证会话的转服。
        boolean wroteSession = sessions.forceAuthenticate(uuid, clock.instant());
        if (!wroteSession && sessions.find(uuid).map(LoginSession::isAuthenticated).orElse(false)) {
            return new AdminResult(AdminResult.Kind.ALREADY_AUTHENTICATED, displayName);
        }
        if (resident.isPresent()) {
            boolean stillInLimbo = residents.approve(resident.get().uuid(), message);
            return new AdminResult(stillInLimbo
                    ? AdminResult.Kind.APPROVED_IN_LIMBO
                    : AdminResult.Kind.APPROVED_OFFLINE,
                    displayName);
        }
        // 关键竞态：查找 resident 时 handler 可能还没 register（onSpawn 尚未执行），
        // 而 forceAuthenticate 已经写入。此时再查一次 resident；若它刚刚出现，
        // 立即放行，避免玩家因为“两次检查都发生在写入之前”而永久卡在 limbo。
        Optional<LimboResidents.Resident> lateResident = residents.find(playerName);
        if (lateResident.isPresent()
                && residents.approve(lateResident.get().uuid(), message)) {
            return new AdminResult(AdminResult.Kind.APPROVED_IN_LIMBO, displayName);
        }
        // 仅剩会话：玩家已断线，放行状态会随宽限记录保留。
        return new AdminResult(AdminResult.Kind.APPROVED_OFFLINE, displayName);
    }

    /**
     * 管理员踢出。
     *
     * <p>顺序不变量：先移除会话（避免残留会话把重连当成已认证），再断开 limbo
     * 玩家；limbo 里找不到人时断开后端在线玩家；两者都没有则仅清理会话。</p>
     *
     * @param playerName 目标玩家游戏 ID
     * @param message    断开玩家时的提示（由 Adapter 从配置取得）
     */
    public AdminResult kick(String playerName, String message) {
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(message, "message");

        Optional<LoginSession> session = sessions.findByGameId(playerName);
        Optional<LimboResidents.Resident> resident = residents.find(playerName);
        Optional<UUID> online = backend.findOnline(playerName);

        if (session.isEmpty() && resident.isEmpty() && online.isEmpty()) {
            return new AdminResult(AdminResult.Kind.NOT_FOUND, playerName);
        }

        UUID uuid = session.map(LoginSession::uuid)
                .or(() -> resident.map(LimboResidents.Resident::uuid))
                .or(() -> online)
                .orElseThrow();
        String displayName = session.map(LoginSession::gameId)
                .or(() -> resident.map(LimboResidents.Resident::gameId))
                .orElseGet(() -> online.flatMap(backend::nameOf).orElse(playerName));

        sessions.remove(uuid);

        if (resident.isPresent() && residents.kick(resident.get().uuid(), message)) {
            return new AdminResult(AdminResult.Kind.KICKED_IN_LIMBO, displayName);
        }
        // limbo 中已无人：断开后端连接；玩家不在线时 KICKED_BACKEND 表示“已清理干净”。
        online.ifPresent(player -> backend.disconnect(player, message));
        return new AdminResult(AdminResult.Kind.KICKED_BACKEND, displayName);
    }

    /**
     * 后端在线玩家的最小视图，由集成层适配 Velocity {@code ProxyServer}。
     * 放在编排层内部，避免把平台类型带进可单测的 Module。
     */
    public interface BackendPlayers {

        /** 按游戏 ID（大小写不敏感）查找代理服在线玩家。 */
        Optional<UUID> findOnline(String gameId);

        /** 断开指定玩家并在断开原因中显示 {@code message}。 */
        void disconnect(UUID uuid, String message);

        /** 在线玩家的当前显示名；玩家不在线时为空。 */
        Optional<String> nameOf(UUID uuid);
    }
}