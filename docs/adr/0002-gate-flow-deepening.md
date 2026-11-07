# ADR-0002: 门禁流程的 deepening 契约

## Status

Accepted — 2026-11-07（已实施 2026-11-08）

## Related

- 细化但不取代 [ADR-0001: 用 Velocity 插件而不是 Paper 后端插件](0001-velocity-over-paper.md)。
  ADR-0001 决定平台（单 Velocity 插件 + LimboAPI），本 ADR 只决定该插件内部的门禁流程结构；
  两者是补充关系，不是 supersedes。

## Context

架构评审确认：登录流程的规则散在 `LoginGateListener`、`LoginSessionHandler`、`LimboLoginService`、`AdminCommandExecutor` 四个 Adapter 里；会话超时在 reload 后与 spec 相反；approve/kick 双写；reload 逐字段替换；测试没有穿过编排层。本 ADR 冻结目标 Module 与 Interface，供并行实施使用。

原则：`Module`、`Interface`、`Implementation`、`Depth`、`Seam`、`Adapter`、`Leverage`、`Locality`。不做只有一个 Adapter 的假想 Seam；测试替身算第二个 Adapter。

## 目标结构

### 1. runtime

新增 `runtime/RuntimeSnapshot`：

```java
public record RuntimeSnapshot(
        PluginConfig config,
        Messages messages,
        WebsiteClient websiteClient,
        RestartTrigger restartTrigger) implements AutoCloseable {
    @Override public void close(); // 关闭 websiteClient（若为 AutoCloseable）
}
```

新增 `runtime/RuntimeHolder`：

- 内部 `AtomicReference<RuntimeSnapshot>`；
- `RuntimeSnapshot current()`；
- `RuntimeSnapshot publish(RuntimeSnapshot next)`：一次引用替换，返回旧代际；
- `void close()`：关闭当前代际。

`HttpWebsiteClient` 实现 `AutoCloseable`，`close()` 关闭内部 `HttpClient`。
`HydwindGatePlugin.reloadConfiguration()` 必须：构建完整新代际 → 一次 `publish` → `close` 旧代际；reload 方法加 `synchronized`。所有读取方通过 `runtime().config()` / `runtime().messages()` 取一次快照并在本次操作内复用。

### 2. 登录会话

`auth/LoginSession` 增加两个字段（放在 record 末尾）：

```java
Instant loginDeadline,   // 本次登录会话的截止时刻；join 时固定
long verificationId      // 每次 beginVerification 递增；0 表示尚未提交
```

`auth/LoginSessionStore` 的 Interface 改为：

- `join(UUID, String gameId, String ip, Instant now, Duration loginTimeout)`：创建 PENDING 时固定 `loginDeadline = now + loginTimeout`；宽限恢复的 AUTHENTICATED 会话保留原 deadline。
- `OptionalLong beginVerification(UUID, Instant now)`：仅 PENDING → VERIFYING，`verificationId++`。
- `boolean authenticationSucceeded(UUID, long verificationId, Instant now)`：仅 VERIFYING 且 id 匹配。
- `boolean authenticationFailed(UUID, long verificationId, Instant now)`：仅 VERIFYING 且 id 匹配。
- `boolean timeOut(UUID, Instant now)`：只用会话自己的 `loginDeadline`，不再接收 limit。
- `List<LoginSession> timeOutAll(Instant now)`：同样只用会话自己的 deadline；供 60 秒 sweep 兜底。
- 其余 `find` / `findByGameId` / `snapshot` / `quit` / `expireGrace` / `forceAuthenticate` / `remove` / `setReconnectGrace` 保留。

`LoginSessionHandler` 的超时任务改为调用 `timeOut(uuid, now)`；spawn 时仍按当时的 deadline 排一次性任务，但不再在触发时读全局配置。

### 3. flow

新增 `flow/LoginFlow`（纯逻辑，不 import Velocity/LimboAPI）：

```java
public record LoginIdentity(UUID uuid, String gameId, String ip) {}

public enum SubmitKind { IGNORED, EMPTY_CODE, TOO_LONG, ALREADY_AUTHENTICATED, VERIFYING, REJECTED }

public record SubmitResult(SubmitKind kind, long verificationId, String normalizedCode) {}

public record VerifyOutcome(boolean authenticated, boolean triggerRestart,
                            boolean contractMismatch, String message) {}

public final class LoginFlow {
    public LoginFlow(LoginSessionStore sessions, WebsiteClient website,
                     Messages messages, Clock clock);
    public SubmitResult submit(LoginIdentity identity, String rawLine);
    public VerifyOutcome verify(LoginIdentity identity, long verificationId, String code);
    public boolean timeout(LoginIdentity identity);
}
```

行为：

- `submit` 解析 `LoginCommand`；`NOT_LOGIN` → `IGNORED`；`EMPTY_CODE` / `TOO_LONG` 返回对应 kind；已认证 → `ALREADY_AUTHENTICATED`；`beginVerification` 失败且状态 VERIFYING → `REJECTED`；成功则返回 `VERIFYING` + id + 归一化码。
- `verify` 调用 `WebsiteClient.verify`；用 `LoginFeedback` 得到文案与动作；成功时调用 `authenticationSucceeded(uuid, verificationId, now)`，失败时 `authenticationFailed(...)`；返回 `VerifyOutcome`。
- `timeout` 调用 `sessions.timeOut(uuid, now)`。

`LoginSessionHandler` 只保留平台动作（发消息、转服、断开、提示调度），把 submit/verify/timeout 的决策委托给 `LoginFlow`。

### 4. admin

新增 `admin/AdminResult`：

```java
public record AdminResult(Kind kind, String playerName) {
    public enum Kind { APPROVED_IN_LIMBO, APPROVED_OFFLINE, KICKED_IN_LIMBO,
                       KICKED_BACKEND, ALREADY_AUTHENTICATED, NOT_FOUND }
}
```

新增 `admin/AdminActionService`：

```java
public AdminActionService(LoginSessionStore sessions, LimboResidents residents,
                          ProxyServer server, Clock clock);
public AdminResult approve(String playerName);
public AdminResult kick(String playerName);
```

`login/LimboResidents` 窄 Interface（由 `LimboLoginService` 实现）：

```java
Optional<Resident> find(String gameId);
List<Resident> all();
boolean approve(UUID uuid, String message);
boolean kick(UUID uuid, String message);
record Resident(UUID uuid, String gameId) {}
```

approve 的顺序：先 `forceAuthenticate`，再按 resident 是否存在决定是否 `approveByAdmin`。kick：先 `sessions.remove`，再踢 limbo 或后端在线玩家。`AdminCommandExecutor` 只把 `AdminResult` 映射成文案，不再直接写 store。

`LoginSessionHandler.onSpawn` 必须先检查 store：若会话已认证（管理员在 spawn 前放行），立即取消任务并转服，不再发提示。

### 5. command

新增 `command/CommandGrammar`：

```java
public record Spec(String name, boolean requiresTarget) {}
public static final List<Spec> SPECS;
public static Optional<AdminCommandRequest> parseLine(String raw);
public static AdminCommandRequest parseArgs(String... args);
public static List<String> suggestSubcommands(String prefix);
public static boolean requiresTarget(AdminCommandRequest.Type type);
```

`AdminCommandRequest.Type` 删除 `NONE`；limbo 侧用 `Optional` 表达“不是我的命令”。`HydwindCommand` 的补全从 `SPECS` 派生；权限谓词只在一个地方判断。`AdminCommandExecutor` 的 switch 改成穷尽表达式。

### 6. web

- `VerifyResult.Status` 新增 `CONTRACT_MISMATCH`。
- `HttpWebsiteClient`：200 但 `ok=false` / 缺 `account` / 409 未知 reason / body 畸形 → `CONTRACT_MISMATCH`；IOException / 超时 / 5xx → `UNREACHABLE`；403/404/401/429/已知 409 保持原语义。
- `HealthResult` 增加 `boolean transportFailure`：`healthy(uptime)`、`unhealthy()`（transportFailure=true）、`unhealthyContract()`（false）。
- `RestartTrigger` 只在 `health.transportFailure()` 时考虑受控重启。
- `LoginFeedback`：`CONTRACT_MISMATCH` → `serviceMisconfigured`，`triggerRestart=false`。

### 7. config

新增窄设置 record：`WebsiteSettings`、`AuthSettings`、`LimboSettings`、`AdminSettings`、`ServerSettings`。
`PluginConfig` 提供 `website()` / `auth()` / `limbo()` / `admin()` / `server()`。
`PluginConfig` 构造器负责：base-url 非空、超时为正；`HttpWebsiteClient` 不再做 `positiveOrDefault` 兜底。
`LimboLoginService` 在 `initialize` 时持有 `LimboSettings` 的生效副本，并提供 `activeLimboSettings()` 供日志/状态使用；reload 只比较并告警。

### 8. ui

- 新增 `ui/AdminStatusRenderer.render(AdminStatus, AdminMessages, String permission, DateTimeFormatter)`；`AdminStatus` 只保留快照与统计，不再有 `lines()`。
- `Messages` / `AdminMessages` 保留现有 record 字段；新增 `Messages.placeholderWarnings(Map<String,Object> root)`，由插件在加载配置时记录 WARN，不改变运行时替换行为。
- `Templates` 保持“未知占位符原样保留”。

### 9. compat

新增 `compat/LimboCompat`：最低 `1.2.0`，用 `VersionCompat.isAtLeast` 比较；插件启动时读取 `limboapi` 插件版本并校验，缺失或过低时记录错误并关闭代理。`VersionCompat` 保持纯 helper。

### 10. 清理

- `auth/KaomojiCode` 重命名为 `auth/LoginCode`，语义覆盖登录码与备用码；更新全部引用与测试。
- 删除 `LimboLoginService.findLimboPlayer`。
- 插件的 60 秒 sweep 调用 `sessionStore.timeOutAll(now)` 兜底并记录日志；per-player 定时任务仍是主要踢出路径。
- spec 0002:124 的 “删除 Paper LoginCommand” 改为明确指 Paper 实现，避免与现存 `login/LoginCommand` 混淆。

## 测试契约

新增/更新：

- `LoginFlowTest`：成功、错误码、超时、迟到结果、重复提交、管理员放行后的 onSpawn。
- `AdminActionServiceTest`：仅在 limbo、仅会话、两者都有、都不存在。
- `RuntimeHolderTest`：发布后代际可见、旧代际关闭。
- `CommandGrammarTest`：解析 / 补全 / requiresTarget 一致。
- `LoginSessionStoreTest`：deadline 固定、reload 后旧会话不变、verificationId 归属。
- `HttpWebsiteClientTest`：403/404 经 HTTP 桩；CONTRACT_MISMATCH 分类。
- `RestartTriggerTest`：契约不兼容不触发重启。
- `PluginConfigTest`：窄设置视图与集中校验。

所有测试只穿过 Module 的 Interface，不直接调用 package-private 映射函数。

## 实施约束

- Java 25；不引入新依赖。
- 不运行 Gradle / 测试（工作区规则）；本地只做 `javac` 语法检查由集成阶段统一执行。
- 每个 Agent 只改自己名下的文件；跨文件签名变更由 ADR 冻结。
- 注释用中文，风格与现有代码一致。

## Implementation notes

实施与冻结签名存在以下偏差，均已在代码中落地：

- `AdminActionService` 不直接依赖 Velocity 的 `ProxyServer`，改用窄接口
  `AdminActionService.BackendPlayers`（`findOnline` / `disconnect` / `nameOf`）访问后端在线玩家，
  便于用测试替身覆盖；`approve` / `kick` 额外接收 `message` 参数，由 Adapter 从当前配置取文案，
  编排层不依赖 `Messages`。
- 配置校验放在窄设置 record（`WebsiteSettings`、`AuthSettings`、`LimboSettings`、`AdminSettings`、
  `ServerSettings`）的紧凑构造器里，`PluginConfig` 只负责组装与转发；`HttpWebsiteClient`
  因此不再做 `positiveOrDefault` 之类的兜底。
- `HttpWebsiteClient.close()` 不调用 `HttpClient.close()`：后者会阻塞等待在途请求，
  而 reload 发生在代理调度线程上。实现改为设置 `closed` 标志，让后续请求立即返回
  契约不匹配（避免伪装成传输层失败）；旧连接池随代际被 GC 回收。
- `LimboLoginService` 在 `initialize()` 时固定 `LimboSettings`，通过 `activeLimboSettings()`
  暴露给启动日志；日志同时打印配置值与生效值，二者不同时提示重启代理。
