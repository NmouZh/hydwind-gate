package me.hydwind.gate.web;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import me.hydwind.gate.config.RestartSettings;
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

    /** 探活 single-flight：网站宕机时避免每个未认证玩家各打一次健康检查。 */
    private final AtomicBoolean probeInFlight = new AtomicBoolean();
    /** 探活最短间隔：冷却期内的重复失败直接跳过，不刷日志也不打网站。 */
    private static final Duration MIN_PROBE_INTERVAL = Duration.ofSeconds(10);
    private volatile Instant lastProbeAt = Instant.MIN;

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

    /**
     * 收到 UNREACHABLE 时调用：只有探活确认是传输层失败且策略允许时才写重启请求文件。
     *
     * <p>网站有响应但契约不匹配（{@link HealthResult#unhealthyContract()}）时只记录
     * WARN：重启网站无法修复接口不兼容，避免无意义的重启风暴。</p>
     */
    public void onUnreachable() {
        Instant now = clock.instant();
        if (!probeInFlight.compareAndSet(false, true)) {
            logger.debug("Restart trigger already probing; skipping duplicate request.");
            return;
        }
        try {
            Instant last = lastProbeAt;
            if (last != Instant.MIN && now.isBefore(last.plus(MIN_PROBE_INTERVAL))) {
                logger.debug("Restart trigger probe throttled; last probe at {}", last);
                return;
            }
            lastProbeAt = now;

            HealthResult health;
            try {
                health = websiteClient.health();
            } catch (RuntimeException exception) {
                // 探活自身异常不算一次有效探测：不占用限频窗口，下一次失败可以立刻重试。
                lastProbeAt = Instant.MIN;
                logger.warn("网站健康检查异常，跳过重启: {}", exception.toString());
                return;
            }

            if (health == null) {
                logger.warn("网站健康检查返回 null，跳过重启。");
                return;
            }
            if (health.healthy()) {
                logger.info("收到登录校验不可达，但网站健康检查通过，跳过重启。");
                return;
            }
            if (!health.transportFailure()) {
                logger.warn("网站健康检查失败但属于契约不匹配（网站有响应），跳过重启。");
                return;
            }

            if (!policy.tryAcquire(now)) {
                logger.warn("网站不可达，但重启请求被策略拒绝（冷却中或已达到每小时上限）；请求文件: {}",
                        settings.requestFile());
                return;
            }

            Path requestFile = settings.requestFile().toAbsolutePath().normalize();
            try {
                writeRequestFile(requestFile, content(now));
                logger.warn("网站不可达，已写入重启请求文件: {}", requestFile);
            } catch (IOException | RuntimeException exception) {
                // 文件系统异常、权限不足等只告警；重启额度仍视为已消耗，避免异常路径造成重启风暴。
                logger.warn("网站不可达，但写入重启请求文件失败: {} ({})",
                        requestFile, exception.toString());
            }
        } finally {
            probeInFlight.set(false);
        }
    }

    /**
     * 先写同目录临时文件，再原子替换目标。
     *
     * <p>不直接往目标路径写：该目录对网站服务用户也可写，直接写会跟随预先
     * 放置的符号链接，把内容截断到 `velocity.toml` 或插件 jar 上。临时文件
     * 由 {@link Files#createTempFile} 独占创建，替换时链接本身被替换掉，
     * 目标文件不会被跟随。</p>
     */
    private static void writeRequestFile(Path requestFile, String content) throws IOException {
        Path parent = requestFile.getParent();
        if (parent == null) {
            throw new IOException("restart request path has no parent: " + requestFile);
        }
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, ".restart-request-", ".tmp");
        try {
            // createTempFile 已经创建了文件；这里只能截断写入，不能再 CREATE_NEW。
            // NOFOLLOW_LINKS 防止临时文件在创建与打开之间被替换成符号链接。
            try (SeekableByteChannel channel = Files.newByteChannel(tmp,
                    Set.of(StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
                    LinkOption.NOFOLLOW_LINKS)) {
                channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
            }
            Files.move(tmp, requestFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        // 目标如果是符号链接，REPLACE_EXISTING 已经把链接替换成普通文件；
        // 断言一次，避免 Files.move 在某些文件系统上退化成跟随语义。
        if (Files.isSymbolicLink(requestFile)) {
            throw new IOException("restart request path is a symbolic link: " + requestFile);
        }
        if (!Files.isRegularFile(requestFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("restart request path is not a regular file: " + requestFile);
        }
    }

    private static String content(Instant now) {
        return "reason=website-unreachable" + System.lineSeparator()
                + "requested-at=" + now + System.lineSeparator();
    }
}