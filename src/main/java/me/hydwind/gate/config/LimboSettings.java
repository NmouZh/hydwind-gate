package me.hydwind.gate.config;

import java.util.Objects;

/**
 * LimboAPI 虚拟世界设置（{@code limbo} 配置段）。
 *
 * <p>世界名与出生点只在启动时生效；热重载只比较并告警。</p>
 *
 * @param world 虚拟世界名，不能为空
 * @param spawn 出生坐标，格式 {@code "x, y, z"}；格式解析由 {@code LimboSpawn} 负责
 */
public record LimboSettings(String world, String spawn) {

    public LimboSettings {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(spawn, "spawn");
        if (world.isBlank()) {
            throw new IllegalArgumentException("limbo.world must not be blank");
        }
    }
}
