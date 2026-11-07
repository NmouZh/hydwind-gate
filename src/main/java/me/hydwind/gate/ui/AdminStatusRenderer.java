package me.hydwind.gate.ui;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import me.hydwind.gate.command.AdminStatus;

/**
 * 把 {@link AdminStatus} 快照渲染成逐行发给管理员的状态文本。
 *
 * <p>纯展示逻辑：不访问 Velocity、网站或会话存储。排序与截断也只在这里做，
 * {@code AdminStatus} 保持为原样的统计快照。</p>
 */
public final class AdminStatusRenderer {

    /** 状态里最多列出的 limbo 玩家名数量，避免刷屏。 */
    public static final int MAX_LIMBO_NAMES = 20;

    /**
     * 玩家名过多时的固定后缀。它不随 {@code config.yml} 的 {@code messages} 段配置，
     * 因此不放进 {@link AdminMessages}，避免为一条内置文案扩散 record 字段。
     */
    private static final String MORE_NAMES_SUFFIX = " 等 {count} 人";

    private AdminStatusRenderer() {
    }

    /**
     * 渲染完整的状态行。grace、nextAllowed 等分支与旧版 {@code AdminStatus.lines} 保持一致。
     *
     * @param status     状态快照
     * @param messages   管理员文案
     * @param permission 调用者拥有的管理权限名
     * @param timeFormat {@code nextAllowed} 的显示格式
     */
    public static List<String> render(AdminStatus status,
                                      AdminMessages messages,
                                      String permission,
                                      DateTimeFormatter timeFormat) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(timeFormat, "timeFormat");

        List<String> lines = new ArrayList<>(7);
        lines.add(Templates.fill(messages.statusLimbo(), "count", status.limboPlayers()));
        if (!status.limboPlayerNames().isEmpty()) {
            lines.add(Templates.fill(messages.statusLimboNames(),
                    "players", formatLimboPlayerNames(status.limboPlayerNames())));
        }
        lines.add(Templates.fill(messages.statusPending(), "count", status.pending()));
        if (status.grace() > 0) {
            lines.add(Templates.fill(messages.statusAuthenticatedGrace(),
                    "count", status.authenticated(), "grace", status.grace()));
        } else {
            lines.add(Templates.fill(messages.statusAuthenticated(),
                    "count", status.authenticated()));
        }
        lines.add(status.websiteHealthy()
                ? Templates.fill(messages.statusHealthHealthy(), "uptime", status.uptimeSeconds())
                : messages.statusHealthUnhealthy());

        String quota = Templates.fill(messages.statusRestartQuota(),
                "remaining", status.restartRemaining());
        if (status.restartRemaining() == 0 && status.nextAllowed().isPresent()) {
            quota += Templates.fill(messages.statusNextAllowed(),
                    "time", timeFormat.format(status.nextAllowed().get()));
        }
        lines.add(quota);
        lines.add(Templates.fill(messages.statusPermission(), "permission", permission));
        return List.copyOf(lines);
    }

    /** 排序后拼接玩家名；超过上限时只列前 {@value #MAX_LIMBO_NAMES} 个并附「等 N 人」。 */
    private static String formatLimboPlayerNames(List<String> names) {
        List<String> sorted = names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        if (sorted.size() <= MAX_LIMBO_NAMES) {
            return String.join(", ", sorted);
        }
        String shown = String.join(", ", sorted.subList(0, MAX_LIMBO_NAMES));
        return shown + Templates.fill(MORE_NAMES_SUFFIX, "count", sorted.size());
    }
}
