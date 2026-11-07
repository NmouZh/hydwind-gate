# 回滚与版本策略

本文说明 HydwindGate 发布后如何回退。插件本身不持久化数据，回滚的复杂度主要在于
配置严格化、LimboAPI 版本绑定，以及玩家需要重新登录。

## 版本命名

- 当前发布版本：`0.2.0`（架构深化版）。
- 上一版：`0.1.0`。
- 两个版本的 jar 文件名不同（`HydwindGate-0.2.0.jar` / `HydwindGate-0.1.0.jar`），
  不要覆盖旧 jar；在 `plugins/` 里只保留一个，把另一个移出目录备份。
- 每次发布前给 git 打 tag（例如 `v0.2.0`），CI artifact 与 tag 对应。

## 回滚前先判断

| 现象 | 优先动作 |
|---|---|
| 启动日志报配置校验失败（base-url、超时、数值字符串） | 先修配置，通常不需要回滚 |
| 启动日志报 LimboAPI 版本过低 / Velocity 版本过低 | 回滚 LimboAPI/Velocity 到配套版本，或升级插件 |
| 玩家卡在 limbo、`/login` 无反应 | 保留现场日志与 jar，按本文回滚 |
| 网站验证持续 401 / 契约不兼容 | 先核对 `HYDWIND_PLUGIN_TOKEN` 与网站版本，通常不需要回滚 |

## 回滚步骤

1. **停止代理**（Velocity），避免在运行中替换 jar。
2. **保留现场**：
   - 复制当前 `plugins/HydwindGate-0.2.0.jar` 与 `plugins/hydwind-gate/config.yml` 到备份目录；
   - 复制最近一段控制台日志。
3. **替换 jar**：把 `HydwindGate-0.1.0.jar` 放回 `plugins/`，移除 `0.2.0` 的 jar。
4. **配置**：`0.2.0` 的配置键与 `0.1.0` 完全兼容，可以直接沿用。
   注意方向相反时不成立：`0.2.0` 对非法数值、空 base-url、无 host 的 URL 更严格；
   从旧版升级时若配置含脏值，`0.2.0` 会拒绝启动，而回退到 `0.1.0` 会静默使用默认值。
5. **LimboAPI / Velocity**：如果这次发布同时升级过它们，必须一起回退到与
   `0.1.0` 配套的版本；只回退插件、不回退 LimboAPI 可能仍然启动失败。
6. **启动代理**，确认控制台出现 `HydwindGate 0.1.0 enabled`。
7. **通知玩家重新登录**：会话与断线宽限只在内存中，回滚重启后所有玩家都需要
   重新走网站 `/gate` 登录。这是预期行为，不是故障。

## 回滚后的验证

- 控制台无异常堆栈；出现 `HydwindGate ... enabled`。
- 未认证玩家进入 limbo，`/login` 能成功转服。
- `/hydwind status` 能显示 limbo 人数与网站健康。
- 若配置了受控重启，确认 `restart.request-path` 的 systemd unit 仍指向
  `hydwind-web.service`（不要指向 velocity）。

## 不建议的回滚方式

- 不要在两个版本之间反复热替换 jar：Velocity 不支持运行时替换插件，必须重启代理。
- 不要为了保留会话而只回滚部分组件；会话格式不跨版本兼容，且没有持久化。
- 不要把 `0.1.0` 与 `0.2.0` 的 jar 同时放进 `plugins/`：插件 id 相同，会加载失败或行为不确定。