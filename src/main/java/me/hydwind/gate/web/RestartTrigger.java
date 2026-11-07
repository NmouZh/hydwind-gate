package me.hydwind.gate.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.logging.Logger;
import me.hydwind.gate.config.RestartSettings;

/**
 * 网站不可达时的受控重启触发。
 *
 * <p>只写一个请求文件，真正的重启由外部 systemd path unit 完成。准入策略
 * （冷却与每小时上限）由 {@link RestartPolicy} 负责；任何探活或写入失败都只
 * 记录告警，绝不向登录流程抛出异常。
 */
public final class RestartTrigger {

    private final WebsiteClient websiteClient;
    private final RestartPolicy policy;
    private final RestartSettings settings;
    private final Clock clock;
    private final Logger logger;

    public RestartTrigger(WebsiteClient websiteClient,
                          RestartPolicy policy,
                          RestartSettings settings,
                          Clock clock,
                          Logger logger) {
        this.websiteClient = Objects.requireNonNull(websiteClient, "websiteClient");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** 收到 UNREACHABLE 时调用：探活失败且策略允许时写入重启请求文件。 */
    public void onUnreachable() {
        HealthResult health;
        try {
            health = websiteClient.health();
        } catch (RuntimeException exception) {
            logger.warning("网站健康检查异常，按不可用处理: " + exception);
            health = HealthResult.unhealthy();
        }

        if (health == null || health.healthy()) {
            logger.info("收到登录校验不可达，但网站健康检查通过，跳过重启。");
            return;
        }

        Instant now = clock.instant();
        if (!policy.tryAcquire(now)) {
            logger.warning("网站不可达，但重启请求被策略拒绝（冷却中或已达到每小时上限）；"
                    + "请求文件: " + settings.requestFile());
            return;
        }

        Path requestFile = settings.requestFile();
        try {
            Path parent = requestFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(requestFile, content(now), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            logger.warning("网站不可达，已写入重启请求文件: " + requestFile);
        } catch (IOException | RuntimeException exception) {
            // 文件系统异常、权限不足等只告警；重启额度仍视为已消耗，避免异常路径造成重启风暴。
            logger.warning("网站不可达，但写入重启请求文件失败: " + requestFile + " (" + exception + ")");
        }
    }

    private static String content(Instant now) {
        return "reason=website-unreachable" + System.lineSeparator()
                + "requested-at=" + now + System.lineSeparator();
    }
}