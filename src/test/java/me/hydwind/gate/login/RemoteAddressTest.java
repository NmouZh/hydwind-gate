package me.hydwind.gate.login;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

/** 玩家 IP 提取：已解析地址用 IP，未解析地址退回主机名，缺失时返回 unknown。 */
class RemoteAddressTest {

    @Test
    void usesResolvedIpAddress() {
        assertEquals("127.0.0.1",
                RemoteAddress.remoteIp(new InetSocketAddress("127.0.0.1", 25565)));
    }

    @Test
    void fallsBackToHostStringWhenUnresolved() {
        assertEquals("player.example",
                RemoteAddress.remoteIp(InetSocketAddress.createUnresolved("player.example", 25565)));
    }

    @Test
    void missingAddressIsUnknown() {
        assertEquals("unknown", RemoteAddress.remoteIp(null));
    }
}