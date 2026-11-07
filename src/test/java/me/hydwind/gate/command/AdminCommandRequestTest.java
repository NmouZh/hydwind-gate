package me.hydwind.gate.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * {@link AdminCommandRequest} 的 record 语义。
 *
 * <p>解析规则已迁到 {@link CommandGrammar}，由 {@code CommandGrammarTest} 覆盖；
 * 这里只固定结果载体的契约。</p>
 */
class AdminCommandRequestTest {

    @Test
    void keepsTypeAndTarget() {
        AdminCommandRequest request = new AdminCommandRequest(AdminCommandRequest.Type.APPROVE, "Alice");
        assertEquals(AdminCommandRequest.Type.APPROVE, request.type());
        assertEquals("Alice", request.target());

        assertNull(new AdminCommandRequest(AdminCommandRequest.Type.STATUS, null).target());
    }

    @Test
    void rejectsNullType() {
        assertThrows(NullPointerException.class, () -> new AdminCommandRequest(null, "Alice"));
    }

    @Test
    void hasValueSemantics() {
        AdminCommandRequest first = new AdminCommandRequest(AdminCommandRequest.Type.KICK, "Bob");
        AdminCommandRequest same = new AdminCommandRequest(AdminCommandRequest.Type.KICK, "Bob");
        AdminCommandRequest different = new AdminCommandRequest(AdminCommandRequest.Type.KICK, "Alice");

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, different);
    }

    @Test
    void noLongerHasNoneType() {
        // ADR-0002 第 5 节：limbo 侧改用 Optional 表达“不是我的命令”。
        assertThrows(IllegalArgumentException.class,
                () -> AdminCommandRequest.Type.valueOf("NONE"));
    }
}
