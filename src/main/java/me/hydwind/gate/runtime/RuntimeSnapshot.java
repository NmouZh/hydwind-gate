package me.hydwind.gate.runtime;

import java.util.Objects;
import me.hydwind.gate.config.PluginConfig;
import me.hydwind.gate.ui.Messages;
import me.hydwind.gate.web.RestartTrigger;
import me.hydwind.gate.web.WebsiteClient;

/**
 * 一代运行时依赖的不可变快照。
 *
 * <p>配置热重载时构建完整的新代际并整代替换，旧代际在发布后关闭。所有读取方应通过
 * {@link RuntimeHolder#current()} 取一次快照，并在本次操作内复用，避免同一次操作里
 * 混用不同代际的配置、文案与网站客户端。</p>
 *
 * @param config         本代际使用的插件配置
 * @param messages       本代际使用的玩家文案
 * @param websiteClient  本代际使用的网站客户端
 * @param restartTrigger 本代际使用的受控重启触发器
 */
public record RuntimeSnapshot(
        PluginConfig config,
        Messages messages,
        WebsiteClient websiteClient,
        RestartTrigger restartTrigger) implements AutoCloseable {

    public RuntimeSnapshot {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(websiteClient, "websiteClient");
        Objects.requireNonNull(restartTrigger, "restartTrigger");
    }

    /**
     * 关闭本代际占用的资源。网站客户端若实现了 {@link AutoCloseable}，关闭其内部的
     * HTTP 连接池；关闭异常只吞掉，不影响代际切换，也不向调用方抛出。
     */
    @Override
    public void close() {
        if (websiteClient instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 旧代际已不再被使用；关闭失败不影响新代际，代理重启时会释放剩余资源。
            }
        }
    }
}