package me.hydwind.gate.ui;

import java.util.Map;
import me.hydwind.gate.config.YamlMaps;

/**
 * 管理员命令文案。与玩家文案一起由 {@code config.yml} 的 {@code messages.admin} 段提供，
 * 便于服主按需改写。模板占位符：{@code {player}}、{@code {permission}}、{@code {count}}、
 * {@code {grace}}、{@code {uptime}}、{@code {remaining}}、{@code {time}}、{@code {error}}。
 */
public record AdminMessages(
        String noPermission,
        String usage,
        String unknownSubcommand,
        String reloaded,
        String reloadFailed,
        String approveUsage,
        String kickUsage,
        String sessionNotFound,
        String alreadyAuthenticated,
        String approvedPlayer,
        String approved,
        String kickedPlayer,
        String kicked,
        String statusLimbo,
        String statusPending,
        String statusAuthenticated,
        String statusAuthenticatedGrace,
        String statusHealthHealthy,
        String statusHealthUnhealthy,
        String statusRestartQuota,
        String statusNextAllowed,
        String statusPermission
) {

    public static AdminMessages defaults() {
        return new AdminMessages(
                "你没有权限使用 /hydwind（需要 {permission}）。",
                "用法: /hydwind <status|reload|approve <玩家>|kick <玩家>>",
                "未知子命令。用法: /hydwind <status|reload|approve <玩家>|kick <玩家>>",
                "配置已重载；进行中的登录会话保持不变。",
                "配置重载失败: {error}",
                "用法: /hydwind approve <玩家>",
                "用法: /hydwind kick <玩家>",
                "没有找到玩家 {player} 的登录会话。",
                "玩家 {player} 已经通过认证。",
                "管理员已手动放行你的登录。",
                "已放行玩家 {player}。",
                "你已被管理员踢出服务器。",
                "已踢出玩家 {player} 并清理会话。",
                "Limbo 中: {count}",
                "待认证: {count}",
                "已认证: {count}",
                "已认证: {count}（其中离线宽限 {grace}）",
                "网站: 健康（uptime {uptime} 秒）",
                "网站: 不可达",
                "重启额度: {remaining} 次可用",
                "（下次允许 {time}）",
                "管理员权限: {permission}"
        );
    }

    /** @param messagesSection {@code config.yml} 的 {@code messages} 段 */
    public static AdminMessages from(Map<String, Object> messagesSection) {
        Map<String, Object> section = YamlMaps.section(messagesSection, "admin");
        AdminMessages defaults = defaults();
        return new AdminMessages(
                YamlMaps.rawString(section, "no-permission", defaults.noPermission()),
                YamlMaps.rawString(section, "usage", defaults.usage()),
                YamlMaps.rawString(section, "unknown-subcommand", defaults.unknownSubcommand()),
                YamlMaps.rawString(section, "reloaded", defaults.reloaded()),
                YamlMaps.rawString(section, "reload-failed", defaults.reloadFailed()),
                YamlMaps.rawString(section, "approve-usage", defaults.approveUsage()),
                YamlMaps.rawString(section, "kick-usage", defaults.kickUsage()),
                YamlMaps.rawString(section, "session-not-found", defaults.sessionNotFound()),
                YamlMaps.rawString(section, "already-authenticated", defaults.alreadyAuthenticated()),
                YamlMaps.rawString(section, "approved-player", defaults.approvedPlayer()),
                YamlMaps.rawString(section, "approved", defaults.approved()),
                YamlMaps.rawString(section, "kicked-player", defaults.kickedPlayer()),
                YamlMaps.rawString(section, "kicked", defaults.kicked()),
                YamlMaps.rawString(section, "status-limbo", defaults.statusLimbo()),
                YamlMaps.rawString(section, "status-pending", defaults.statusPending()),
                YamlMaps.rawString(section, "status-authenticated", defaults.statusAuthenticated()),
                YamlMaps.rawString(section, "status-authenticated-grace", defaults.statusAuthenticatedGrace()),
                YamlMaps.rawString(section, "status-health-healthy", defaults.statusHealthHealthy()),
                YamlMaps.rawString(section, "status-health-unhealthy", defaults.statusHealthUnhealthy()),
                YamlMaps.rawString(section, "status-restart-quota", defaults.statusRestartQuota()),
                YamlMaps.rawString(section, "status-next-allowed", defaults.statusNextAllowed()),
                YamlMaps.rawString(section, "status-permission", defaults.statusPermission())
        );
    }
}