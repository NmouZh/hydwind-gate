package me.hydwind.gate.ui;

import java.util.Objects;

/** {@code {placeholder}} 模板替换工具；值为 {@code null} 时替换为空字符串。 */
public final class Templates {

    private Templates() {
    }

    /**
     * 依次替换占位符。
     *
     * @param template             模板文本
     * @param placeholderValuePairs 成对的占位符名与值，即 {@code "name", value, "count", 3}
     */
    public static String fill(String template, Object... placeholderValuePairs) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(placeholderValuePairs, "placeholderValuePairs");
        if (placeholderValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("placeholder/value pairs must be even");
        }

        String result = template;
        for (int i = 0; i < placeholderValuePairs.length; i += 2) {
            String placeholder = String.valueOf(placeholderValuePairs[i]);
            Object value = placeholderValuePairs[i + 1];
            result = result.replace("{" + placeholder + "}", value == null ? "" : String.valueOf(value));
        }
        return result;
    }
}