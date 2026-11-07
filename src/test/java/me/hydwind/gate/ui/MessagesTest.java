package me.hydwind.gate.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void warnsWhenConfiguredPromptDropsTheUrlPlaceholder() {
        Map<String, Object> root = Map.of("messages",
                Map.of("gate-prompt", "请打开网站登录。"));

        assertEquals(Map.of("messages.gate-prompt", "{url}"),
                Messages.placeholderWarnings(root));
    }

    @Test
    void warnsWhenConfiguredLoginSuccessDropsTheNamePlaceholder() {
        Map<String, Object> root = Map.of("messages",
                Map.of("login-success", "登录成功，欢迎回来！"));

        assertEquals(Map.of("messages.login-success", "{name}"),
                Messages.placeholderWarnings(root));
    }

    @Test
    void warnsWhenConfiguredMismatchMessageDropsTheExpectedGameIdPlaceholder() {
        Map<String, Object> root = Map.of("messages",
                Map.of("game-id-mismatch-expected", "请使用正确的游戏 ID 登录。"));

        assertEquals(Map.of("messages.game-id-mismatch-expected", "{expectedGameId}"),
                Messages.placeholderWarnings(root));
    }

    @Test
    void warnsAboutAdminMessagesInTheirOwnSection() {
        Map<String, Object> root = Map.of("messages", Map.of("admin",
                Map.of("no-permission", "你没有权限。",
                        "status-authenticated-grace", "已认证: 若干")));

        assertEquals(Map.of(
                        "messages.admin.no-permission", "{permission}",
                        "messages.admin.status-authenticated-grace", "{count}, {grace}"),
                Messages.placeholderWarnings(root));
    }

    @Test
    void configuredTextWithAllDefaultPlaceholdersProducesNoWarnings() {
        Map<String, Object> root = Map.of("messages", Map.of(
                "gate-prompt", "打开 {url} 获取登录码。",
                "login-success", "欢迎回来，{name}！",
                "game-id-mismatch-expected", "请使用游戏 ID {expectedGameId} 的账号登录。",
                "admin", Map.of(
                        "no-permission", "需要 {permission}。",
                        "status-authenticated-grace", "已认证: {count}，宽限 {grace}")));

        assertTrue(Messages.placeholderWarnings(root).isEmpty());
    }

    @Test
    void unknownPlaceholdersAndUnknownKeysAreIgnored() {
        Map<String, Object> root = Map.of("messages", Map.of(
                "gate-prompt", "打开 {url}，或输入 {unknownPlaceholder}。",
                "no-such-message", "未知键 {whatever}"));

        assertTrue(Messages.placeholderWarnings(root).isEmpty());
    }
}