# HydwindGate

氢风（Hydwind）Minecraft 服务器的进服门禁插件。玩家连上 **Velocity 代理**后进入 LimboAPI 虚拟 limbo，在浏览器打开网站登录拿到登录码，再回到游戏输入 `/login <码>`；验证通过后自动转服。

- 代理：Velocity 4.1.2+（Java 25，Minecraft 26.3 / 协议 777）
- 运行时依赖：LimboAPI 1.2.0（作为独立插件安装）
- 构建：Gradle Kotlin DSL + Shadow，Java 25
- 网站：`hydwind-web`（`/gate` 页面 + `/api/plugin/login/verify`）

设计文档：[docs/specs/0002-velocity-gate.md](docs/specs/0002-velocity-gate.md)
决策记录：[docs/adr/0001-velocity-over-paper.md](docs/adr/0001-velocity-over-paper.md) · [docs/adr/0002-gate-flow-deepening.md](docs/adr/0002-gate-flow-deepening.md)

## 状态

当前实现已完成从 Paper 到 Velocity 的迁移，并完成登录流程的深化改造：

- 单个 Velocity 插件；Paper/Bukkit 依赖与对话框、冻结相关代码已删除。
- 登录决策集中在 `flow/LoginFlow`（`submit` / `verify` / `timeout`）；`LoginSessionHandler` 只负责发消息、转服、断开与提示调度。limbo 内的 `/login` 文本解析仍由 `login/LoginCommand` 负责。
- 会话进服时固定自己的 `loginDeadline`；每次成功进入验证（PENDING → VERIFYING）时分配 `verificationId`，编号按玩家 UUID 跨会话单调递增（重连后不从 1 重来），迟到或重复的网站结果会被丢弃。60 秒维护任务按各会话 deadline 兜底超时。
- 配置按 `website` / `auth` / `limbo` / `admin` / `server` 五组映射为窄设置 record（`restart` 继续用 `RestartSettings`）；启动与 reload 集中校验：base-url/URI、超时、无法解析的数值字符串、auth 时限、restart 数值非法时直接失败，空白文本字段与缺失项回退默认值，畸形 `limbo.spawn` 只告警。
- reload 构建完整的 `RuntimeSnapshot` 后整体替换，旧代际关闭；进行中的会话与重启计数保留。
- `/hydwind` 的解析、补全与目标判断统一由 `CommandGrammar` 提供；approve / kick 由 `AdminActionService` 编排：approve 先把会话写成已认证、再转服；kick 先移除会话、再断开。
- 网站响应新增 `CONTRACT_MISMATCH` 分类；verify 的 5xx 属于传输层失败，health 的非 200 属于契约不兼容；只有传输层失败才可能触发受控重启，契约不兼容只告警。
- 启动时校验 LimboAPI 版本（最低 1.2.0）与 Velocity 版本；Velocity 过低或 LimboAPI 已加载但版本过低时记录错误并关闭代理。LimboAPI 缺失时插件因 Velocity 依赖机制不加载，代理继续运行但没有门禁。
- JUnit 5 覆盖编排层（`LoginFlowTest`、`AdminActionServiceTest`、`CommandGrammarTest`、`RuntimeHolderTest`）、会话 deadline 与 `verificationId`、HTTP 契约与 `CONTRACT_MISMATCH`、配置窄视图与集中校验；真实 limbo 客户端交互按手工清单验证。

## 版本要求

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Velocity | 4.1.2+ | 构建使用 `velocity-api:4.1.2-SNAPSHOT`；插件启动时检查版本，低于 4.1.2 会记录错误并关闭代理。升级前先确认 LimboAPI 有对应版本 |
| Java | 25 | 构建与运行都要求 Java 25 |
| Minecraft | 26.3（协议 777） | 客户端与后端均需 26.3；LimboAPI 1.2.0 在低于 777 的协议上会直接关闭代理 |
| LimboAPI | 最低 1.2.0 | 独立插件，必须与 Velocity 版本配套；未安装时 Velocity 因插件依赖拒绝加载 HydwindGate，代理继续运行但没有门禁；已加载但低于 1.2.0 会记录错误并关闭代理 |
| 后端服务器 | Paper 26.3 | 名称需与 `velocity.toml` 的 `servers` 键、`server.main` 一致 |

## 构建

构建统一在 GitHub Actions 上运行（见 `.github/workflows/ci.yml`），**本地不执行 Gradle 构建**，以免环境差异掩盖问题。

- 触发条件：推送到 `main`、`feat/**`（含 `feat/login-gate`）或 `fix/**`；或向 `main`、`feat/login-gate`、`feat/velocity-gate` 发起 Pull Request
- 手动触发：仓库 **Actions** 页面选择 **CI** → **Run workflow**
- 查看最近一次运行：`gh run list -R NmouZh/hydwind-gate --branch <分支名>`
- 跟踪运行：`gh run watch <run-id> -R NmouZh/hydwind-gate`

CI 使用 Java 25（temurin）与 Gradle 9.7.0，执行 `gradle build`（包含 JUnit 5 测试），然后：

1. 在日志中逐个列出测试用例，并输出汇总行
   `JUnit results: N suites, M tests, 0 failures, 0 errors, 0 skipped`。
   测试结果为 0 或任一失败都会让 CI 失败。
2. 检查产物 jar 内含 `velocity-plugin.json`（Velocity 注解处理器生成）。
3. 检查 `build/limboapi/limboapi-1.2.0.jar` 已被下载，并用 GitHub Release API 公布的 SHA-256 校验通过。
4. 上传插件 jar 为 Actions artifact `hydwind-gate`。

### 下载构建产物

```bash
# 查看最近一次成功运行
gh run list -R NmouZh/hydwind-gate --branch feat/velocity-gate --status success

# 下载 artifact 到当前目录（解压后得到 HydwindGate-0.1.0.jar）
gh run download <run-id> -R NmouZh/hydwind-gate -n hydwind-gate
```

也可以直接点开 Actions 运行页面，在 **Artifacts** 区域下载 `hydwind-gate`。

构建依赖：

- Velocity API：`com.velocitypowered:velocity-api:4.1.2-SNAPSHOT`（`https://repo.papermc.io/repository/maven-public/`），`compileOnly` + `annotationProcessor`。
- LimboAPI 1.2.0：Elytrium Maven 与 JitPack 均未发布该版本，Gradle 任务从 GitHub Release 下载到 `build/limboapi/`，以 `compileOnly(files(...))` 引用，**不 shade、不打包**。下载后用 Release API 公布的 SHA-256（`25582ebf...f7d31`）校验，不匹配时构建失败。
- SnakeYAML 与 Gson 用 `implementation` + Shadow 打包进插件 jar，不依赖代理服提供。

## 安装 LimboAPI

LimboAPI 1.2.0 不在 Maven Central / Elytrium Maven 上，需要从 GitHub Release 手动下载：

```bash
curl -fL --create-dirs -o plugins/limboapi-1.2.0.jar \
  https://github.com/Elytrium/LimboAPI/releases/download/1.2.0/limboapi-1.2.0.jar

# 必须校验：LimboAPI 1.2.0 只发布在 GitHub Release，是当前最大的供应链风险。
echo "25582ebf5567fec1c20a03ad2c6e01fa05911a76749c67e7b0192acf508f7d31  plugins/limboapi-1.2.0.jar" \
  | sha256sum -c -
```

`plugins/` 最终应同时包含：

- `HydwindGate-0.1.0.jar`（本插件，来自 CI artifact）
- `limboapi-1.2.0.jar`（LimboAPI）

插件声明了 `@Dependency(id = "limboapi")`；缺少 LimboAPI 时 Velocity 会拒绝加载本插件并给出原因，此时代理仍在运行但没有门禁，必须安装 LimboAPI 后重启并确认控制台出现 `HydwindGate ... enabled`。LimboAPI 本体是 AGPLv3，API 模块是 MIT，再分发时注意区别。

## 部署

1. 安装 Java 25 与 Velocity 4.1.2+，在 `velocity.toml` 的 `servers` 中配置后端（例如键名 `main`），并让插件配置 `server.main` 的值与这个键一致（默认 `main`）。`server.main` 是插件配置路径，不是 `velocity.toml` 里的服务器名。
2. 把 `HydwindGate-0.1.0.jar` 与 `limboapi-1.2.0.jar` 放进 Velocity 的 `plugins/`（见上一节的下载方式）。
3. 启动代理，插件会在 `plugins/hydwind-gate/config.yml` 生成默认配置。
4. 设置环境变量 `HYDWIND_PLUGIN_TOKEN`，其值必须与网站侧 `PLUGIN_TOKEN` 一致；非空时优先于 `website.token`。
5. 重启代理，把 `velocity.toml` 中的 `forced-hosts` / 玩家入口指向该代理；插件会通过 `PreLoginEvent` 强制离线模式。

### 部署注意

- **版本强绑定**：LimboAPI 最低 1.2.0，只兼容 Velocity 4.1.2+ 与协议 777（MC 26.3）。升级 Velocity 前先确认 LimboAPI 有对应版本，否则代理会拒绝启动或直接关闭连接。
- **令牌不要进仓库**：`website.token` 建议留空，用进程环境变量 `HYDWIND_PLUGIN_TOKEN` 注入；令牌错误时玩家看到「认证服务配置异常」，控制台有 401 告警。
- **重启目录可写**：`restart.request-path` 的父目录必须存在且代理进程可写（默认 `/run/hydwind`）；或改到可写路径。该文件由外部 systemd `path` unit 监听，插件本身不执行重启。
- **契约不兼容只告警**：verify 返回 200 但响应体不符合契约（缺字段、409 未知 `reason`、畸形 JSON）时按「认证服务配置异常」提示玩家并记录 WARN，不写重启文件；health 返回非 200（含 5xx）或健康体畸形同样视为契约不兼容，只告警、不触发重启。只有 verify 的传输层失败（不可达、超时、5xx）才进入受控重启判断，且仍需健康检查确认 `transportFailure=true`。
- **主服名必须存在**：`server.main` 与 `velocity.toml` 的 `servers` 键一致；认证成功但服务器名不存在时，玩家会被断开并记录错误日志。
- **会话只在内存**：认证会话与断线宽限不会持久化；代理重启后所有玩家需要重新登录。
- **limbo 配置需重启**：`/hydwind reload` 会热重载网站、消息、断线宽限、重启与权限配置；`auth.prompt-interval-seconds` / `auth.login-timeout-seconds` 只对之后进入 limbo 的会话生效。登录超时在会话创建时固定为截止时刻，reload 调大不会延长已进服玩家的超时；`limbo.world` / `limbo.spawn` 的改动只在日志告警，需重启代理生效。
- **配置校验**：启动或 `/hydwind reload` 会集中校验配置。以下情况让启动或重载失败（重载失败时保留当前配置）：`website.base-url` 为空、非法 URI 或非 http/https；`website.connect-timeout-millis` / `read-timeout-millis` ≤ 0；无法解析的数值字符串；`auth` 三个时限 ≤ 0；`restart` 的冷却/窗口 ≤ 0 或 `max-per-hour` 为负。空白文本字段（`health-path`、`permission`、`limbo.world` / `limbo.spawn`、`server.main`、`restart.request-path`）、字段缺失、数值字段为非数字非字符串类型时回退默认值；畸形 `limbo.spawn` 只记录 WARN 并使用默认出生点。
- **确认插件已启用**：每次启动后检查控制台出现 `HydwindGate ... enabled`。缺少 LimboAPI 时 Velocity 会拒绝加载本插件，代理仍会运行，但此时没有任何门禁；没有这行日志就必须先解决依赖再开放入口。
- **启动版本检查**：启用时检查 `proxy.getVersion()` 至少为 4.1.2，并读取 `limboapi` 插件的实际版本（最低 1.2.0）。Velocity 版本过低或 LimboAPI 已加载但版本过低时记录明确错误并关闭代理。
- **不开放额外端口**：插件只通过 HTTP(S) 访问网站，不监听任何新端口。
- **管理员权限**：`admin.permission`（默认 `hydwind.admin`）由外部权限插件授予；未授权执行 `/hydwind` 只会收到无权限提示。

## 配置

完整注释见 `src/main/resources/config.yml`。主要配置项：

```yaml
website:
  base-url: "http://103.236.99.253:62179"  # 网站根地址
  token: ""                                 # 建议改用 HYDWIND_PLUGIN_TOKEN
  health-path: "/api/health"
  connect-timeout-millis: 3000
  read-timeout-millis: 5000
auth:
  login-timeout-seconds: 300                # 未登录踢出；进服时固定截止时刻
  session-grace-seconds: 300                # 断线免登录宽限
  prompt-interval-seconds: 10               # limbo 重复提示间隔
limbo:
  world: "hydwind_limbo"                    # 虚拟世界名（需重启代理）
  spawn: "0, 64, 0"                         # x, y, z[, yaw, pitch]（需重启代理）
server:
  main: "main"                              # velocity.toml 中的服务器名
restart:
  request-path: "/run/hydwind/restart-request"
  cooldown-seconds: 600                     # 两次重启最小间隔
  window-seconds: 3600                      # 滚动窗口
  max-per-hour: 2                           # 窗口内最多触发次数
admin:
  permission: "hydwind.admin"
messages:
  # 玩家与管理员可见文案，支持 {url}、{name}、{expectedGameId}、
  # {seconds}、{attempts}、{player}、{permission}、{count}、{grace}、{uptime}、
  # {remaining}、{time}、{error}、{players} 等占位符。
  # status-unverified 是 limbo 中显示的当前状态（默认「尚未认证」）。
```

配置在启动与 `/hydwind reload` 时集中校验：`website.base-url` 为空/非法 URI、超时 ≤ 0、无法解析的数值字符串、`auth` 三个时限 ≤ 0、`restart` 数值非法会让启动或重载失败（重载失败时保留当前配置）；空白文本字段、字段缺失、数值字段为非数字非字符串类型时回退默认值；畸形 `limbo.spawn` 只告警并使用默认出生点。插件内部按 `website` / `auth` / `limbo` / `server` / `restart` / `admin` 分组提供窄配置视图。`auth.login-timeout-seconds` 在玩家进服时固化为会话截止时刻，reload 调大只影响之后进入 limbo 的会话。

## 部署前必填项

- [ ] 在网站配置 `PLUGIN_TOKEN`，并在代理进程设置环境变量 `HYDWIND_PLUGIN_TOKEN`（不要写进仓库）。
- [ ] `website.base-url` 指向可访问的网站；正式开放前切换到 TLS。
- [ ] 安装 `limboapi-1.2.0.jar`（最低 1.2.0）；LimboAPI 与 Velocity 版本强绑定，升级 Velocity 前先确认兼容版本。
- [ ] 启动后在控制台确认 `HydwindGate ... enabled`；没有这行说明插件未加载，门禁不存在。
- [ ] 创建 `/run/hydwind` 且代理进程用户可写；或把 `restart.request-path` 改到可写路径。`/run` 是 tmpfs，手工创建会在宿主机重启后消失——用 systemd 的 `RuntimeDirectory=hydwind` 或 `/etc/tmpfiles.d/hydwind.conf` 持久化，并让代理用户与网站服务同组（见 `hydwind-web/deploy/README.md` 第 3 节）。
- [ ] 配置 systemd `hydwind-restart.path` + `.service`，监听重启请求文件。
- [ ] 确认 `server.main` 与 `velocity.toml` 中的服务器名一致。
- [ ] 管理员组拥有 `hydwind.admin`。

## 回滚

发布后如需回退，见 [docs/rollback.md](docs/rollback.md)：保留上一版 jar、沿用配置（旧版对新校验更宽松）、
同步回退 LimboAPI/Velocity，并预期所有玩家重新登录。

## 受控重启

网站不可达时登录流程按 fail-closed 处理：玩家留在 limbo，看到「认证服务暂时不可用」。`RestartTrigger` 随即做一次健康检查；只有健康检查确认是传输层失败（不可达、超时，`transportFailure=true`）且 `RestartPolicy` 允许时才写入 `restart.request-path`。网站有响应但响应体不符合契约、或 health 返回非 200 时只记录 WARN，不写文件。文件内容形如：

```text
reason=website-unreachable
requested-at=2026-11-07T12:34:56Z
```

策略默认 10 分钟冷却、滚动 1 小时内最多 2 次；被拒绝的尝试不会重置冷却或占用额度。超限、探活异常、文件写入失败都只记录告警，不会向登录流程抛异常。systemd 示例：

```ini
# /etc/systemd/system/hydwind-restart.path
[Path]
PathModified=/run/hydwind/restart-request

[Install]
WantedBy=multi-user.target
```

```ini
# /etc/systemd/system/hydwind-restart.service
[Unit]
Description=Restart Hydwind website on request
After=hydwind-web.service

[Service]
Type=oneshot
# 重启的是网站，不是代理。重启代理会踢掉所有玩家，而且插件里的
# 重启冷却与配额只存在内存中，会随代理重启一起清零。
# 先重启，成功后删除请求文件；失败时保留文件以便 path 单元重试。
ExecStart=/usr/bin/systemctl restart hydwind-web.service
ExecStart=/usr/bin/rm -f /run/hydwind/restart-request
```

> 这套 unit 与 `hydwind-web/deploy/` 下的同名文件是同一对，直接复制那里的一份，
> 不要在两处各写一份（文件名相同会互相覆盖）。

## 命令

- `/login <登录码>`：在 limbo 中完成认证。命令由 LimboAPI 吞掉并交给 `onChat` 解析，不经过 Velocity `CommandManager`；`/login` 注册为 limbo 命令仅为客户端 Tab 补全。为兼容聊天直发，不带前导 `/` 的 `login` / `hydwind` 也能识别。
- `/hydwind status`：查看 limbo 玩家数、玩家名（最多 20 个）、待认证/已认证会话、离线宽限数、网站健康、重启额度。
- `/hydwind reload`：热重载 `config.yml`；进行中的会话与重启计数保留，断线宽限立即更新。提示间隔与登录超时只对新会话生效——已进服玩家的登录截止时刻在会话创建时固定，reload 调大不会延长其超时。
- `/hydwind approve <玩家>`：手动放行未认证会话并转到主服。顺序是**先把会话写成 AUTHENTICATED，再转服**。
- `/hydwind kick <玩家>`：清理会话并断开 limbo 或主服中的玩家。顺序是**先移除会话，再断开**；反过来会让被踢玩家在宽限内重连时跳过登录。

完整的手工验收项见 [docs/manual-test.md](docs/manual-test.md)。