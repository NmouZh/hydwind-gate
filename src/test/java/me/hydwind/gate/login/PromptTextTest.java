package me.hydwind.gate.login;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PromptTextTest {

    @Test
    void splitsTemplateAroundClickableUrl() {
        PromptText prompt = PromptText.fromTemplate(
                "请在浏览器打开 {url} 登录账号，然后回到游戏输入 /login <登录码>", "http://example.test/gate");

        assertEquals("请在浏览器打开 ", prompt.beforeUrl());
        assertEquals("http://example.test/gate", prompt.url());
        assertEquals(" 登录账号，然后回到游戏输入 /login <登录码>", prompt.afterUrl());
    }

    @Test
    void appendsUrlWhenTemplateOmitsPlaceholder() {
        PromptText prompt = PromptText.fromTemplate("请打开网站登录", "http://example.test/gate");

        assertEquals("请打开网站 ", prompt.beforeUrl());
        assertEquals("http://example.test/gate", prompt.url());
        assertEquals("", prompt.afterUrl());
    }

    @Test
    void blankTemplateStillShowsUrl() {
        PromptText prompt = PromptText.fromTemplate(null, "http://example.test/gate");

        assertEquals("", prompt.beforeUrl());
        assertEquals("http://example.test/gate", prompt.url());
        assertEquals("", prompt.afterUrl());
    }
}