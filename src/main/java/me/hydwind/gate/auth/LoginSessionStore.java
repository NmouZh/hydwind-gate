package me.hydwind.gate.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 内存中的登录会话状态机。所有时间都由调用方注入，不在内部读取系统时钟。
 *
 * <p>每个会话在进服时固定自己的 {@code loginDeadline}；之后的超时判断只看这个截止时刻，
 * 因此热重载改变 {@code auth.login-timeout-seconds} 不会影响已经进服的玩家。
 *
 * <p>宽限记录只保存在内存里，插件重启后自然清空，玩家需要重新登录。
 */
public final class LoginSessionStore {

    private final Map<UUID, LoginSession> sessions = new HashMap<>();
    /**
     * 每个 UUID 已分配过的最大校验编号与最后使用时刻。单独存放、跨会话不重置，
     * 这样旧连接迟到的校验结果不会命中重连后新建的会话。
     *
     * <p>条目在 {@link #expireGrace} 中按 {@link #VERIFICATION_ID_RETENTION} 回收，
     * 避免长期运行的代理随独立玩家数无限增长。</p>
     */
    private final Map<UUID, VerificationCounter> lastVerificationIds = new HashMap<>();
    /** 校验编号的保留时长：远大于任何登录会话/断线宽限，足够覆盖迟到结果。 */
    private static final Duration VERIFICATION_ID_RETENTION = Duration.ofHours(24);
    private volatile Duration reconnectGrace;

    public LoginSessionStore(Duration reconnectGrace) {
        this.reconnectGrace = Objects.requireNonNull(reconnectGrace, "reconnectGrace");
    }

    /**
     * 热重载 {@code auth.session-grace-seconds}。只更新后续宽限判断的截止时间，
     * 不移动、不删除已有会话；刷新会话由维护任务按新时长处理。
     */
    public synchronized void setReconnectGrace(Duration reconnectGrace) {
        this.reconnectGrace = Objects.requireNonNull(reconnectGrace, "reconnectGrace");
    }

    /**
     * 玩家进服。已认证且在宽限内的同 UUID + 同 IP 重连直接恢复认证；
     * 其余情况创建新的 {@link LoginSession.Status#PENDING} 会话。
     *
     * <p>新会话的 {@code loginDeadline} 固定为 {@code now + loginTimeout}；
     * 宽限恢复的会话和重复的在线进服事件都保留原 deadline，不因再次进服而延长登录窗口。
     */
    public synchronized LoginSession join(UUID uuid, String gameId, String ip, Instant now, Duration loginTimeout) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(ip, "ip");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(loginTimeout, "loginTimeout");

        LoginSession existing = sessions.get(uuid);
        if (existing != null && existing.isAuthenticated()) {
            if (existing.disconnectedAt() == null && existing.matches(gameId, ip)) {
                // 重复的进服事件：同一身份（游戏 ID + IP）才保持在线会话，仅刷新活动时间。
                // 不同 IP 的同名连接不能直接继承认证，必须重新登录。
                return update(existing.reconnectedAt(now));
            }
            if (withinGrace(existing, gameId, ip, now)) {
                // 宽限恢复：沿用原 loginDeadline 与 verificationId。
                return update(existing.reconnectedAt(now));
            }
        } else if (existing != null && existing.disconnectedAt() == null
                && awaitingAuthentication(existing) && existing.matches(gameId, ip)) {
            // 未认证会话的重复进服事件：保持状态与截止时刻，避免靠重复进服延长登录窗口。
            return update(existing.reconnectedAt(now));
        }

        return update(LoginSession.pending(uuid, gameId, ip, now, now.plus(loginTimeout)));
    }

    /**
     * 提交登录码，PENDING 进入 VERIFYING 并分配新的校验编号。
     *
     * @return 本次校验编号；非 PENDING 状态返回 {@link OptionalLong#empty()}
     */
    public synchronized OptionalLong beginVerification(UUID uuid, Instant now) {
        LoginSession session = sessions.get(uuid);
        if (session == null || session.status() != LoginSession.Status.PENDING) {
            return OptionalLong.empty();
        }
        // 编号按 UUID 全局单调递增，不随会话重建而回到 1。
        long verificationId = lastVerificationIds
                .compute(uuid, (key, counter) -> counter == null
                        ? new VerificationCounter(1L, now)
                        : new VerificationCounter(counter.lastId() + 1, now))
                .lastId();
        sessions.put(uuid, session.transitionTo(LoginSession.Status.VERIFYING, now, verificationId));
        return OptionalLong.of(verificationId);
    }

    /**
     * 网站校验成功，VERIFYING 进入 AUTHENTICATED。
     * 只有校验编号匹配的结果才生效；迟到的旧结果、非 VERIFYING 状态一律拒绝。
     */
    public synchronized boolean authenticationSucceeded(UUID uuid, long verificationId, Instant now) {
        LoginSession session = sessions.get(uuid);
        if (session == null || session.status() != LoginSession.Status.VERIFYING
                || session.verificationId() != verificationId) {
            return false;
        }
        if (!now.isBefore(session.loginDeadline())) {
            // 到达或超过截止时刻与 timeOut 的边界一致：迟到的成功结果不得放行，
            // 交由超时路径处理。
            sessions.put(uuid, session.transitionTo(LoginSession.Status.TIMED_OUT, now));
            return false;
        }
        sessions.put(uuid, session.transitionTo(LoginSession.Status.AUTHENTICATED, now));
        return true;
    }

    /**
     * 网站拒绝登录码，VERIFYING 退回 PENDING，允许玩家重新提交。
     * 只有校验编号匹配的结果才生效，迟到结果不会打断新的校验。
     */
    public synchronized boolean authenticationFailed(UUID uuid, long verificationId, Instant now) {
        LoginSession session = sessions.get(uuid);
        if (session == null || session.status() != LoginSession.Status.VERIFYING
                || session.verificationId() != verificationId) {
            return false;
        }
        sessions.put(uuid, session.transitionTo(LoginSession.Status.PENDING, now));
        return true;
    }

    /**
     * 到达会话自己的 {@code loginDeadline} 仍未认证则标记为 TIMED_OUT。
     *
     * <p>返回 {@code true} 表示调用方应断开该连接：既包括本次完成迁移，
     * 也包括会话此前已被 60 秒 sweep 标记为 TIMED_OUT 的情况。这样两个
     * 超时入口（per-player 定时器与 sweep）可以安全地竞争，不会互相抵消。</p>
     *
     * @return 调用方是否应断开该连接
     */
    public synchronized boolean timeOut(UUID uuid, Instant now) {
        Objects.requireNonNull(now, "now");
        LoginSession session = sessions.get(uuid);
        if (session == null) {
            return false;
        }
        if (session.status() == LoginSession.Status.TIMED_OUT) {
            return true;
        }
        if (!awaitingAuthentication(session) || now.isBefore(session.loginDeadline())) {
            return false;
        }
        sessions.put(uuid, session.transitionTo(LoginSession.Status.TIMED_OUT, now));
        return true;
    }

    /**
     * 玩家退出。已认证会话保留下来作为断线宽限记录；未认证会话直接移除。
     */
    public synchronized void quit(UUID uuid, Instant now) {
        LoginSession session = sessions.get(uuid);
        if (session == null) {
            return;
        }
        if (session.isAuthenticated()) {
            sessions.put(uuid, session.disconnectedAt(now));
        } else {
            sessions.remove(uuid);
        }
    }

    /**
     * 批量处理超时。返回所有“需要调用方断开”的会话：本次刚到期转入 TIMED_OUT 的，
     * 以及此前已被其它入口（例如迟到的成功结果）标记为 TIMED_OUT、但还没被清理的。
     *
     * <p>后者同样必须返回，否则 {@code authenticationSucceeded} 在截止后写入的
     * TIMED_OUT 会话将没有任何组件负责断开玩家，玩家会永久留在 limbo。</p>
     */
    public synchronized List<LoginSession> timeOutAll(Instant now) {
        Objects.requireNonNull(now, "now");
        List<LoginSession> timedOut = new ArrayList<>();
        for (LoginSession session : List.copyOf(sessions.values())) {
            if (session.status() == LoginSession.Status.TIMED_OUT) {
                // 已标记但尚未被断开/清理：继续交给调用方处理。
                timedOut.add(session);
                continue;
            }
            if (!awaitingAuthentication(session) || now.isBefore(session.loginDeadline())) {
                continue;
            }
            LoginSession updated = session.transitionTo(LoginSession.Status.TIMED_OUT, now);
            sessions.put(session.uuid(), updated);
            timedOut.add(updated);
        }
        return timedOut;
    }

    /**
     * 清除已超过断线宽限的认证记录。
     *
     * @return 被清除的会话数量
     */
    public synchronized int expireGrace(Instant now) {
        Objects.requireNonNull(now, "now");
        int removed = 0;
        for (LoginSession session : List.copyOf(sessions.values())) {
            Instant disconnectedAt = session.disconnectedAt();
            if (session.isAuthenticated()
                    && disconnectedAt != null
                    && !now.isBefore(disconnectedAt.plus(reconnectGrace))) {
                sessions.remove(session.uuid());
                removed++;
            }
        }
        // 校验编号只保留有限时长，避免长期运行的代理随独立玩家数无限增长。
        Instant idCutoff = now.minus(VERIFICATION_ID_RETENTION);
        lastVerificationIds.entrySet().removeIf(entry -> entry.getValue().updatedAt().isBefore(idCutoff));
        return removed;
    }

    public synchronized Optional<LoginSession> find(UUID uuid) {
        return Optional.ofNullable(sessions.get(uuid));
    }

    /**
     * 该玩家当前是否已认证、可连接后端。
     *
     * <p>这是门禁唯一的放行判据：{@code ServerPreConnectEvent}、limbo 放行分支与
     * 管理员动作都问同一个问题。没有会话或会话未认证一律返回 {@code false}
     * （fail-closed）。</p>
     */
    public synchronized boolean isAuthenticated(UUID uuid) {
        LoginSession session = sessions.get(uuid);
        return session != null && session.isAuthenticated();
    }

    /** 按游戏 ID（大小写不敏感）查找会话，供管理员命令使用。 */
    public synchronized Optional<LoginSession> findByGameId(String gameId) {
        Objects.requireNonNull(gameId, "gameId");
        return sessions.values().stream()
                .filter(session -> session.gameId().equalsIgnoreCase(gameId))
                .findFirst();
    }

    /** 管理员手动放行：任意存在且未认证的会话直接置为 AUTHENTICATED。 */
    public synchronized boolean forceAuthenticate(UUID uuid, Instant now) {
        LoginSession session = sessions.get(uuid);
        if (session == null || session.isAuthenticated()) {
            return false;
        }
        sessions.put(uuid, session.transitionTo(LoginSession.Status.AUTHENTICATED, now));
        return true;
    }

    /**
     * 管理员按游戏 ID 放行，返回被写入认证的会话。
     *
     * <p>查找与写入在同一把锁内，避免调用方先查到旧会话、再写另一个 UUID；
     * limbo 中同名但不同 UUID 的新连接由调用方随后按 UUID 单独处理。</p>
     */
    public synchronized Optional<LoginSession> forceAuthenticateByGameId(String gameId, Instant now) {
        Objects.requireNonNull(gameId, "gameId");
        LoginSession session = sessions.values().stream()
                .filter(candidate -> candidate.gameId().equalsIgnoreCase(gameId))
                .findFirst()
                .orElse(null);
        if (session == null || session.isAuthenticated()) {
            return Optional.empty();
        }
        LoginSession updated = session.transitionTo(LoginSession.Status.AUTHENTICATED, now);
        sessions.put(session.uuid(), updated);
        return Optional.of(updated);
    }

    /**
     * 管理员踢出：移除会话（含宽限记录）。
     *
     * <p>刻意保留 {@code lastVerificationIds}：踢出后玩家重连会建立新会话，
     * 校验编号必须继续递增，否则旧连接的迟到结果会命中新会话。</p>
     */
    public synchronized boolean remove(UUID uuid) {
        return sessions.remove(uuid) != null;
    }

    /**
     * 只在会话确实处于 TIMED_OUT 时移除它。60 秒 sweep 可能持有过期快照，
     * 这期间玩家可能已重连并建立了新会话；用状态比较避免删掉新会话。
     */
    public synchronized boolean removeIfTimedOut(UUID uuid) {
        LoginSession session = sessions.get(uuid);
        if (session == null || session.status() != LoginSession.Status.TIMED_OUT) {
            return false;
        }
        return sessions.remove(uuid) != null;
    }

    /** 会话快照，供状态统计使用。 */
    public synchronized List<LoginSession> snapshot() {
        return List.copyOf(sessions.values());
    }

    private LoginSession update(LoginSession session) {
        sessions.put(session.uuid(), session);
        return session;
    }

    private static boolean awaitingAuthentication(LoginSession session) {
        return session.status() == LoginSession.Status.PENDING
                || session.status() == LoginSession.Status.VERIFYING;
    }

    private boolean withinGrace(LoginSession session, String gameId, String ip, Instant now) {
        Instant disconnectedAt = session.disconnectedAt();
        return disconnectedAt != null
                && session.matches(gameId, ip)
                && !now.isBefore(disconnectedAt)
                && now.isBefore(disconnectedAt.plus(reconnectGrace));
    }

    /** 每个 UUID 的校验编号计数器；带时间戳以便回收。 */
    private record VerificationCounter(long lastId, Instant updatedAt) {
    }
}
