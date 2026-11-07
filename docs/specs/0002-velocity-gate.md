## Problem Statement

VC 服是 **Velocity 代理**，不是 Paper 服务端。现有 `hydwind-gate` 是按 Paper 26.3 写的插件（Dialog API、Bukkit 事件、世界内冻结），无法加载到 Velocity，也无法提供原生对话框。用户要求以后**只有 VC 服这一个插件**，不做后端 Paper 插件。

因此需要一个单 Velocity 插件：未认证玩家连上代理后进入 LimboAPI 虚拟 limbo，看到网站链接，用 `/login <码>` 完成认证，再被送到主服。

## Solution

把 `hydwind-gate` 重写为 Velocity 插件：

- `LoginLimboRegisterEvent` 判断玩家是否需要认证；需要则用 LimboAPI 创建虚拟 limbo 并把玩家放进去。
- limbo 内通过 Adventure 发送聊天/标题/可点击网址，说明「打开 `/gate` 登录，然后 `/login <码>`」。
- `LimboSessionHandler#onChat` 解析 `/login <码>`（LimboAPI 会吞掉聊天和命令，不经过 Velocity CommandManager），交给 `flow/LoginFlow.submit`，由它调用网站 `/api/plugin/login/verify`。
- 成功：记录会话，`LimboPlayer#disconnect(主服)`；失败：提示原因；5 分钟未登录踢出。
- 网站 API 不变；复用 `WebsiteClient`、`LoginCode`（原 `KaomojiCode`）、`LoginSessionStore`、`RestartPolicy`、`RestartTrigger`。
- 登录决策集中到 `flow/LoginFlow`，管理操作集中到 `admin/AdminActionService`，命令语法集中到 `command/CommandGrammar`；运行时代际由 `RuntimeHolder` 整体替换。
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
- 启动时检查 `proxy.getVersion().getVersion()` 至少 4.1.2，并用 `compat/LimboCompat` 读取已安装 `limboapi` 插件的实际版本；缺失或低于 1.2.0 时记录明确错误并关闭代理（Velocity 没有运行时禁用插件的 API），不静默继续。
- `compat/VersionCompat` 只做版本比较（忽略 `-SNAPSHOT` 等后缀）；`LimboCompat` 在其上定义最低 LimboAPI 版本 `1.2.0` 并读取构建期写入的版本资源。
- LimboAPI 最低 1.2.0；与 Velocity 版本强绑定，升级 Velocity 前必须同步升级 LimboAPI。
- 插件元数据：`@Plugin(id = "hydwind-gate", dependencies = { @Dependency(id = "limboapi") })`，生成 `velocity-plugin.json`。
- 只做一个插件；LimboAPI 作为运行时库插件单独安装。

### LimboAPI 依赖

- Elytrium Maven 目前只有 `1.1.27` 和 `2.0.0-SNAPSHOT`，`1.2.0` 返回 404；JitPack 也返回 404。
- 1.2.0 的官方发布物是 GitHub Release 的 `limboapi-1.2.0.jar`。
- 构建时用 Gradle 任务下载该 jar 到 `build/limboapi/`，以 `compileOnly(files(...))` 引用；不要 shade。
- 下载后用 GitHub Release API 公布的 SHA-256（`25582ebf5567fec1c20a03ad2c6e01fa05911a76749c67e7b0192acf508f7d31`）校验，不匹配时构建失败。
- 固定 URL 与版本；若后续 Elytrium Maven 发布 1.2.0，切换为 `net.elytrium.limboapi:api:1.2.0`。
- 运行期必须安装 `limboapi-1.2.0.jar` 或更高版本；缺失时插件因依赖缺失不加载，低于 1.2.0 时启动版本检查记录错误并关闭代理。

### 登录流程

1. `PreLoginEvent`：强制离线模式（`forceOfflineMode()`），记录用户名/UUID。
2. `LoginLimboRegisterEvent`：
   - 调用 `LoginSessionStore#join(uuid, gameId, ip, now, auth.loginTimeout())`；新会话在这里固定 `loginDeadline = now + loginTimeout`。
   - 若加入后会话已认证（同 UUID + 同 IP 在断线宽限内），`LimboFactory#passLoginLimbo(player)`，直接走正常登录。
   - 否则 `event.addOnJoinCallback(() -> spawnInLimbo(player))`。
3. `spawnInLimbo`：`createVirtualWorld` + `createLimbo` + `limbo.registerCommand(new LimboCommandMeta(List.of("login")))` + `limbo.spawnPlayer(player, sessionHandler)`。
4. `LimboSessionHandler#onSpawn`：先查 `LoginSessionStore`；若会话已被管理员提前放行为 `AUTHENTICATED`，立即取消任务并转服，不再发送 limbo 提示。否则发送网址、说明和当前状态；用 `LimboPlayer#getScheduledExecutor()` 每 `auth.prompt-interval-seconds` 重复提示，并按该会话自己的 `loginDeadline` 排一次性超时任务。
5. `onChat(String)`：命令带前导 `/`；limbo 侧先用 `CommandGrammar.parseLine` 判断是否为 `/hydwind`，其余交给 `LoginFlow.submit(identity, line)`。`submit` 返回 `VERIFYING` 时异步调用 `LoginFlow.verify(identity, verificationId, code)`；成功后 `limboPlayer.disconnect(mainServer)`，失败按 `VerifyOutcome.message()` 回消息；超时由 `LoginFlow.timeout(identity)` 踢出。为兼容聊天直发，不带前导 `/` 的 `login` 也识别（`hydwind` 同理）。
6. `ServerPreConnectEvent`：没有有效会话的玩家禁止连接任何后端。
7. `DisconnectEvent`：清理会话（保留 5 分钟宽限记录）。

### 流程编排与模块边界

- `flow/LoginFlow`：纯逻辑，不 import Velocity/LimboAPI。构造函数接收 `LoginSessionStore`、`WebsiteClient`、`Messages`、`Clock`；`submit` 解析 `LoginCommand` 并推进会话状态，`verify` 调用网站并把结果应用到会话，`timeout` 使用会话自己的截止时刻。`LoginSessionHandler` 只保留发消息、转服、断开、提示调度等平台动作。
- `SubmitKind` / `SubmitResult` / `VerifyOutcome`：提交分类为 `IGNORED`、`EMPTY_CODE`、`TOO_LONG`、`ALREADY_AUTHENTICATED`、`VERIFYING`、`REJECTED`；`submit` 成功时携带 `verificationId` 与归一化后的码；`verify` 返回是否放行、是否触发受控重启、是否契约不兼容与文案。`LoginFlow` 不会替 Adapter 补建会话，会话必须由进服流程创建。
- `auth/LoginSession`：在 record 末尾新增 `loginDeadline`（进服时固定，宽限恢复沿用原值）与 `verificationId`（每次 `beginVerification` 递增，`0` 表示尚未提交）。`authenticationSucceeded` / `authenticationFailed` 只接受 `VERIFYING` 且编号匹配的结果，迟到结果被丢弃。
- `admin/AdminActionService`：编排 approve / kick；`login/LimboResidents` 是 limbo 玩家的窄接口（由 `LimboLoginService` 实现）。approve 先 `forceAuthenticate`，再按 resident 是否存在决定是否转服；kick 先 `sessions.remove`，再踢 limbo 或后端在线玩家。`AdminCommandExecutor` 只把 `AdminResult` 映射成文案。
- `command/CommandGrammar`：`/hydwind` 子命令语法的唯一来源；`SPECS`、`parseLine`、`parseArgs`、`suggestSubcommands`、`requiresTarget` 全部从同一份定义派生。`AdminCommandRequest.Type` 删除 `NONE`，limbo 侧用 `Optional` 表示“不是我的命令”；`HydwindCommand` 的补全由 `SPECS` 派生，权限谓词只在一处判断，执行器 switch 穷尽所有类型。
- `runtime/RuntimeSnapshot` + `runtime/RuntimeHolder`：快照包含 `PluginConfig`、`Messages`、`WebsiteClient`、`RestartTrigger`。`RuntimeHolder` 用一次 `AtomicReference` 替换发布新代际并返回旧代际；读取方在单次操作内只取一次快照。`HttpWebsiteClient` 实现 `AutoCloseable`，关闭时释放内部 `HttpClient`。

### 会话与失败策略

- 会话按 UUID 存，记录 IP、认证时间；断线宽限 5 分钟（UUID + IP）。
- 超时在会话创建时固定：`loginDeadline = now + auth.login-timeout-seconds`。per-player 定时任务和 60 秒兜底 sweep 都只看会话自己的 deadline，不再在触发时读取全局配置。
- 网站不可达（`UNREACHABLE`）：fail-closed，保留在 limbo，提示「认证服务暂时不可用」，触发受控重启；只有健康检查确认属于传输层失败（`HealthResult#transportFailure`）且策略允许时才写重启请求文件。
- 429 限流：显示「尝试过多，请 X 秒后再试」；响应带 `attemptsRemaining` 时追加「剩余 N 次」，不触发重启。
- 401/令牌错误：日志告警，按服务异常处理，不触发重启。
- 403（`FORBIDDEN`）与 404（`NOT_FOUND`）是明确失败结果：显示对应文案，不触发健康检查或受控重启。
- 409 只在 `reason` 为 `invalid_code`、`expired`、`used`、`game_id_mismatch` 时按业务失败处理；未知或缺失 `reason`、畸形 body 视为契约不兼容。
- 200 但 `ok=false`、缺少 `account` 或响应体畸形：视为契约不兼容（`CONTRACT_MISMATCH`），提示「认证服务配置异常」，不触发重启。其余非预期状态码（不含 5xx）同样按契约不兼容处理；5xx 按传输层不可达处理。
- 健康检查 200 但 `ok` 不为 true、或健康响应体畸形：`HealthResult#unhealthyContract`，只告警，不触发受控重启。
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
  - `restart.request-path`、`restart.cooldown-seconds`、`restart.max-per-hour`、`restart.window-seconds`
  - `admin.permission`（默认 `hydwind.admin`）
  - `messages`（网址、提示、错误、超时）
- 新增窄设置 record `WebsiteSettings`、`AuthSettings`、`LimboSettings`、`AdminSettings`、`ServerSettings`，由 `PluginConfig#website()`、`auth()`、`limbo()`、`admin()`、`server()` 暴露；`restart` 继续使用 `RestartSettings`。校验集中在配置构造阶段（`PluginConfig` 及其窄设置 record）：base-url 非空、超时与时长均为正；非法值让启动或 reload 失败，不再静默回退默认值。`HttpWebsiteClient` 不再自行做 `positiveOrDefault` 兜底。
- `LimboLoginService` 在 `initialize` 时持有生效的 `limbo` 设置副本，供日志/状态使用；reload 只比较并告警，不重建 limbo。
- `/hydwind reload` 重载，保留进行中的会话与 `RestartPolicy` 计数；reload 构建完整的 `RuntimeSnapshot` 后一次 `publish`，再关闭旧代际；reload 方法加 `synchronized`。
- 超时在会话创建时固定：`auth.session-grace-seconds` 立即生效；`auth.prompt-interval-seconds` 与 `auth.login-timeout-seconds` 只对之后进入 limbo 的会话生效。reload 调大 `auth.login-timeout-seconds` 不会延长已进服玩家的截止时刻，旧会话仍按原定时间被踢。

### 命令与权限

- Velocity `CommandManager` 注册 `/hydwind`（已连后端的玩家）
- limbo 内 `onChat` 也解析 `/hydwind`，仅对 `hydwind.admin` 生效；不带前导 `/` 的 `hydwind` 同样识别（兼容行为）
- 两个入口共用 `command/CommandGrammar`：子命令清单、补全顺序与 `requiresTarget` 都由 `SPECS` 派生；`parseLine` 用 `Optional.empty()` 表示“不是管理命令”，limbo 继续按登录码处理。权限谓词只在一处判断，`AdminCommandExecutor` 的 switch 对所有 `AdminCommandRequest.Type` 穷尽，`AdminResult` 只在这里映射为文案。
- 子命令：`status`、`reload`、`approve <player>`、`kick <player>`
- `status` 除人数外列出 limbo 玩家名，最多 20 个，更多时显示「等 N 人」
- 权限由外部权限插件授予；未授权时返回无权限提示

### 代码迁移

- 删除 Paper/Bukkit 专用：`JoinListener`、`FreezeController`、`DialogFactory`、`DialogListener`、`paper-plugin.yml`、Bukkit/Paper 依赖、Dialog/冻结配置，以及 Paper 的 `LoginCommand` 实现。注意：这里删除的是 Paper 命令类；现存 `login/LoginCommand` 是 limbo `onChat` 的文本解析器，保留。
- 保留并移植：`WebsiteClient`、`LoginCode`（由 `KaomojiCode` 重命名，同时处理登录码与备用码）、`LoginSessionStore`、`RestartPolicy`、`RestartTrigger`、消息与配置结构。
- 主类改为 Velocity `@Plugin`；用 Velocity `ProxyServer`、`Scheduler`。
- 深化改造新增：`flow/LoginFlow`、`admin/AdminActionService`、`command/CommandGrammar`、`runtime/RuntimeHolder`、`compat/LimboCompat`；`AdminCommandExecutor` 只做文案映射，`LoginSessionHandler` 只做平台动作。
- 旧 spec `docs/specs/0001-login-gate.md` 标记 superseded；新增 ADR `docs/adr/0001-velocity-over-paper.md`。

### 构建

- Gradle Kotlin DSL，Java 25 toolchain。
- Velocity API：`com.velocitypowered:velocity-api:4.1.2-SNAPSHOT`，仓库 `https://repo.papermc.io/repository/maven-public/`。
- SnakeYAML 与 Gson 用 `implementation` + Shadow 插件打包进插件 jar；LimboAPI 与 Velocity API 用 `compileOnly`。
- CI 运行 `gradle build` 并上传 jar artifact；本地不跑构建。

## Testing Decisions

- **最高接缝（HTTP 契约）**：`POST /api/plugin/login/verify`、`GET /api/health`。`WebsiteClient` 对本地 `com.sun.net.httpserver.HttpServer` 桩测试 200/401/403/404/409/429/超时/连接拒绝，并覆盖 200 畸形 body、200 `ok=false`、缺 `account`、409 未知 `reason` 的 `CONTRACT_MISMATCH` 分类。
- **编排层（测试替身）**：`LoginFlowTest` 覆盖成功、错误码、超时、迟到结果、重复提交、管理员放行后的 onSpawn 决策；`AdminActionServiceTest` 覆盖仅在 limbo、仅会话、两者都有、都不存在及 approve/kick 的顺序不变量；`CommandGrammarTest` 覆盖解析、补全与 `requiresTarget` 一致性；`RuntimeHolderTest` 覆盖发布后代际可见、旧代际关闭与重复关闭安全。
- **纯逻辑**：`LoginCode` 归一化（登录码与备用码）、`LoginSessionStore` 状态、宽限与 `loginDeadline`（含 reload 后旧会话不变、`verificationId` 归属）、`RestartPolicy` 冷却与上限、`RestartTrigger` 契约不兼容不触发重启、配置窄视图与集中校验、Velocity/LimboAPI 版本比较、共享命令行/模板工具。JUnit 5。
- **测试约束**：所有测试只穿过 Module 的 Interface，不直接调用 package-private 映射函数；真实 limbo 客户端交互不在自动化测试范围内。
- **Limbo 集成**：真实 limbo 客户端交互仍手工验证（进 limbo、可点击 URL、真实客户端聊天/命令吞并、转服、断线宽限、真实网络故障）；`LoginFlow` / `AdminActionService` / `CommandGrammar` / `RuntimeHolder` 等编排用测试替身自动化，不重复手工验证。
- **手工清单**：进 limbo、看到可点击 URL、`/login` 成功/失败/超时、管理员在 onSpawn 前放行、reload 调大超时后旧会话仍按原时间被踢、契约不兼容只告警不写重启文件、LimboAPI 版本过低启动失败、转主服、断线宽限、网站不可达 fail-closed、受控重启文件、`/hydwind` 四个子命令、Tab 补全。
- **CI**：`gradle build` + 测试 + artifact；不在本机执行构建/测试。

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