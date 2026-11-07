package me.hydwind.gate.web;

/**
 * 网站插件接口的客户端。所有失败都必须以明确的结果返回，不向调用方抛网络异常。
 */
public interface WebsiteClient {

    /**
     * 校验登录码。
     *
     * @param code   登录码或备用码
     * @param gameId 玩家游戏 ID
     * @param uuid   玩家 UUID
     * @return 校验结果；网络故障、超时、非预期响应一律为 {@link VerifyResult.Status#UNREACHABLE}
     */
    VerifyResult verify(String code, String gameId, java.util.UUID uuid);

    /**
     * 探活网站。
     */
    HealthResult health();
}
