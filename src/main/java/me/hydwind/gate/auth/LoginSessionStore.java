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
     * 每个 UUID 当前有效的连接代际。旧连接的 DisconnectEvent 可能晚于新连接的
     * join 到达；quit 必须带上连接代际，只有仍然匹配时才允许删除/标记会话。
     */
    private final Map<UUID, Long> connectionIds = new HashMap<>();
    private long nextConnectionId = 1L;
    /**
     * 全局单调递增的校验编号。不按 UUID 保留计数器：全局编号天然不会回退，
     * 旧连接迟到的结果永远命中不了重连后的新会话，也不会因为随机用户名把
     * 内存撑大或触发“计数器满→全员无法登录”的可用性故障。
     */
    private long nextVerificationId = 1L;
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
        return joinWithConnection(uuid, gameId, ip, now, loginTimeout).session();
    }

    /**
     * 进服并返回本次连接的代际编号。
     *
     * <p>每次进服事件都分配新的 {@code connectionId}；断线事件必须带回同一个编号，
     * 否则会被当成旧连接的迟到事件忽略。这样玩家快速重连时，旧连接的
     * {@code DisconnectEvent} 不会删掉新会话。</p>
     */
    public synchronized JoinResult joinWithConnection(UUID uuid, String gameId, String ip,
                                                       Instant now, Duration loginTimeout) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(ip, "ip");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(loginTimeout, "loginTimeout");

        LoginSession existing = sessions.get(uuid);
        // 同一 UUID 仍有在线连接时，任何新连接都不能顶掉它：LimboAPI 在把
        // 连接注册进 Velocity 之前就触发本事件，同名攻击者可以抢在受害者
        // 前面 join，从而改写会话的 IP/连接代际。等旧连接真正断线
        // （disconnectedAt != null）后，才允许宽限重连或重新登录。
        if (existing != null && existing.disconnectedAt() == null
                && existing.status() != LoginSession.Status.TIMED_OUT) {
            return new JoinResult(existing, 0L, true);
        }

        long connectionId = nextConnectionId++;
        connectionIds.put(uuid, connectionId);

        if (existing != null && existing.isAuthenticated()) {
            if (existing.disconnectedAt() == null && existing.matches(gameId, ip)) {
                // 重复的进服事件：同一身份（游戏 ID + IP）才保持在线会话，仅刷新活动时间。
                // 不同 IP 的同名连接不能直接继承认证，必须重新登录。
                return new JoinResult(update(existing.reconnectedAt(now)), connectionId);
            }
            if (withinGrace(existing, gameId, ip, now)) {
                // 宽限恢复：沿用原 loginDeadline 与 verificationId。
                return new JoinResult(update(existing.reconnectedAt(now)), connectionId);
            }
        } else if (existing != null && existing.disconnectedAt() == null
                && awaitingAuthentication(existing) && existing.matches(gameId, ip)) {
            // 未认证会话的重复进服事件：保持状态与截止时刻，避免靠重复进服延长登录窗口。
            return new JoinResult(update(existing.reconnectedAt(now)), connectionId);
        }

        return new JoinResult(update(LoginSession.pending(uuid, gameId, ip, now, now.plus(loginTimeout))),
                connectionId);
    }

    /** 一次进服事件对应的会话与连接代际；rejected 表示另一个在线连接占用了该 UUID。 */
    public record JoinResult(LoginSession session, long connectionId, boolean rejected) {
        public JoinResult(LoginSession session, long connectionId) {
            this(session, connectionId, false);
        }
    }

    /**
     * 提交登录码，PENDING 进入 VERIFYING 并分配新的校验编号。
     *
     * @return 本次校验编号；非 PENDING 状态返回 {@link OptionalLong#empty()}
     */
    public synchronized OptionalLong beginVerification(UUID uuid, Instant now) {
        Long connectionId = connectionIds.get(uuid);
        if (connectionId == null) return OptionalLong.empty();
        return beginVerification(uuid, connectionId, now);
    }

    /**
     * 只允许当前连接推进自己的会话。旧连接（或同名顶替连接）不能借受害者
     * 的会话状态提交/验证登录码。
     */
    public synchronized OptionalLong beginVerification(UUID uuid, long connectionId, Instant now) {
        LoginSession session = sessions.get(uuid);
        if (session == null || session.status() != LoginSession.Status.PENDING) {
            return OptionalLong.empty();
        }
        Long current = connectionIds.get(uuid);
        if (current == null || current.longValue() != connectionId) {
            return OptionalLong.empty();
        }
        // 全局单调递增：不随会话重建或 UUID 变化回退。
        long verificationId = nextVerificationId++;
        sessions.put(uuid, session.transitionTo(LoginSession.Status.VERIFYING, now, verificationId));
        return OptionalLong.of(verificationId);
    }

    /**
     * 网站校验成功，VERIFYING 进入 AUTHENTICATED。
     * 只有校验编号匹配的结果才生效；迟到的旧结果、非 VERIFYING 状态一律拒绝。
     */
    public synchronized boolean authenticationSucceeded(UUID uuid, long verificationId, Instant now) {
        Long connectionId = connectionIds.get(uuid);
        if (connectionId == null) return false;
        return authenticationSucceeded(uuid, connectionId, verificationId, now);
    }

    /** 成功结果必须属于当前连接；旧连接的迟到成功结果一律丢弃。 */
    public synchronized boolean authenticationSucceeded(UUID uuid, long connectionId,
                                                        long verificationId, Instant now) {
        Long current = connectionIds.get(uuid);
        if (current == null || current.longValue() != connectionId) {
            return false;
        }
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
        Long connectionId = connectionIds.get(uuid);
        if (connectionId == null) return false;
        return authenticationFailed(uuid, connectionId, verificationId, now);
    }

    /** 失败结果同样只归当前连接，迟到结果不能改写新连接的状态。 */
    public synchronized boolean authenticationFailed(UUID uuid, long connectionId,
                                                     long verificationId, Instant now) {
        Long current = connectionIds.get(uuid);
        if (current == null || current.longValue() != connectionId) {
            return false;
        }
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
        Long connectionId = connectionIds.get(uuid);
        if (connectionId == null) return false;
        return timeOut(uuid, connectionId, now);
    }

    /** 只有当前连接可以触发/消费自己会话的超时。 */
    public synchronized boolean timeOut(UUID uuid, long connectionId, Instant now) {
        Objects.requireNonNull(now, "now");
        Long current = connectionIds.get(uuid);
        if (current == null || current.longValue() != connectionId) {
            return false;
        }
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
        quitInternal(uuid, now);
        connectionIds.remove(uuid);
    }

    /**
     * 带连接代际的退出。只有当前会话仍属于这条连接时才生效。
     *
     * <p>玩家在 limbo 掉线后立即重连时，新连接的 join 可能先到，旧连接迟到的
     * DisconnectEvent 不能删除新会话。</p>
     */
    public synchronized void quit(UUID uuid, long connectionId, Instant now) {
        Long current = connectionIds.get(uuid);
        if (current == null || current.longValue() != connectionId) {
            return;
        }
        connectionIds.remove(uuid);
        quitInternal(uuid, now);
    }

    private void quitInternal(UUID uuid, Instant now) {
        LoginSession session = sessions.get(uuid);
        if (session == null) {
            return;
        }
        if (session.isAuthenticated()) {
            sessions.put(uuid, session.disconnectedAt(now));
        } else {
            // 未认证会话在断线时直接移除：玩家重连必须重新开始登录。
            // 但已经因超时被标记的会话保留给 sweep 处理，否则 sweep 扫不到，
            // 也就不会执行断开（虽然玩家此时通常已经断开）。
            if (session.status() == LoginSession.Status.TIMED_OUT) {
                return;
            }
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
                connectionIds.remove(session.uuid());
                removed++;
            }
        }
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

    /** 只认当前连接的认证状态；ServerPreConnectEvent 和 limbo 放行使用这个版本。 */
    public synchronized boolean isAuthenticated(UUID uuid, long connectionId) {
        Long current = connectionIds.get(uuid);
        if (current == null || current.longValue() != connectionId) return false;
        LoginSession session = sessions.get(uuid);
        return session != null && session.isAuthenticated();
    }

    /** 指定连接是否仍是该 UUID 的当前连接。 */
    public synchronized boolean isCurrentConnection(UUID uuid, long connectionId) {
        Long current = connectionIds.get(uuid);
        return current != null && current.longValue() == connectionId;
    }

    /**
     * 丢弃一个已失效的连接代际，但不改动会话本身。
     * 用于 CONFLICTING_LOGIN 这类“连接已死、会话可能仍属于别人”的场景。
     */
    public synchronized void dropConnection(UUID uuid, long connectionId) {
        Long current = connectionIds.get(uuid);
        if (current != null && current.longValue() == connectionId) {
            connectionIds.remove(uuid);
        }
    }

    /**
     * 按游戏 ID 精确查找会话，供管理员命令使用。
     *
     * <p>用精确比较而不是 {@code equalsIgnoreCase}：离线模式的 UUID 由用户名精确派生，
     * 大小写变体是两个不同账号。{@code equalsIgnoreCase} 会把 Unicode 简单大小写
     * （例如 {@code ſ} 与 {@code s}）判为相同，让管理员放行到错误的玩家。
     * 需要便利性时由命令行入口做一次 trim，不在这里放宽。</p>
     */
    public synchronized Optional<LoginSession> findByGameId(String gameId) {
        Objects.requireNonNull(gameId, "gameId");
        return sessions.values().stream()
                .filter(session -> session.gameId().equals(gameId))
                .findFirst();
    }

    /** 管理员手动放行：只有仍可认证（PENDING / VERIFYING）的会话能写入 AUTHENTICATED。 */
    public synchronized boolean forceAuthenticate(UUID uuid, Instant now) {
        LoginSession session = sessions.get(uuid);
        if (session == null || !awaitingAuthentication(session)) {
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
                .filter(candidate -> candidate.gameId().equals(gameId))
                .findFirst()
                .orElse(null);
        // TIMED_OUT 不能复活：sweep 已经决定要断开这个玩家，放行只会制造
        // “会话已认证但 handler 正在踢人”的分叉状态。
        if (session == null || !awaitingAuthentication(session)) {
            return Optional.empty();
        }
        LoginSession updated = session.transitionTo(LoginSession.Status.AUTHENTICATED, now);
        sessions.put(session.uuid(), updated);
        return Optional.of(updated);
    }

    /**
     * 管理员踢出：移除会话（含宽限记录）。
     *
     * <p>校验编号是全局单调递增的，踢出后玩家重连建立的新会话也不会复用旧编号，
     * 旧连接的迟到结果无法命中新会话。</p>
     */
    public synchronized boolean remove(UUID uuid) {
        connectionIds.remove(uuid);
        return sessions.remove(uuid) != null;
    }

    /**
     * 只在会话仍是调用方刚才看到的那个实例时移除它。
     *
     * <p>管理员 kick 先查会话、再执行删除；这期间玩家可能断线重连并建立了
     * 新会话。用 record 的精确相等比较，避免删掉崭新会话。</p>
     *
     * @return 是否真的移除了期望的那个会话
     */
    public synchronized boolean removeIfSession(UUID uuid, LoginSession expected) {
        Objects.requireNonNull(expected, "expected");
        boolean removed = sessions.remove(uuid, expected);
        if (removed) connectionIds.remove(uuid);
        return removed;
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
        boolean removed = sessions.remove(uuid) != null;
        if (removed) connectionIds.remove(uuid);
        return removed;
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

}
