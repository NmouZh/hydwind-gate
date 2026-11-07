package me.hydwind.gate.web;

/**
 * 网站插件接口的客户端。所有失败都必须以明确的结果返回，不向调用方抛网络异常。
 *
 * <p>失败按来源分成两类：传输层失败（连接失败、超时、5xx）映射为
 * {@link VerifyResult.Status#UNREACHABLE} / {@link HealthResult#unhealthy()}；
 * 网站答得出响应但状态码、字段或结构与契约不符时映射为
 * {@link VerifyResult.Status#CONTRACT_MISMATCH} / {@link HealthResult#unhealthyContract()}。
 * 后者重启网站也无济于事，调用方不得据此触发受控重启。</p>
 */
public interface WebsiteClient {

    /**
     * 校验登录码。
     *
     * @param code   登录码或备用码
     * @param gameId 玩家游戏 ID
     * @param uuid   玩家 UUID
     * @return 校验结果；传输层失败一律为 {@link VerifyResult.Status#UNREACHABLE}，
     *         响应存在但不符合契约时为 {@link VerifyResult.Status#CONTRACT_MISMATCH}
     */
    VerifyResult verify(String code, String gameId, java.util.UUID uuid);

    /**
     * 探活网站。
     */
    HealthResult health();
}
