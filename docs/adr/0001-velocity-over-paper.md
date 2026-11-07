# ADR-0001: 用 Velocity 插件而不是 Paper 后端插件

## Status

Accepted — 2026-11-07

## Context

VC 服是 **Velocity 代理**，不是 Paper 服务端。之前的 `hydwind-gate` 按 Paper 26.3 实现，使用 Paper Dialog API、Bukkit 事件和世界内冻结，无法在 Velocity 加载。

用户要求以后**只有 VC 服这一个插件**，不做后端 Paper 插件。原生的 `/dialog` 输入框是 Minecraft 1.21.6+ 的**后端服务端**能力，Velocity 代理本身没有 Dialog API。

## Decision

把 HydwindGate 实现为**一个 Velocity 插件**，使用 **LimboAPI** 创建虚拟 limbo：

- 未认证玩家连上代理后进入虚拟 limbo。
- 聊天栏显示网站链接和说明。
- 玩家在网站 `/gate` 登录拿到登录码，在 limbo 输入 `/login <码>`。
- 插件调用网站校验接口，成功后把玩家转到主服。

不接受「Velocity + 后端 Paper 插件」的两插件方案，即使它能提供原生对话框。

## Consequences

- **失去原生对话框**：交互变成聊天提示 + `/login` 命令；这是单 Velocity 插件的硬限制。
- **依赖 LimboAPI**：运行时必须安装 `limboapi-1.2.0.jar`；它与 Velocity 版本强绑定，升级代理要同步升级。
- **版本门槛**：LimboAPI 1.2.0 要求 Java 25、Velocity 4.1.2+、协议 777（Minecraft 26.3）。
- **供应链风险**：1.2.0 目前只在 GitHub Release，Elytrium Maven 未发布；构建需要下载并固定该 jar。
- **代码迁移**：Paper 专用类删除，网站客户端、码逻辑、会话状态机、重启策略保留。
- **网站不变**：`/gate` 和 `/api/plugin/login/verify` 继续使用。

## Alternatives considered

1. **Velocity + 后端 Paper 插件**：能提供原生对话框，但需要两个插件，违背「只有一个插件」。
2. **纯 Velocity 拒绝连接 + 浏览器登录后重连**：最简单，但玩家必须先断开、登录、再重连，体验差。
3. **VeloAuth 式嵌入式 limbo 服务端**：不依赖 LimboAPI，但要维护一个嵌入式 MC 服务端，复杂度高。

选择 LimboAPI 方案，在「单插件」和「游戏内交互」之间取平衡。