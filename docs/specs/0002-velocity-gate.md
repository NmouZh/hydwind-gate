## Problem Statement

VC 服是 **Velocity 代理**，不是 Paper 服务端。现有 `hydwind-gate` 是按 Paper 26.3 写的插件（Dialog API、Bukkit 事件、世界内冻结），无法加载到 Velocity，也无法提供原生对话框。用户要求以后**只有 VC 服这一个插件**，不做后端 Paper 插件。

因此需要一个单 Velocity 插件：未认证玩家连上代理后进入 LimboAPI 虚拟 limbo，看到网站链接，用 `/login <码>` 完成认证，再被送到主服。

## Solution

把 `hydwind-gate` 重写为 Velocity 插件：

- `LoginLimboRegisterEvent` 判断玩家是否需要认证；需要则用 LimboAPI 创建虚拟 limbo 并把玩家放进去。
- limbo 内通过 Adventure 发送聊天/标题/可点击网址，说明「打开 `/gate` 登录，然后 `/login <码>`」。
- `LimboSessionHandler#onChat` 解析 `/login <码>`（LimboAPI 会吞掉聊天和命令，不经过 Velocity CommandManager），调用网站 `/api/plugin/login/verify`。
- 成功：记录会话，`LimboPlayer#disconnect(主服)`；失败：提示原因；5 分钟未登录踢出。
- 网站 API 不变；复用 `WebsiteClient`、`KaomojiCode`、`LoginSessionStore`、`RestartPolicy`、`RestartTrigger`。
- 删除 Paper 专用代码，旧 spec `0001-login-gate` 标记 superseded。

## User Stories

1. 作为未认证玩家，我连上 Velocity 后进入 limbo，看到登录说明而不是被直接踢出。
2. 作为玩家，我想在 limbo 聊天栏看到可点击的网站链接，以便不用手打地址。
3. 作为玩家，我想用 `/login <码>` 在 limbo 完成认证，以便不用离开客户端。
4. 作为玩家，我想在输错码时看到明确原因和剩余尝试次数。
5. 作为玩家，我想认证成功后自动被送到主服，以便直接开始游戏。
6. 作为玩家，我想 5 分钟未登录被踢出，以便代理不无限占用连接。
7. 作为短时掉线的玩家，我想同 UUID + 同 IP 在 5 分钟内重连免登录。
8. 作为已注册玩家，我想直接进主服，不再看到 limbo。
9. 作为未注册玩家，我想被告知去 QQ 群私聊机器人注册。
10. 作为账号名与游戏 ID 不一致的玩家，我想看到「此账号对应游戏 ID 为 X」。
11. 作为玩家，我想在提示里看到中文别名。
12. 作为系统，我不想未认证玩家能连到任何后端服务器。
13. 作为系统，我想网站不可用时 fail-closed，保持在 limbo 并提示稍后再试。
14. 作为管理员，我想用 `/hydwind status` 看 limbo 中的玩家、已认证会话和网站健康。
15. 作为管理员，我想用 `/hydwind reload` 热重载配置。
16. 作为管理员，我想用 `/hydwind approve <玩家>` 手动放行。
17. 作为管理员，我想用 `/hydwind kick <玩家>` 踢出。
18. 作为管理员，我想在网站不健康、触发重启、达到冷却上限时收到日志告警。
19. 作为运维，我想插件只依赖一个 LimboAPI 插件，不需要后端 Paper 插件。
20. 作为运维，我想插件启动时检查 Velocity/协议版本，不兼容时明确报错。
21. 作为运维，我想插件声明 `limboapi` 依赖，缺失时加载失败并给出清晰原因。
22. 作为运维，我想主服名称可配置，认证后默认去主服。
23. 作为运维，我想网站 base URL、令牌、超时、limbo 世界都可配置。
24. 作为开发者，我想复用现有网站客户端、码逻辑、会话状态机和重启策略。
25. 作为开发者，我想删除 Paper 专用类，避免两套平台代码并存。
26. 作为开发者，我想 CI 产出可部署 jar 并保留测试。
27. 作为玩家，我想 `/login` 在客户端有 Tab 补全，以便知道命令存在。
28. 作为玩家，我想点击网址后在浏览器登录 `/gate` 并复制码，再回游戏输入。
29. 作为系统，我想 limbo 内的命令由 `onChat` 解析，不依赖 Velocity CommandManager。
30. 作为系统，我想登录阶段不发送 PLAY 状态的包，等 `onSpawn` 后再发提示。
31. 作为系统，我想聊天/命令长度不超过 256，登录码远低于上限。
32. 作为系统，我想认证会话只存内存，Velocity 重启后全部重新登录。
33. 作为系统，我想可点击 URL 在 limbo 中可用（Adventure `ClickEvent.openUrl`），并做一次真实客户端验证。
34. 作为系统，我想不开放任何额外网络端口。
35. 作为运维，我想插件 jar 通过 GitHub Actions 产出，不在本机构建。

## Implementation Decisions

### 平台与版本

- Velocity 4.1.2+（最新 4.1.x）、Java 25、Minecraft 26.3（协议 777）。
- 启动时检查 `proxy.getVersion().getVersion()` 至少 4.1.2，并确认 `limboapi` 插件存在；不兼容时记录明确错误并关闭代理（Velocity 没有运行时禁用插件的 API），不静默继续。
- LimboAPI 1.2.0；与 Velocity 版本强绑定，升级 Velocity 前必须同步升级 LimboAPI。
- 插件元数据：`@Plugin(id = "hydwind-gate", dependencies = { @Dependency(id = "limboapi") })`，生成 `velocity-plugin.json`。
- 只做一个插件；LimboAPI 作为运行时库插件单独安装。

### LimboAPI 依赖

- Elytrium Maven 目前只有 `1.1.27` 和 `2.0.0-SNAPSHOT`，`1.2.0` 返回 404；JitPack 也返回 404。
- 1.2.0 的官方发布物是 GitHub Release 的 `limboapi-1.2.0.jar`。
- 构建时用 Gradle 任务下载该 jar 到 `build/limboapi/`，以 `compileOnly(files(...))` 引用；不要 shade。
- 下载后用 GitHub Release API 公布的 SHA-256（`25582ebf5567fec1c20a03ad2c6e01fa05911a76749c67e7b0192acf508f7d31`）校验，不匹配时构建失败。
- 固定 URL 与版本；若后续 Elytrium Maven 发布 1.2.0，切换为 `net.elytrium.limboapi:api:1.2.0`。
- 运行期必须安装 `limboapi-1.2.0.jar`，否则插件因依赖缺失不加载。

### 登录流程

1. `PreLoginEvent`：强制离线模式（`forceOfflineMode()`），记录用户名/UUID。
2. `LoginLimboRegisterEvent`：
   - 若 `LoginSessionStore` 中有有效会话（同 UUID + 同 IP，5 分钟宽限），`LimboFactory#passLoginLimbo(player)`，直接走正常登录。
   - 否则 `event.addOnJoinCallback(() -> spawnInLimbo(player))`。
3. `spawnInLimbo`：`createVirtualWorld` + `createLimbo` + `limbo.registerCommand(new LimboCommandMeta(List.of("login")))` + `limbo.spawnPlayer(player, sessionHandler)`。
4. `LimboSessionHandler#onSpawn`：发送网址、说明和当前状态；用 `LimboPlayer#getScheduledExecutor()` 每 10 秒重复提示；启动 5 分钟超时。
5. `onChat(String)`：命令带前导 `/`；解析 `/login <code>`；提交异步 verify；成功后 `limboPlayer.disconnect(mainServer)`；失败回消息；超时踢出。为兼容聊天直发，不带前导 `/` 的 `login` 也识别（`hydwind` 同理）。
6. `ServerPreConnectEvent`：没有有效会话的玩家禁止连接任何后端。
7. `DisconnectEvent`：清理会话（保留 5 分钟宽限记录）。

### 会话与失败策略

- 会话按 UUID 存，记录 IP、认证时间；断线宽限 5 分钟（UUID + IP）。
- 网站不可达：fail-closed，保留在 limbo，提示「认证服务暂时不可用」，触发受控重启；超时踢出。
- 429 限流：显示「尝试过多，请 X 秒后再试」；响应带 `attemptsRemaining` 时追加「剩余 N 次」，不触发重启。
- 401/令牌错误：日志告警，按服务异常处理，不触发重启。
- 403（`FORBIDDEN`）与 404（`NOT_FOUND`）是明确失败结果：显示对应文案，不触发健康检查或受控重启；其余无法理解的响应仍按不可达处理。
- `invalid_code` 文案提示没有账号的玩家到 QQ 群私聊机器人注册游戏 ID。

### 网站接口

- 复用现有 `POST /api/plugin/login/verify`，请求 `{ code, gameId, uuid }`，Bearer `PLUGIN_TOKEN`。
- `GET /api/health` 用于健康检查与受控重启。
- 网站侧不需要改动。

### 配置

- `config.yml`（SnakeYAML，`@DataDirectory`）：
  - `website.base-url`、`website.token`（支持 `HYDWIND_PLUGIN_TOKEN` 环境变量覆盖）
  - `auth.login-timeout-seconds`、`auth.session-grace-seconds`、`auth.prompt-interval-seconds`
  - `limbo.world`、`limbo.spawn`
  - `server.main`（认证后转服目标）
  - `restart.request-path`、`restart.cooldown-seconds`、`restart.max-per-hour`
  - `admin.permission`（默认 `hydwind.admin`）
  - `messages`（网址、提示、错误、超时）
- `/hydwind reload` 重载，保留进行中的会话与 `RestartPolicy` 计数。`auth.session-grace-seconds` 立即生效；`auth.prompt-interval-seconds` 与 `auth.login-timeout-seconds` 只对之后进入 limbo 的会话生效（已 spawn 的会话定时器不重排）。

### 命令与权限

- Velocity `CommandManager` 注册 `/hydwind`（已连后端的玩家）
- limbo 内 `onChat` 也解析 `/hydwind`，仅对 `hydwind.admin` 生效；不带前导 `/` 的 `hydwind` 同样识别（兼容行为）
- 子命令：`status`、`reload`、`approve <player>`、`kick <player>`
- `status` 除人数外列出 limbo 玩家名，最多 20 个，更多时显示「等 N 人」
- 权限由外部权限插件授予；未授权时返回无权限提示

### 代码迁移

- 删除 Paper/Bukkit 专用：`JoinListener`、`FreezeController`、`DialogFactory`、`DialogListener`、`LoginCommand`、`paper-plugin.yml`、Bukkit/Paper 依赖、Dialog/冻结配置。
- 保留并移植：`WebsiteClient`、`KaomojiCode`、`LoginSessionStore`、`RestartPolicy`、`RestartTrigger`、消息与配置结构。
- 主类改为 Velocity `@Plugin`；用 Velocity `ProxyServer`、`Scheduler`。
- 旧 spec `docs/specs/0001-login-gate.md` 标记 superseded；新增 ADR `docs/adr/0001-velocity-over-paper.md`。

### 构建

- Gradle Kotlin DSL，Java 25 toolchain。
- Velocity API：`com.velocitypowered:velocity-api:4.1.2-SNAPSHOT`，仓库 `https://repo.papermc.io/repository/maven-public/`。
- SnakeYAML 与 Gson 用 `implementation` + Shadow 插件打包进插件 jar；LimboAPI 与 Velocity API 用 `compileOnly`。
- CI 运行 `gradle build` 并上传 jar artifact；本地不跑构建。

## Testing Decisions

- **最高接缝（HTTP 契约）**：`POST /api/plugin/login/verify`、`GET /api/health`。`WebsiteClient` 对本地 `com.sun.net.httpserver.HttpServer` 桩测试 200/401/403/404/409/429/超时/连接拒绝。
- **纯逻辑**：`KaomojiCode` 归一化、`LoginSessionStore` 状态与宽限（含 reload 后的宽限变更）、`RestartPolicy` 冷却与上限、配置解析与重载、Velocity 版本比较、共享命令行/模板工具。JUnit 5。
- **Limbo 集成**：不写自动化单元测试，用真实 Velocity 4.1.2 + LimboAPI 1.2.0 + 26.3 客户端跑手工清单。
- **手工清单**：进 limbo、看到可点击 URL、`/login` 成功/失败/超时、转主服、断线宽限、网站不可达 fail-closed、受控重启文件、`/hydwind` 四个子命令、Tab 补全。
- **CI**：`gradle build` + 测试 + artifact；不在本机执行构建/测试。
- 只测外部行为，不断言私有实现。

## Out of Scope

- 原生 Dialog（Velocity 没有该 API）。
- 后端 Paper/Spigot 插件。
- 多个代理/多网络。
- 权限插件本身的实现（只调用 `hasPermission`）。
- 网站注册流程改动。
- 网站 TLS（沿用当前 HTTP 配置）。
- 资源包、limbo 世界美化。
- 正版验证（继续离线模式）。

## Further Notes

- LimboAPI 1.2.0 未发布到 Maven，构建依赖 GitHub Release jar；这是当前最大的供应链风险。
- LimboAPI 插件本体是 AGPLv3，API 模块是 MIT；分发时注意区别。
- 协议硬检查：1.2.0 在协议 777（26.3）上工作；低于 777 会直接关闭代理，不是降级。
- limbo 中的聊天和命令都被 LimboAPI 吞掉，统一进 `onChat`；`CommandManager` 不参与。
- 登录阶段（LOGIN 状态）不能发消息，必须等 `onSpawn`（PLAY 状态）。
- 可点击 URL 用 Adventure `ClickEvent.openUrl`；没有找到 limbo 内官方示例，需真实客户端验证。
- 聊天/命令上限 256 字符，登录码远低于上限。
- 现有网站 `/gate`、`/api/plugin/login/verify` 全部保留，插件重写不影响网站。
- Paper 实现保留在 git 历史中；当前 main 的 Paper 代码在 `feat/velocity-gate` 合并时删除。