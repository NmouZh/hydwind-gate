package me.hydwind.gate.ui;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 管理员命令文案。与玩家文案一起由 {@code config.yml} 的 {@code messages.admin} 段提供，
 * 便于服主按需改写。模板占位符：{@code {player}}、{@code {permission}}、{@code {count}}、
 * {@code {grace}}、{@code {uptime}}、{@code {remaining}}、{@code {time}}。
 */
public record AdminMessages(
        String noPermission,
        String usage,
        String unknownSubcommand,
        String reloaded,
        String approveUsage,
        String kickUsage,
        String sessionNotFound,
        String alreadyAuthenticated,
        String approvedPlayer,
        String approved,
        String kickedPlayer,
        String kicked,
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
                "用法: /hydwind approve <玩家>",
                "用法: /hydwind kick <玩家>",
                "没有找到玩家 {player} 的登录会话。",
                "玩家 {player} 已经通过认证。",
                "管理员已手动放行你的登录。",
                "已放行玩家 {player}。",
                "你已被管理员踢出服务器。",
                "已踢出玩家 {player} 并清理会话。",
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

    public static AdminMessages from(FileConfiguration config) {
        AdminMessages defaults = defaults();
        return new AdminMessages(
                config.getString("messages.admin.no-permission", defaults.noPermission()),
                config.getString("messages.admin.usage", defaults.usage()),
                config.getString("messages.admin.unknown-subcommand", defaults.unknownSubcommand()),
                config.getString("messages.admin.reloaded", defaults.reloaded()),
                config.getString("messages.admin.approve-usage", defaults.approveUsage()),
                config.getString("messages.admin.kick-usage", defaults.kickUsage()),
                config.getString("messages.admin.session-not-found", defaults.sessionNotFound()),
                config.getString("messages.admin.already-authenticated", defaults.alreadyAuthenticated()),
                config.getString("messages.admin.approved-player", defaults.approvedPlayer()),
                config.getString("messages.admin.approved", defaults.approved()),
                config.getString("messages.admin.kicked-player", defaults.kickedPlayer()),
                config.getString("messages.admin.kicked", defaults.kicked()),
                config.getString("messages.admin.status-pending", defaults.statusPending()),
                config.getString("messages.admin.status-authenticated", defaults.statusAuthenticated()),
                config.getString("messages.admin.status-authenticated-grace", defaults.statusAuthenticatedGrace()),
                config.getString("messages.admin.status-health-healthy", defaults.statusHealthHealthy()),
                config.getString("messages.admin.status-health-unhealthy", defaults.statusHealthUnhealthy()),
                config.getString("messages.admin.status-restart-quota", defaults.statusRestartQuota()),
                config.getString("messages.admin.status-next-allowed", defaults.statusNextAllowed()),
                config.getString("messages.admin.status-permission", defaults.statusPermission())
        );
    }
}