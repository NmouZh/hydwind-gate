package me.hydwind.gate.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MessagesTest {

    @Test
    void overridesProvidedKeysAndKeepsDefaultsForMissingOnes() {
        Map<String, Object> root = Map.<String, Object>of(
                "messages", Map.<String, Object>of(
                        "gate-title", "自定义标题",
                        "gate-prompt", "打开 {url}",
                        "admin", Map.<String, Object>of("no-permission", "无权限")));

        Messages messages = Messages.from(root);
        Messages defaults = Messages.defaults();

        assertEquals("自定义标题", messages.gateTitle());
        assertEquals("打开 {url}", messages.gatePrompt());
        assertEquals("无权限", messages.admin().noPermission());
        assertEquals(defaults.loginSuccess(), messages.loginSuccess());
        assertEquals(defaults.sessionTimeout(), messages.sessionTimeout());
        assertEquals(defaults.admin().usage(), messages.admin().usage());
    }

    @Test
    void missingMessagesSectionUsesDefaults() {
        assertEquals(Messages.defaults(), Messages.from(Map.of()));
    }
}