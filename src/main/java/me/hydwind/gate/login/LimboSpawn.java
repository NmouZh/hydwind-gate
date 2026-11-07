package me.hydwind.gate.login;

/**
 * {@code limbo.spawn} 配置的解析结果。
 *
 * @param yaw   水平朝向，默认 0（南）
 * @param pitch 俯仰角，默认 0
 */
public record LimboSpawn(double x, double y, double z, float yaw, float pitch) {

    public static final LimboSpawn DEFAULT = new LimboSpawn(0.0, 64.0, 0.0, 0.0f, 0.0f);

    /** 解析 {@code "x, y, z"}；格式错误一律回退默认出生点，绝不让插件启动失败。 */
    public static LimboSpawn parse(String configured) {
        if (configured == null) {
            return DEFAULT;
        }
        String[] parts = configured.split(",");
        if (parts.length < 3) {
            return DEFAULT;
        }
        try {
            double x = Double.parseDouble(parts[0].trim());
            double y = Double.parseDouble(parts[1].trim());
            double z = Double.parseDouble(parts[2].trim());
            float yaw = parts.length >= 5 ? Float.parseFloat(parts[3].trim()) : DEFAULT.yaw();
            float pitch = parts.length >= 5 ? Float.parseFloat(parts[4].trim()) : DEFAULT.pitch();
            if (!finite(x) || !finite(y) || !finite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                return DEFAULT;
            }
            return new LimboSpawn(x, y, z, yaw, pitch);
        } catch (NumberFormatException ignored) {
            return DEFAULT;
        }
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }
}