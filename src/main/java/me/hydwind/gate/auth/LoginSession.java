package me.hydwind.gate.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一个玩家本次进服的登录会话。对象不可变，状态变化由 {@link LoginSessionStore} 产生新实例。
 *
 * @param disconnectedAt 已认证玩家断线的时刻；在线时为 {@code null}
 * @param loginDeadline  本次登录会话的截止时刻，进服时固定；宽限恢复的会话沿用原值
 * @param verificationId 本次校验的编号，每次 {@code beginVerification} 递增；{@code 0} 表示尚未提交
 */
public record LoginSession(
        UUID uuid,
        String gameId,
        String ip,
        Status status,
        Instant enteredAt,
        Instant lastActivityAt,
        Instant disconnectedAt,
        Instant loginDeadline,
        long verificationId) {

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
        Objects.requireNonNull(loginDeadline, "loginDeadline");
    }

    public boolean isAuthenticated() {
        return status == Status.AUTHENTICATED;
    }

    static LoginSession pending(UUID uuid, String gameId, String ip, Instant now, Instant loginDeadline) {
        return new LoginSession(uuid, gameId, ip, Status.PENDING, now, now, null, loginDeadline, 0L);
    }

    LoginSession transitionTo(Status next, Instant now) {
        return transitionTo(next, now, verificationId);
    }

    LoginSession transitionTo(Status next, Instant now, long verificationId) {
        return new LoginSession(uuid, gameId, ip, next, enteredAt, now, null, loginDeadline, verificationId);
    }

    LoginSession disconnectedAt(Instant now) {
        return new LoginSession(uuid, gameId, ip, status, enteredAt, now, now, loginDeadline, verificationId);
    }

    LoginSession reconnectedAt(Instant now) {
        return new LoginSession(uuid, gameId, ip, status, enteredAt, now, null, loginDeadline, verificationId);
    }

    boolean matches(String candidateGameId, String candidateIp) {
        return gameId.equals(candidateGameId) && ip.equals(candidateIp);
    }
}
