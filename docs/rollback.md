# 回滚与版本策略

本文说明 HydwindGate 发布后如何回退。插件本身不持久化数据，回滚的复杂度主要在于
配置严格化、LimboAPI 版本绑定，以及玩家需要重新登录。

## 版本命名

- 当前正式发布：**`0.1.0`**（tag `v0.1.0`，GitHub Release）。
- 在 `v0.1.0` 之前，开发构建也使用过 `0.1.0`，但它们没有 tag / Release，
  不应作为回滚基线。回滚目标请使用 GitHub Releases 中的 jar。
- 从 `v0.1.0` 起，每次发布递增版本号，jar 文件名带版本号
  （`HydwindGate-<version>.jar`），按文件名区分新旧。

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
   - 复制当前 jar 与 `plugins/hydwind-gate/config.yml` 到备份目录；
   - 复制最近一段控制台日志。
3. **替换 jar**：把目标版本的 jar 放回 `plugins/`，移除当前版本的 jar。
   同一目录只保留一个 HydwindGate jar；插件 id 相同，放两个会加载失败或行为不确定。
4. **配置**：`0.1.0` 不认识未来版本新增的键，会忽略它们；已知键保持兼容。
   反向升级（从旧开发构建到 `0.1.0`）时注意：`0.1.0` 对非法数值、空 base-url、
   无 host 的 URL 更严格，含脏值的旧配置会拒绝启动；旧开发构建则会静默使用默认值。
5. **LimboAPI / Velocity**：如果这次发布同时升级过它们，必须一起回退到配套版本；
   只回退插件、不回退 LimboAPI 可能仍然启动失败。
6. **启动代理**，确认控制台出现 `HydwindGate <version> enabled`。
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
- 不要把不同版本的 HydwindGate jar 同时放进 `plugins/`。