package me.hydwind.gate.login;

import java.util.Objects;

/**
 * 登录提示模板按 {@code {url}} 切成三段，便于把中间一段渲染成可点击链接。
 *
 * @param beforeUrl 网址前的文字
 * @param url       可点击的网站地址
 * @param afterUrl  网址后的文字
 */
public record PromptText(String beforeUrl, String url, String afterUrl) {

    public static final String URL_PLACEHOLDER = "{url}";

    public PromptText {
        Objects.requireNonNull(beforeUrl, "beforeUrl");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(afterUrl, "afterUrl");
    }

    public static PromptText fromTemplate(String template, String url) {
        Objects.requireNonNull(url, "url");
        if (template == null || template.isBlank()) {
            return new PromptText("", url, "");
        }

        int index = template.indexOf(URL_PLACEHOLDER);
        if (index >= 0) {
            return new PromptText(
                    template.substring(0, index),
                    url,
                    template.substring(index + URL_PLACEHOLDER.length()));
        }

        // 模板没有占位符时把网址补在末尾，保证玩家总能看到它。
        String separator = Character.isWhitespace(template.charAt(template.length() - 1)) ? "" : " ";
        return new PromptText(template + separator, url, "");
    }
}