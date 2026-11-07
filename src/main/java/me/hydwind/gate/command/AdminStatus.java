package me.hydwind.gate.command;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import me.hydwind.gate.auth.LoginSession;
import me.hydwind.gate.ui.AdminMessages;
import me.hydwind.gate.web.HealthResult;
import me.hydwind.gate.web.RestartPolicy;

/**
 * {@code /hydwind status} 的统计快照与文案渲染。纯逻辑，不访问 Velocity 或网站。
 *
 * @param limboPlayers    当前仍停留在 limbo 的玩家数
 * @param pending         待认证会话数（PENDING / VERIFYING）
 * @param authenticated   已认证会话数（含离线宽限）
 * @param grace           已断线、仍在宽限期内的认证会话数
 * @param websiteHealthy  最近一次健康检查结果
 * @param uptimeSeconds   网站自报运行秒数
 * @param restartRemaining 当前可用的受控重启额度
 * @param nextAllowed     额度耗尽时恢复的最早时间
 */
public record AdminStatus(
        int limboPlayers,
        int pending,
        int authenticated,
        int grace,
        boolean websiteHealthy,
        long uptimeSeconds,
        int restartRemaining,
        Optional<Instant> nextAllowed
) {

    public AdminStatus {
        Objects.requireNonNull(nextAllowed, "nextAllowed");
    }

    public static AdminStatus from(List<LoginSession> sessions,
                                   int limboPlayers,
                                   HealthResult health,
                                   RestartPolicy policy,
                                   Instant now) {
        Objects.requireNonNull(sessions, "sessions");
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
                limboPlayers,
                pending,
                authenticated,
                grace,
                effective.healthy(),
                effective.uptimeSeconds(),
                policy.remaining(now),
                policy.nextAllowed(now));
    }

    /** 渲染成逐行发给管理员的状态文本。 */
    public List<String> lines(AdminMessages messages, String permission, DateTimeFormatter timeFormat) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(timeFormat, "timeFormat");

        List<String> lines = new ArrayList<>(6);
        lines.add(fill(messages.statusLimbo(), "count", limboPlayers));
        lines.add(fill(messages.statusPending(), "count", pending));
        if (grace > 0) {
            lines.add(fill(messages.statusAuthenticatedGrace(),
                    "count", authenticated, "grace", grace));
        } else {
            lines.add(fill(messages.statusAuthenticated(), "count", authenticated));
        }
        lines.add(websiteHealthy
                ? fill(messages.statusHealthHealthy(), "uptime", uptimeSeconds)
                : messages.statusHealthUnhealthy());

        String quota = fill(messages.statusRestartQuota(), "remaining", restartRemaining);
        if (restartRemaining == 0 && nextAllowed.isPresent()) {
            quota += fill(messages.statusNextAllowed(), "time", timeFormat.format(nextAllowed.get()));
        }
        lines.add(quota);
        lines.add(fill(messages.statusPermission(), "permission", permission));
        return List.copyOf(lines);
    }

    private static String fill(String template, String placeholder, Object value) {
        return template.replace("{" + placeholder + "}", String.valueOf(value));
    }

    private static String fill(String template,
                               String firstPlaceholder, Object firstValue,
                               String secondPlaceholder, Object secondValue) {
        return template
                .replace("{" + firstPlaceholder + "}", String.valueOf(firstValue))
                .replace("{" + secondPlaceholder + "}", String.valueOf(secondValue));
    }
}