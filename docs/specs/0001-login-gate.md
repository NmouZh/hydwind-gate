> **Superseded** — 本文描述的是 Paper 26.3 插件实现。2026-11-07 起改用单个 Velocity 插件，
> 见 [0002-velocity-gate.md](0002-velocity-gate.md) 与 [ADR-0001](../adr/0001-velocity-over-paper.md)。
> 保留本文仅作历史参考。

## Problem Statement

氢风是离线模式服务器，任何客户端都能用任意用户名进入。网站已有 QQ 机器人注册的账号体系（账号名 = 游戏 ID），但游戏服务器和账号体系之间没有连接，导致未注册玩家、冒名玩家都能直接进服。玩家需要一个「进服先证明自己是网站账号本人」的流程，管理员需要可观测、可兜底的手段。

## Solution

新增 Paper 插件 **HydwindGate**：玩家进服后被冻结，弹出带网址的对话框；玩家在浏览器登录网站 `/gate` 页面，复制一个颜文字验证码，粘贴回游戏对话框（或 `/login <码>`）；插件调用网站接口校验，成功后解冻放行。网站新增 `/gate` 页面、健康检查与插件专用校验接口；网站挂掉时由 systemd 拉起，插件在探活失败时触发一次受冷却限制的重启。

## User Stories

1. 作为未登录玩家，我想在进服后看到清晰的登录说明，以便知道下一步做什么。
2. 作为玩家，我想在对话框里看到可点击的网站链接，以便不用手动输入长地址。
3. 作为玩家，我想在登录前被冻结在出生点、禁止移动/交互/聊天/受伤，以便不能顶替他人破坏服务器。
4. 作为玩家，我想从网站复制一个可爱的颜文字验证码，以便登录过程有趣。
5. 作为玩家，我想同时看到一个纯 ASCII 备用码，以便粘贴失败时还能手输。
6. 作为玩家，我想把验证码粘贴进游戏对话框的输入行，以便不用手动敲。
7. 作为玩家，我想用 `/login <码>` 登录，以便对话框粘贴不可用时仍有可靠路径。
8. 作为输错码的玩家，我想看到明确错误和剩余尝试次数，以便知道怎么改。
9. 作为码过期的玩家，我想能回网站重新生成，以便继续登录。
10. 作为已注册玩家，我想在校验成功后立刻解冻，以便尽快开始游戏。
11. 作为短暂掉线的玩家，我想在 5 分钟内用同一 UUID + IP 重连免登录，以便不必重复操作。
12. 作为长时间未登录的玩家，我想收到明确的超时踢出提示，以便知道发生了什么。
13. 作为未注册玩家，我想被告知去 QQ 群私聊机器人「注册 游戏ID」，以便知道注册路径。
14. 作为账号名与游戏 ID 不一致的玩家，我想看到「此账号对应游戏 ID 为 X」的提示，以便纠正。
15. 作为玩家，我想在提示里看到自己的中文别名，以便体验更亲切。
16. 作为误关对话框的玩家，我想用 `/login` 重新打开，以便恢复流程。
17. 作为网站用户，我想用现有账号登录 `/gate` 页面，以便获取验证码。
18. 作为已登录用户，我想看到大号颜文字码和「复制」按钮，以便一眼识别、一键复制。
19. 作为用户，我想验证码 5 分钟后失效且只能用一次，以便降低被冒用风险。
20. 作为用户，我想在账号名和游戏 ID 不匹配时被网站拒绝，以便一码一人。
21. 作为未注册访客，我想在 `/gate` 看到去 QQ 群注册的指引，以便知道下一步。
22. 作为管理员，我想用 `/hydwind status` 查看待认证玩家、已认证玩家和网站健康状态。
23. 作为管理员，我想用 `/hydwind reload` 热重载配置，以便不重启服务器。
24. 作为管理员，我想用 `/hydwind approve <玩家>` 在网站故障时手动放行，以便兜底。
25. 作为管理员，我想用 `/hydwind kick <玩家>` 处理异常会话。
26. 作为管理员，我想在网站不健康、重启触发、达到冷却上限时收到日志告警。
27. 作为管理员，我想重启触发有 10 分钟冷却、每小时最多 2 次，以便避免无限重启。
28. 作为运维，我想网站由 systemd 开机自启、崩溃自动拉起，以便夜间无人值守。
29. 作为运维，我想插件在探活失败时触发一次受控重启，以便玩家申请时能自愈。
30. 作为运维，我想有一个 `/api/health` 端点，以便 systemd 和插件探活。
31. 作为运维，我想插件令牌与站务令牌分离，以便最小权限。
32. 作为运维，我想网站 base URL 可配置，以便换域名或上 TLS 时只改配置。
33. 作为运维，我想注册时账号名限制在 16 位以内，以便所有账号都能进服。
34. 作为玩家，我想网站的其他功能在插件故障时不受影响，以便仍能看指南、人物和议题。
35. 作为开发者，我想有覆盖码生成、会话状态机、HTTP 契约的测试，以便改动时能验证行为。

## Implementation Decisions

**仓库与构建**

- 新仓库 `hydwind-gate`，独立于 `hydwind-web`；加入工作区 `CLAUDE.md` 仓库登记与 `GLOSSARY-MAP.md`。
- Paper `26.3.build.244-stable`，Java 25，Gradle Kotlin DSL，`plugin.yml`（`api-version: '26.3'`）。
- 包名 `me.hydwind.gate`。

**模块**

- `config`：`PluginConfig`，YAML 配置，支持热重载。
- `auth`：`LoginManager`、`LoginSession`、`LoginSessionStore`、`KaomojiCode`。
- `web`：`WebsiteClient`（接口 + HTTP 实现）、`RestartPolicy`、`RestartTrigger`；健康检查是 `WebsiteClient.health()` 的方法，不单独设 `HealthProbe`。
- `listener`：`JoinListener`、`DialogListener`、`QuitListener`、`FreezeController`。
- `command`：`LoginCommand`、`AdminCommand`。
- `ui`：`DialogFactory`、`Messages`。

**认证状态机**

- 状态：`PENDING`（进服未登录）→ `VERIFYING`（已提交码，等待网站）→ `AUTHENTICATED`；`PENDING` 可超时进入 `TIMED_OUT` 并踢出。
- 会话以 UUID 为键，内存保存；服务器重启后全部清空，玩家重新登录。
- 断线后保留 5 分钟宽限，条件为同 UUID + 同 IP；换 IP 或超时则重新登录。

**验证码**

- 由网站 `/gate` 页面生成，绑定当前登录账号。
- 24 个颜文字候选，随机拼接 2 个；同时生成 ASCII 备用码 `HWG-XXXX`。
- 5 分钟 TTL、一次性；两码绑定同一请求，任一成功即全部作废。
- NFC 归一化 + 去首尾空格；ASCII 部分大小写不敏感。
- 网站按「账号 + 码」查找，跨账号撞码无影响；每个游戏 ID 5 分钟内最多 5 次尝试。
- 颜文字池与备用码前缀放在网站配置中，可替换。

**插件流程**

- `AsyncPlayerPreLoginEvent` 只做基础放行（不在此处阻塞）；进服后由 `PlayerJoinEvent` 进入 `PENDING`。
- 冻结：传送到配置的出生点，冒险模式、隐身、无敌、禁止移动（取消移动事件）、禁止聊天/命令/交互/伤害。
- 对话框：`Dialog.create` + `DialogInput.text("code", ...)` + `DialogType.confirmation`；`canCloseWithEscape(false)`；正文包含可点击的网站链接。
- 提交后异步调用 `WebsiteClient.verify(code, gameId, uuid)`，成功回调主线程解冻。
- `/login` 无参数重新打开对话框；`/login <码>` 直接提交。
- 网站不可达时 fail-closed：保留 `PENDING`，提示「认证服务暂时不可用」，超时踢出；不 fail-open。
- `Ctrl+V` 粘贴无官方保证，`/login` 是必需的兜底路径。

**网站接口**

```
GET  /api/health
→ 200 { "ok": true, "uptime": <seconds> }

POST /api/plugin/login/verify
Authorization: Bearer <PLUGIN_TOKEN>
{ "code": "<码>", "gameId": "<游戏ID>", "uuid": "<UUID>" }
→ 200 { "ok": true, "account": { "username": "...", "nickname": "...", "role": "player|admin" } }
→ 409 { "ok": false, "reason": "invalid_code|expired|used|game_id_mismatch" }
→ 401 令牌错误
```

- 新增 `PLUGIN_TOKEN` 环境变量，与 `ISSUE_ADMIN_TOKEN` 分离；插件令牌只能调健康检查与登录校验。
- `/gate` 页面：登录后生成并展示码、复制按钮、ASCII 备用码；未登录引导去 `/account/`；未注册引导去 QQ 群。
- 账号注册校验收紧为 3–16 位；已有超长账号（预期不存在）不迁移。
- `security.allowedDomains` 加入 `103.236.99.253`；`site` 改为新地址；同步 `check-build.mjs`、`BaseLayout.astro`、`README.md`、`docs/backend-api.md`。

**进程守护与自动重启**

- systemd unit `hydwind-web.service`：`Restart=always`，开机自启。
- `hydwind-restart.path` + `.service`：监听 `/run/hydwind/restart-request`，文件出现即 `systemctl restart hydwind-web`，执行后删除。
- 插件 `RestartPolicy`：仅在「玩家提交登录 + 健康探活失败」时触发；10 分钟冷却、每小时最多 2 次；超限只告警不重启。
- 插件通过写文件触发，不需要 sudo，也不开网络端口。

**传输**

- 当前使用 `http://103.236.99.253:62179`，配置项预留；正式开放前切 TLS。
- 所有插件请求设置连接/读取超时，失败进入 fail-closed。

## Testing Decisions

- **只测外部行为**：验证码的生成/归一化/匹配、会话状态迁移、HTTP 请求与响应、重启策略的冷却上限；不断言私有方法和内部字段。
- **插件纯逻辑**：JUnit 5 覆盖 `KaomojiCode`、`LoginSessionStore`、`RestartPolicy`。
- **插件 HTTP 层**：`WebsiteClient` 对本地 `com.sun.net.httpserver.HttpServer` 桩测试成功、401、409、超时、连接拒绝。
- **网站纯逻辑**：`node:test` 覆盖码生成、NFC 归一化、TTL、一次性、限流键。
- **网站路由**：扩展 `scripts/smoke.mjs`，起 standalone server 后测 `/api/health` 和 `/api/plugin/login/verify` 的 200/401/409。
- **游戏内行为**：`run-paper` 起 26.3 测试服，按手工清单验证冻结、对话框、`/login`、踢出、重连宽限；这部分不纳入 CI。
- **现有接缝优先**：网站不引入 Jest/Vitest，沿用 `smoke.mjs`；插件不引入 MockBukkit，事件层用集成测试环境。

## Out of Scope

- 游戏内注册 / 绑定 QQ。
- QQ 机器人改动（只改网站注册的用户名校验）。
- 插件向网站上报在线状态（`STATUS_ENDPOINT` 替换）。
- TLS / 域名接入（本期先 HTTP）。
- 多服务器 / 代理（Velocity/BungeeCord）支持。
- 玩家数据、传记、经济系统同步。
- 反作弊、外挂检测。
- 游戏内改密码 / 找回密码。

## Further Notes

- **HTTP 风险**：密码和插件令牌会明文经过公网，测试期可接受，正式开放前必须上 TLS。
- **颜文字渲染**：Minecraft 默认字体不保证覆盖所有颜文字，需在 26.3 客户端实测；显示异常时复制粘贴仍可用。
- **Ctrl+V 未确认**：Paper/Minecraft 没有对话框粘贴的官方文档，`/login` 兜底不可省。
- **账号名长度**：网站允许 3–24，Minecraft 上限 16；本期收紧到 16，避免产生无法进服的账号。
- **中文别名**：`nickname` 仅用于展示，不参与身份匹配。
