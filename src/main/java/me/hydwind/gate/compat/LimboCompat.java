package me.hydwind.gate.compat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

/**
 * LimboAPI 版本兼容判断与构建期版本读取。
 *
 * <p>插件只支持 LimboAPI {@value #MINIMUM_LIMBO_VERSION} 及更新版本；版本比较复用
 * {@link VersionCompat#isAtLeast(String, String)}，忽略 {@code -SNAPSHOT} 等后缀，
 * 无法解析的版本一律视为不兼容。</p>
 *
 * <p>构建时把 {@code limboApiVersion} 展开进 jar 内的 {@code /limbo-version.properties}，
 * 供启动检查把“构建所用版本”与“运行时实际安装版本”对齐。</p>
 */
public final class LimboCompat {

    /** HydwindGate 支持的最低 LimboAPI 版本。 */
    public static final String MINIMUM_LIMBO_VERSION = "1.2.0";

    /** 构建期写入的版本资源；由 {@code processResources} 展开模板生成。 */
    private static final String VERSION_RESOURCE = "/limbo-version.properties";
    private static final String VERSION_KEY = "version";

    private LimboCompat() {
    }

    /** 判断运行时 LimboAPI 版本是否满足最低要求；null、空串或无法解析时返回 false。 */
    public static boolean isSupported(String actualVersion) {
        return VersionCompat.isAtLeast(actualVersion, MINIMUM_LIMBO_VERSION);
    }

    /**
     * 读取构建时写入 jar 的 LimboAPI 版本。
     *
     * <p>资源缺失、读取失败、缺少 {@code version} 键或值为空白时返回
     * {@link Optional#empty()}；调用方应把它当作“无法确认构建版本”，
     * 而不是据此拒绝启动。</p>
     */
    public static Optional<String> bundledVersion() {
        try (InputStream input = LimboCompat.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (input == null) {
                return Optional.empty();
            }
            Properties properties = new Properties();
            properties.load(input);
            String version = properties.getProperty(VERSION_KEY);
            if (version == null || version.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(version.strip());
        } catch (IOException | IllegalArgumentException exception) {
            // 资源不可读或内容含非法转义时不抛出：版本信息缺失不应中断启动检查。
            return Optional.empty();
        }
    }
}