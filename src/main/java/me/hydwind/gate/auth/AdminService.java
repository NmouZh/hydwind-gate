package me.hydwind.gate.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import me.hydwind.gate.ui.PlayerGateway;
import me.hydwind.gate.web.HealthResult;
import me.hydwind.gate.web.RestartPolicy;
import me.hydwind.gate.web.WebsiteClient;

/**
 * 管理员命令的纯 Java 核心：状态统计、手动放行、踢出。Bukkit 副作用经由
 * {@link PlayerGateway} 承担，因此可以直接用假实现测试。
 */
public final class AdminService {

    public enum Outcome {
        OK,
        NOT_FOUND,
        ALREADY_AUTHENTICATED
    }

    public record Result(Outcome outcome, String message) {
    }

    /**
     * @param pending             待认证（PENDING + VERIFYING）
     * @param authenticated       已认证（含离线宽限记录）
     * @param grace               其中已离线、仍在宽限期内的记录
     * @param health              网站最近一次探活结果
     * @param restartRemaining    重启策略剩余额度
     * @param nextRestartAllowedAt 额度耗尽或冷却中时的最早恢复时间
     */
    public record Status(int pending,
                         int authenticated,
                         int grace,
                         HealthResult health,
                         int restartRemaining,
                         Optional<Instant> nextRestartAllowedAt) {
    }

    private final LoginSessionStore sessionStore;
    private final PlayerGateway playerGateway;
    private final WebsiteClient websiteClient;
    private final RestartPolicy restartPolicy;
    private final Clock clock;

    public AdminService(LoginSessionStore sessionStore,
                        PlayerGateway playerGateway,
                        WebsiteClient websiteClient,
                        RestartPolicy restartPolicy,
                        Clock clock) {
        this.sessionStore = sessionStore;
        this.playerGateway = playerGateway;
        this.websiteClient = websiteClient;
        this.restartPolicy = restartPolicy;
        this.clock = clock;
    }

    public Status status() {
        Instant now = clock.instant();
        int pending = 0;
        int authenticated = 0;
        int grace = 0;
        for (LoginSession session : sessionStore.snapshot()) {
            switch (session.status()) {
                case PENDING, VERIFYING -> pending++;
                case AUTHENTICATED -> {
                    authenticated++;
                    if (session.disconnectedAt() != null) {
                        grace++;
                    }
                }
                case TIMED_OUT -> {
                    // 即将被踢出的会话不计入待认证。
                }
            }
        }
        return new Status(pending, authenticated, grace, websiteClient.health(),
                restartPolicy.remaining(now), restartPolicy.nextAllowed(now));
    }

    public Result approve(String gameId) {
        LoginSession session = sessionStore.findByGameId(gameId).orElse(null);
        if (session == null) {
            return new Result(Outcome.NOT_FOUND, "没有找到玩家 " + gameId + " 的登录会话。");
        }
        if (session.isAuthenticated()) {
            return new Result(Outcome.ALREADY_AUTHENTICATED, "玩家 " + session.gameId() + " 已经通过认证。");
        }
        if (!sessionStore.forceAuthenticate(session.uuid(), clock.instant())) {
            return new Result(Outcome.NOT_FOUND, "没有找到玩家 " + gameId + " 的登录会话。");
        }
        playerGateway.sendMessage(session.uuid(), "管理员已手动放行你的登录。");
        playerGateway.unfreeze(session.uuid());
        return new Result(Outcome.OK, "已放行玩家 " + session.gameId() + "。");
    }

    public Result kick(String gameId) {
        LoginSession session = sessionStore.findByGameId(gameId).orElse(null);
        if (session == null) {
            return new Result(Outcome.NOT_FOUND, "没有找到玩家 " + gameId + " 的登录会话。");
        }
        sessionStore.remove(session.uuid());
        playerGateway.kick(session.uuid(), "你已被管理员踢出服务器。");
        return new Result(Outcome.OK, "已踢出玩家 " + session.gameId() + " 并清理会话。");
    }
}