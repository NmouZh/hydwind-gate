package me.hydwind.gate.login;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import org.junit.jupiter.api.Test;

/**
 * 断线事件是否会释放会话。
 *
 * <p>{@code CONFLICTING_LOGIN} 事件携带的是目标玩家的 UUID，不是被拒绝连接的
 * UUID；处理它会允许未认证者用别人的名字连接一次就清掉对方会话。</p>
 */
class LoginGateListenerTest {

    @Test
    void conflictingLoginMustNotReleaseTheTargetSession() {
        assertFalse(LoginGateListener.shouldReleaseSession(
                        DisconnectEvent.LoginStatus.CONFLICTING_LOGIN),
                "同名重复登录被拒绝时，事件里的 UUID 属于目标玩家，不能清掉其会话");
    }

    @Test
    void normalDisconnectsReleaseTheSession() {
        assertTrue(LoginGateListener.shouldReleaseSession(
                DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
        assertTrue(LoginGateListener.shouldReleaseSession(
                DisconnectEvent.LoginStatus.CANCELLED_BY_USER));
        assertTrue(LoginGateListener.shouldReleaseSession(
                DisconnectEvent.LoginStatus.CANCELLED_BY_PROXY));
        assertTrue(LoginGateListener.shouldReleaseSession(
                DisconnectEvent.LoginStatus.CANCELLED_BY_USER_BEFORE_COMPLETE));
        assertTrue(LoginGateListener.shouldReleaseSession(
                DisconnectEvent.LoginStatus.PRE_SERVER_JOIN));
    }
}