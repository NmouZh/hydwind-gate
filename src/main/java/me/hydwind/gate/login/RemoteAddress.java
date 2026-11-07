package me.hydwind.gate.login;

import java.net.InetSocketAddress;

/** 从连接地址取远端 IP，供会话宽限与日志使用。已解析地址用 IP，未解析地址退回主机名。 */
public final class RemoteAddress {

    private RemoteAddress() {
    }

    public static String remoteIp(InetSocketAddress address) {
        if (address == null) {
            return "unknown";
        }
        return address.getAddress() == null
                ? address.getHostString()
                : address.getAddress().getHostAddress();
    }
}