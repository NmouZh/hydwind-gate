package me.hydwind.gate.compat;

/**
 * Velocity 版本兼容判断。
 *
 * <p>只比较点分数字部分，忽略 {@code -SNAPSHOT}、{@code +build} 等后缀；缺失的段按 0 处理。
 * 无法解析的版本一律视为不兼容，避免在未知版本上放行未认证玩家。</p>
 */
public final class VersionCompat {

    /** HydwindGate 与 LimboAPI 1.2.0 支持的最低 Velocity 版本。 */
    public static final String MINIMUM_VELOCITY_VERSION = "4.1.2";

    private VersionCompat() {
    }

    public static boolean isSupportedVelocity(String version) {
        return isAtLeast(version, MINIMUM_VELOCITY_VERSION);
    }

    /** 判断 {@code actual} 是否大于或等于 {@code minimum}；任一方无法解析时返回 false。 */
    public static boolean isAtLeast(String actual, String minimum) {
        int[] actualParts = parse(actual);
        int[] minimumParts = parse(minimum);
        if (actualParts == null || minimumParts == null) {
            return false;
        }
        int length = Math.max(actualParts.length, minimumParts.length);
        for (int i = 0; i < length; i++) {
            int left = i < actualParts.length ? actualParts[i] : 0;
            int right = i < minimumParts.length ? minimumParts[i] : 0;
            if (left != right) {
                return left > right;
            }
        }
        return true;
    }

    private static int[] parse(String version) {
        if (version == null) {
            return null;
        }
        String trimmed = version.strip();
        if (trimmed.isEmpty()) {
            return null;
        }

        int[] parts = new int[4];
        int count = 0;
        int index = 0;
        while (index < trimmed.length()) {
            int start = index;
            while (index < trimmed.length() && Character.isDigit(trimmed.charAt(index))) {
                index++;
            }
            if (start == index) {
                break;
            }
            long value;
            try {
                value = Long.parseLong(trimmed.substring(start, index));
            } catch (NumberFormatException overflow) {
                value = Long.MAX_VALUE;
            }
            if (count == parts.length) {
                int[] grown = new int[parts.length * 2];
                System.arraycopy(parts, 0, grown, 0, parts.length);
                parts = grown;
            }
            parts[count++] = (int) Math.min(Integer.MAX_VALUE, value);
            if (index < trimmed.length() && trimmed.charAt(index) == '.') {
                index++;
            } else {
                break;
            }
        }
        if (count == 0) {
            return null;
        }
        int[] result = new int[count];
        System.arraycopy(parts, 0, result, 0, count);
        return result;
    }
}