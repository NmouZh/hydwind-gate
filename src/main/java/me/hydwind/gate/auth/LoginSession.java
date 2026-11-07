package me.hydwind.gate.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一个玩家本次进服的登录会话。对象不可变，状态变化由 {@link LoginSessionStore} 产生新实例。
 *
 * @param disconnectedAt 已认证玩家断线的时刻；在线时为 {@code null}
 */
public record LoginSession(
        UUID uuid,
        String gameId,
        String ip,
        Status status,
        Instant enteredAt,
        Instant lastActivityAt,
        Instant disconnectedAt) {

    public enum Status {
        PENDING,
        VERIFYING,
        AUTHENTICATED,
        TIMED_OUT
    }

    public LoginSession {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(ip, "ip");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(enteredAt, "enteredAt");
        Objects.requireNonNull(lastActivityAt, "lastActivityAt");
    }

    public boolean isAuthenticated() {
        return status == Status.AUTHENTICATED;
    }

    static LoginSession pending(UUID uuid, String gameId, String ip, Instant now) {
        return new LoginSession(uuid, gameId, ip, Status.PENDING, now, now, null);
    }

    LoginSession transitionTo(Status next, Instant now) {
        return new LoginSession(uuid, gameId, ip, next, enteredAt, now, null);
    }

    LoginSession disconnectedAt(Instant now) {
        return new LoginSession(uuid, gameId, ip, status, enteredAt, now, now);
    }

    LoginSession reconnectedAt(Instant now) {
        return new LoginSession(uuid, gameId, ip, status, enteredAt, now, null);
    }

    boolean matches(String candidateGameId, String candidateIp) {
        return gameId.equals(candidateGameId) && ip.equals(candidateIp);
    }
}
