package me.hydwind.gate.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * LimboAPI 版本兼容判断与打包版本读取。
 *
 * <p>比较语义复用 {@link VersionCompat}：忽略 {@code -SNAPSHOT} 等后缀，缺失段按 0 处理，
 * 无法解析的版本视为不支持。{@code bundledVersion()} 依赖 {@code processResources}
 * 展开资源模板；在没有执行资源处理的环境里资源可能不存在，因此测试允许缺失，但资源存在时
 * 必须等于构建写入的最低版本。</p>
 */
class LimboCompatTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "1.2.0",
            "1.2.0-SNAPSHOT",
            "1.2.0+build.7",
            "1.2",
            "1.2.1",
            "1.3.0",
            "2.0.0",
            " 1.2.0 "
    })
    void acceptsLimboApiAtLeastOneTwoZero(String version) {
        assertTrue(LimboCompat.isSupported(version), version);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1.1.27",
            "1.1.9",
            "1.0.0",
            "0.9.9",
            "",
            "not-a-version",
            "-1.2.0"
    })
    void rejectsVersionsBelowTheMinimumOrUnparsable(String version) {
        assertFalse(LimboCompat.isSupported(version), version);
    }

    @Test
    void handlesNullAsUnsupported() {
        assertFalse(LimboCompat.isSupported(null));
    }

    @Test
    void bundledVersionIsEitherMissingOrTheBuildVersion() {
        Optional<String> bundled = LimboCompat.bundledVersion();
        // 只编译测试、未执行 processResources 时资源可能不存在；允许缺失，存在则必须正确。
        assertTrue(bundled.isEmpty() || LimboCompat.MINIMUM_LIMBO_VERSION.equals(bundled.get()),
                "bundled version: " + bundled);
        bundled.ifPresent(version ->
                assertTrue(LimboCompat.isSupported(version), "bundled version not supported: " + version));
    }
}