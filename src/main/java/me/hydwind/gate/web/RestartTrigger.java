package me.hydwind.gate.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import me.hydwind.gate.config.RestartSettings;
import me.hydwind.gate.util.Throwables;
import org.slf4j.Logger;

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
        Path requestFile = settings.requestFile();
        Path fullPath = requestFile.toAbsolutePath().normalize();
        logger.warn("网站校验不可达，开始受控重启评估：原因=website-unreachable，请求文件={}", fullPath);

        HealthResult health;
        try {
            health = websiteClient.health();
        } catch (RuntimeException exception) {
            logger.warn("网站健康检查异常，按不可用处理：异常={}", Throwables.describe(exception));
            health = HealthResult.unhealthy();
        }

        if (health == null || health.healthy()) {
            logger.info("受控重启评估：健康检查={}，跳过重启。",
                    health == null ? "无结果" : "通过（uptime=" + health.uptimeSeconds() + "s）");
            return;
        }
        logger.warn("受控重启评估：健康检查=失败（uptime={}s）。", health.uptimeSeconds());

        Instant now = clock.instant();
        int remaining = policy.remaining(now);
        if (!policy.tryAcquire(now)) {
            logger.warn("网站不可达，但重启请求被策略拒绝（冷却中或已达到每小时上限）；"
                            + "触发前剩余额度={}，最早可触发={}，请求文件={}",
                    remaining,
                    policy.nextAllowed(now).map(Instant::toString).orElse("无"),
                    fullPath);
            return;
        }
        logger.info("重启策略允许本次请求（触发前剩余额度={}），准备写入：{}", remaining, fullPath);

        try {
            Path parent = fullPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String fileContent = content(now);
            Files.writeString(fullPath, fileContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            logger.warn("网站不可达，已写入重启请求文件：路径={}，原因={}，请求时间={}",
                    fullPath, "website-unreachable", now);
        } catch (IOException | RuntimeException exception) {
            // 文件系统异常、权限不足等只告警；重启额度仍视为已消耗，避免异常路径造成重启风暴。
            logger.warn("网站不可达，但写入重启请求文件失败：路径={}，异常={}",
                    fullPath, Throwables.describe(exception));
        }
    }

    private static String content(Instant now) {
        return "reason=website-unreachable" + System.lineSeparator()
                + "requested-at=" + now + System.lineSeparator();
    }
}