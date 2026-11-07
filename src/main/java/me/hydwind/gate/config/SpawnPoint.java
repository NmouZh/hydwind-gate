package me.hydwind.gate.config;

import java.util.Objects;

/**
 * 登录冻结时玩家被传送到的出生点。
 */
public record SpawnPoint(String world, double x, double y, double z, float yaw, float pitch) {

    public static final SpawnPoint DEFAULT = new SpawnPoint("world", 0.0, 64.0, 0.0, 0.0f, 0.0f);

    public SpawnPoint {
        Objects.requireNonNull(world, "world");
    }
}