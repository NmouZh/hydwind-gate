package me.hydwind.gate.config;

import java.util.Objects;

/**
 * 管理命令设置（{@code admin} 配置段）。
 *
 * @param permission 使用 {@code /hydwind} 所需权限，不能为空
 */
public record AdminSettings(String permission) {

    public AdminSettings {
        Objects.requireNonNull(permission, "permission");
        if (permission.isBlank()) {
            throw new IllegalArgumentException("admin.permission must not be blank");
        }
    }
}
