package me.hydwind.gate.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DialogFactoryTest {

    private static final String URL = "http://example.test/gate";

    @Test
    void substitutesUrlPlaceholder() {
        assertEquals("打开 " + URL + " 登录", DialogFactory.renderPrompt("打开 {url} 登录", URL));
    }

    @Test
    void appendsUrlWhenPromptHasNoPlaceholder() {
        String prompt = DialogFactory.renderPrompt("请登录账号", URL);

        assertTrue(prompt.contains(URL), () -> "dialog body should always show the website URL: " + prompt);
    }

    @Test
    void doesNotDuplicateUrlWhenTemplateAlreadyContainsIt() {
        assertEquals("请打开 " + URL, DialogFactory.renderPrompt("请打开 " + URL, URL));
    }
}