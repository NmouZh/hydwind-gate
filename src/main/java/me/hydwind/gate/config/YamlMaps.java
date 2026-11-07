package me.hydwind.gate.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * SnakeYAML 解析出的 {@code Map} 的读取工具。所有 getter 都带默认值，
 * 配置缺项时插件仍能启动。
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

    public static long longValue(Map<String, Object> section, String key, long fallback) {
        Object value = section.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string) {
            try {
                return Long.parseLong(string.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static int intValue(Map<String, Object> section, String key, int fallback) {
        return (int) Math.min(Integer.MAX_VALUE, longValue(section, key, fallback));
    }
}