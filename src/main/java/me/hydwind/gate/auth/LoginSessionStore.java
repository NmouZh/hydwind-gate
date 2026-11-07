package me.hydwind.gate.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 内存中的登录会话状态机。所有时间都由调用方注入，不在内部读取系统时钟。
 *
 * <p>宽限记录只保存在内存里，插件重启后自然清空，玩家需要重新登录。
 */
public final class LoginSessionStore {

    /** 已认证玩家断线后，同 UUID + 同 IP 免登录的默认时长。 */
    static final Duration RECONNECT_GRACE = Duration.ofMinutes(5);

    private final Map<UUID, LoginSession> sessions = new HashMap<>();
    private final Duration reconnectGrace;

    public LoginSessionStore() {
        this(RECONNECT_GRACE);
    }

    public LoginSessionStore(Duration reconnectGrace) {
        this.reconnectGrace = Objects.requireNonNull(reconnectGrace, "reconnectGrace");
    }

    /**
     * 玩家进服。已认证且在宽限内的同 UUID + 同 IP 重连直接恢复认证；
     * 其余情况一律创建新的 {@link LoginSession.Status#PENDING} 会话。
     */
    public synchronized LoginSession join(UUID uuid, String gameId, String ip, Instant now) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(ip, "ip");
        Objects.requireNonNull(now, "now");

        LoginSession existing = sessions.get(uuid);
        if (existing != null && existing.isAuthenticated()) {
            if (existing.disconnectedAt() == null) {
                // 重复的进服事件：保持在线会话，仅刷新活动时间。
                return update(existing.reconnectedAt(now));
            }
            if (withinGrace(existing, gameId, ip, now)) {
                return update(existing.reconnectedAt(now));
            }
        }

        return update(LoginSession.pending(uuid, gameId, ip, now));
    }

    /** 提交登录码，PENDING 进入 VERIFYING。其他状态返回 {@code false}，不做任何改变。 */
    public synchronized boolean beginVerification(UUID uuid, Instant now) {
        LoginSession session = sessions.get(uuid);
        if (session == null || session.status() != LoginSession.Status.PENDING) {
            return false;
        }
        sessions.put(uuid, session.transitionTo(LoginSession.Status.VERIFYING, now));
        return true;
    }

    /** 网站校验成功，VERIFYING 进入 AUTHENTICATED。非 VERIFYING（含迟到的结果）返回 {@code false}。 */
    public synchronized boolean authenticationSucceeded(UUID uuid, Instant now) {
        LoginSession session = sessions.get(uuid);
        if (session == null || session.status() != LoginSession.Status.VERIFYING) {
            return false;
        }
        sessions.put(uuid, session.transitionTo(LoginSession.Status.AUTHENTICATED, now));
        return true;
    }

    /** 网站拒绝登录码，VERIFYING 退回 PENDING，允许玩家重新提交。 */
    public synchronized boolean authenticationFailed(UUID uuid, Instant now) {
        LoginSession session = sessions.get(uuid);
        if (session == null || session.status() != LoginSession.Status.VERIFYING) {
            return false;
        }
        sessions.put(uuid, session.transitionTo(LoginSession.Status.PENDING, now));
        return true;
    }

    /**
     * 从进服时刻起超过 {@code limit} 仍未认证则标记为 TIMED_OUT。
     *
     * @return 本次调用是否完成了超时迁移
     */
    public synchronized boolean timeOut(UUID uuid, Instant now, Duration limit) {
        LoginSession session = sessions.get(uuid);
        if (session == null) {
            return false;
        }
        if (session.status() != LoginSession.Status.PENDING
                && session.status() != LoginSession.Status.VERIFYING) {
            return false;
        }
        if (Duration.between(session.enteredAt(), now).compareTo(limit) < 0) {
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
     * 批量处理超时：PENDING / VERIFYING 从进服起超过 {@code limit} 的会话标记为 TIMED_OUT。
     *
     * @return 本次被标记为超时的会话
     */
    public synchronized List<LoginSession> timeOutAll(Instant now, Duration limit) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(limit, "limit");
        List<LoginSession> timedOut = new ArrayList<>();
        for (LoginSession session : List.copyOf(sessions.values())) {
            if (session.status() != LoginSession.Status.PENDING
                    && session.status() != LoginSession.Status.VERIFYING) {
                continue;
            }
            if (Duration.between(session.enteredAt(), now).compareTo(limit) < 0) {
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
        return removed;
    }

    public synchronized Optional<LoginSession> find(UUID uuid) {
        return Optional.ofNullable(sessions.get(uuid));
    }

    private LoginSession update(LoginSession session) {
        sessions.put(session.uuid(), session);
        return session;
    }

    private static boolean withinGrace(LoginSession session, String gameId, String ip, Instant now) {
        Instant disconnectedAt = session.disconnectedAt();
        return disconnectedAt != null
                && session.matches(gameId, ip)
                && !now.isBefore(disconnectedAt)
                && now.isBefore(disconnectedAt.plus(reconnectGrace));
    }
}
