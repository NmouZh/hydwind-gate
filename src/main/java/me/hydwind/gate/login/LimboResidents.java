package me.hydwind.gate.login;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 当前 limbo 中玩家的窄视图。编排层（如管理操作）只依赖这个接口，
 * 由 {@link LimboLoginService} 实现，测试可用假实现替代。
 *
 * <p>所有方法都应在调用线程上安全执行；实现方负责处理并发。</p>
 */
public interface LimboResidents {

    /** 按游戏 ID 精确查找仍在 limbo 的玩家；离线模式下大小写变体是不同账号。 */
    Optional<Resident> find(String gameId);

    /** 当前 limbo 中的全部玩家快照。 */
    List<Resident> all();

    /**
     * 管理员放行：让玩家停止提示并转到主服。
     *
     * <p>调用前必须已经把登录会话写成 AUTHENTICATED，否则转服会被门禁拒绝。</p>
     *
     * @return 玩家此刻是否仍在 limbo 并被处理
     */
    boolean approve(UUID uuid, String message);

    /**
     * 管理员踢出：断开 limbo 中的玩家。会话清理由调用方负责。
     *
     * @return 玩家此刻是否仍在 limbo 并被处理
     */
    boolean kick(UUID uuid, String message);

    /** limbo 玩家的只读身份。 */
    record Resident(UUID uuid, String gameId) {
        public Resident {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(gameId, "gameId");
        }
    }
}