package me.hydwind.gate.command;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import me.hydwind.gate.auth.LoginSession;
import me.hydwind.gate.web.HealthResult;
import me.hydwind.gate.web.RestartPolicy;

/**
 * {@code /hydwind status} 的统计快照。纯逻辑，不访问 Velocity 或网站；
 * 文案渲染见 {@link me.hydwind.gate.ui.AdminStatusRenderer}。
 *
 * @param limboPlayers     当前仍停留在 limbo 的玩家数
 * @param limboPlayerNames 当前 limbo 玩家名快照；排序与截断由渲染器负责
 * @param pending          待认证会话数（PENDING / VERIFYING）
 * @param authenticated    已认证会话数（含离线宽限）
 * @param grace            已断线、仍在宽限期内的认证会话数
 * @param websiteHealthy   最近一次健康检查结果
 * @param uptimeSeconds    网站自报运行秒数
 * @param restartRemaining 当前可用的受控重启额度
 * @param nextAllowed      额度耗尽时恢复的最早时间
 */
public record AdminStatus(
        int limboPlayers,
        List<String> limboPlayerNames,
        int pending,
        int authenticated,
        int grace,
        boolean websiteHealthy,
        long uptimeSeconds,
        int restartRemaining,
        Optional<Instant> nextAllowed
) {

    public AdminStatus {
        Objects.requireNonNull(limboPlayerNames, "limboPlayerNames");
        Objects.requireNonNull(nextAllowed, "nextAllowed");
        limboPlayerNames = List.copyOf(limboPlayerNames);
    }

    public static AdminStatus from(List<LoginSession> sessions,
                                   List<String> limboPlayerNames,
                                   HealthResult health,
                                   RestartPolicy policy,
                                   Instant now) {
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(limboPlayerNames, "limboPlayerNames");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(now, "now");

        int pending = 0;
        int authenticated = 0;
        int grace = 0;
        for (LoginSession session : sessions) {
            if (session.status() == LoginSession.Status.PENDING
                    || session.status() == LoginSession.Status.VERIFYING) {
                pending++;
            }
            if (session.isAuthenticated()) {
                authenticated++;
                if (session.disconnectedAt() != null) {
                    grace++;
                }
            }
        }

        HealthResult effective = health == null ? HealthResult.unhealthy() : health;
        return new AdminStatus(
                limboPlayerNames.size(),
                limboPlayerNames,
                pending,
                authenticated,
                grace,
                effective.healthy(),
                effective.uptimeSeconds(),
                policy.remaining(now),
                policy.nextAllowed(now));
    }
}
