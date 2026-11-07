package me.hydwind.gate.web;

import java.time.Clock;
import java.util.logging.Logger;
import me.hydwind.gate.config.RestartSettings;

/**
 * 网站不可达时的受控重启触发。
 *
 * <p>只写一个请求文件，真正的重启由外部 systemd path unit 完成。任何写入失败
 * 都只记录告警，绝不影响登录流程。
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
        this.websiteClient = websiteClient;
        this.policy = policy;
        this.settings = settings;
        this.clock = clock;
        this.logger = logger;
    }

    /** 收到 UNREACHABLE 时调用：探活失败且策略允许时写入重启请求文件。 */
    public void onUnreachable() {
        // 实现见后续提交。
    }
}