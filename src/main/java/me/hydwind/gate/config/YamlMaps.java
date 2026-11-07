package me.hydwind.gate.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * SnakeYAML 解析出的 {@code Map} 的读取工具。文本与缺失项都有默认值，
 * 配置缺项时插件仍能启动；数值字段的字符串写法若无法解析则直接失败，
 * 避免 {@code "600s"} 之类的笔误被静默当成默认值。
 */
public final class YamlMaps {

    private YamlMaps() {
    }

    /** 取子段；不存在或不是 map 时返回空 map。键统一转成字符串。 */
    public static Map<String, Object> section(Map<String, Object> parent, String key) {
        Objects.requireNonNull(parent, "parent");
        Object value = parent.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((childKey, childValue) -> result.put(String.valueOf(childKey), childValue));
        return result;
    }

    /** 原始字符串；缺失时为 {@code fallback}，不做裁剪。 */
    public static String rawString(Map<String, Object> section, String key, String fallback) {
        Object value = section.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /** 去掉首尾空白；空字符串视为缺失。 */
    public static String text(Map<String, Object> section, String key, String fallback) {
        String value = rawString(section, key, fallback).trim();
        return value.isEmpty() ? fallback : value;
    }

    /**
     * 读取整数值。
     *
     * <p>数字类型直接取 {@code long}；字符串尝试解析，无法解析时抛
     * {@link IllegalArgumentException}，让启动或 reload 失败并指出具体键名。
     * 值为 {@code null}、缺项或其它类型（如 Boolean）时仍回退 {@code fallback}。</p>
     *
     * @throws IllegalArgumentException 当值是字符串且不是合法整数时
     */
    public static long longValue(Map<String, Object> section, String key, long fallback) {
        Object value = section.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string) {
            try {
                return Long.parseLong(string.trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "config value '" + key + "' is not a number: " + value, exception);
            }
        }
        return fallback;
    }

    public static int intValue(Map<String, Object> section, String key, int fallback) {
        return (int) Math.min(Integer.MAX_VALUE, longValue(section, key, fallback));
    }
}