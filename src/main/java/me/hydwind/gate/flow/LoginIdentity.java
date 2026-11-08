package me.hydwind.gate.flow;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次登录流程中的玩家身份。
 *
 * <p>由平台 Adapter 在玩家进服时构造，登录流程内部只使用这里的数据，
 * 不直接接触 Velocity 的 {@code Player}。</p>
 *
 * @param uuid         玩家 UUID，登录会话的主键
 * @param gameId       玩家游戏 ID，提交给网站校验
 * @param ip           玩家远端地址，进服时由 {@code RemoteAddress} 提取
 * @param connectionId 本次连接的代际编号；0 仅用于不涉及真实连接的测试
 */
public record LoginIdentity(UUID uuid, String gameId, String ip, long connectionId) {

    public LoginIdentity {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(ip, "ip");
    }

    /** 兼容旧测试/纯逻辑调用的无连接版本。生产路径必须带 connectionId。 */
    public LoginIdentity(UUID uuid, String gameId, String ip) {
        this(uuid, gameId, ip, 0L);
    }
}