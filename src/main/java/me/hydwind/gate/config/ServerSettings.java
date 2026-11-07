package me.hydwind.gate.config;

import java.util.Objects;

/**
 * 转服设置（{@code server} 配置段）。
 *
 * @param mainServer 认证成功后转服的目标服务器名（{@code velocity.toml} 中 servers 的键）
 */
public record ServerSettings(String mainServer) {

    public ServerSettings {
        Objects.requireNonNull(mainServer, "mainServer");
    }
}
