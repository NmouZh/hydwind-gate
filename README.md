# HydwindGate

氢风（Hydwind）Minecraft 服务器的进服门禁插件。玩家连上 **Velocity 代理**后进入 LimboAPI 虚拟 limbo，在浏览器打开网站登录拿到登录码，再回到游戏输入 `/login <码>`；验证通过后自动转服。

- 代理：Velocity 4.1.2+（Java 25，Minecraft 26.3 / 协议 777）
- 运行时依赖：LimboAPI 1.2.0（作为独立插件安装）
- 构建：Gradle Kotlin DSL + Shadow，Java 25
- 网站：`hydwind-web`（`/gate` 页面 + `/api/plugin/login/verify`）

设计文档：[docs/specs/0002-velocity-gate.md](docs/specs/0002-velocity-gate.md)
决策记录：[docs/adr/0001-velocity-over-paper.md](docs/adr/0001-velocity-over-paper.md)

## 状态

当前是 **V4（测试、手工清单与文档）**；登录流程在 V2、管理命令在 V3 完成：

- 已从 Paper 插件迁移为单个 Velocity 插件；Paper/Bukkit 依赖与对话框、冻结相关代码已删除。
- 已移植 `WebsiteClient` / `HttpWebsiteClient`、`KaomojiCode`、`LoginSessionStore`、`RestartPolicy`、`RestartTrigger`、`PluginConfig` 与 `Messages`。
- `config.yml` 由 SnakeYAML 读取，SnakeYAML 与 Gson 通过 Shadow 打进插件 jar。
- `PreLoginEvent` 强制离线模式；`LoginLimboRegisterEvent` 决定宽限放行或进入 LimboAPI 虚拟 limbo。
- limbo 内发送可点击网站链接与说明，按 `auth.prompt-interval-seconds` 重复；`onChat` 解析 `/login <码>`，成功后转 `server.main`，失败按网站 reason 提示，5 分钟未登录踢出。
- `ServerPreConnectEvent` 拒绝未认证玩家连接任何后端；断线保留 5 分钟 UUID + IP 宽限。
- `/hydwind status|reload|approve|kick` 已实现；Velocity `CommandManager` 与 limbo `onChat` 共用同一执行器，权限由 `admin.permission` 控制。
- JUnit 5 覆盖码归一化、会话状态机与宽限、配置解析、重启策略与触发、网站 HTTP 契约、命令解析与状态/文案；limbo 真实交互按手工清单验证。

## 版本要求

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Velocity | 4.1.2+ | 构建使用 `velocity-api:4.1.2-SNAPSHOT`；升级前先确认 LimboAPI 有对应版本 |
| Java | 25 | 构建与运行都要求 Java 25 |
| Minecraft | 26.3（协议 777） | 客户端与后端均需 26.3；LimboAPI 1.2.0 在低于 777 的协议上会直接关闭代理 |
| LimboAPI | 1.2.0 | 独立插件，必须与 Velocity 版本配套 |
| 后端服务器 | Paper 26.3 | 名称需与 `velocity.toml` 的 `servers` 键、`server.main` 一致 |

## 构建

构建统一在 GitHub Actions 上运行（见 `.github/workflows/ci.yml`），**本地不执行 Gradle 构建**，以免环境差异掩盖问题。

- 触发条件：推送到 `feat/**` / `fix/**`，或向 `feat/velocity-gate` 发起 Pull Request
- 手动触发：仓库 **Actions** 页面选择 **CI** → **Run workflow**
- 查看最近一次运行：`gh run list -R NmouZh/hydwind-gate --branch <分支名>`
- 跟踪运行：`gh run watch <run-id> -R NmouZh/hydwind-gate`

CI 使用 Java 25（temurin）与 Gradle 9.7.0，执行 `gradle build`（包含 JUnit 5 测试），然后：

1. 在日志中逐个列出测试用例，并输出汇总行
   `JUnit results: N suites, M tests, 0 failures, 0 errors, 0 skipped`。
   测试结果为 0 或任一失败都会让 CI 失败。
2. 检查产物 jar 内含 `velocity-plugin.json`（Velocity 注解处理器生成）。
3. 检查 `build/limboapi/limboapi-1.2.0.jar` 已被 `downloadLimboApi` 任务下载。
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
- LimboAPI 1.2.0：Elytrium Maven 与 JitPack 均未发布该版本，Gradle 任务从 GitHub Release 下载到 `build/limboapi/`，以 `compileOnly(files(...))` 引用，**不 shade、不打包**。

## 安装 LimboAPI

LimboAPI 1.2.0 不在 Maven Central / Elytrium Maven 上，需要从 GitHub Release 手动下载：

```bash
curl -fL --create-dirs -o plugins/limboapi-1.2.0.jar \
  https://github.com/Elytrium/LimboAPI/releases/download/1.2.0/limboapi-1.2.0.jar
```

`plugins/` 最终应同时包含：

- `HydwindGate-0.1.0.jar`（本插件，来自 CI artifact）
- `limboapi-1.2.0.jar`（LimboAPI）

插件声明了 `@Dependency(id = "limboapi")`；缺少 LimboAPI 时 Velocity 会拒绝加载并给出原因。LimboAPI 本体是 AGPLv3，API 模块是 MIT，再分发时注意区别。

## 部署

1. 安装 Java 25 与 Velocity 4.1.2+，在 `velocity.toml` 配置至少一个名为 `server.main` 的后端服务器（ASCII 名，如 `main`）。
2. 把 `HydwindGate-0.1.0.jar` 与 `limboapi-1.2.0.jar` 放进 Velocity 的 `plugins/`（见上一节的下载方式）。
3. 启动代理，插件会在 `plugins/hydwind-gate/config.yml` 生成默认配置。
4. 设置环境变量 `HYDWIND_PLUGIN_TOKEN`，其值必须与网站侧 `PLUGIN_TOKEN` 一致；非空时优先于 `website.token`。
5. 重启代理，把 `velocity.toml` 中的 `forced-hosts` / 玩家入口指向该代理；插件会通过 `PreLoginEvent` 强制离线模式。

### 部署注意

- **版本强绑定**：LimboAPI 1.2.0 只兼容 Velocity 4.1.2+ 与协议 777（MC 26.3）。升级 Velocity 前先确认 LimboAPI 有对应版本，否则代理会拒绝启动或直接关闭连接。
- **令牌不要进仓库**：`website.token` 建议留空，用进程环境变量 `HYDWIND_PLUGIN_TOKEN` 注入；令牌错误时玩家看到「认证服务配置异常」，控制台有 401 告警。
- **重启目录可写**：`restart.request-path` 的父目录必须存在且代理进程可写（默认 `/run/hydwind`）；或改到可写路径。该文件由外部 systemd `path` unit 监听，插件本身不执行重启。
- **主服名必须存在**：`server.main` 与 `velocity.toml` 的 `servers` 键一致；认证成功但服务器名不存在时，玩家会被断开并记录错误日志。
- **会话只在内存**：认证会话与断线宽限不会持久化；代理重启后所有玩家需要重新登录。
- **limbo 配置需重启**：`/hydwind reload` 会热重载网站、超时、提示、重启与消息配置，但 `limbo.world` / `limbo.spawn` 的改动只在日志告警，需重启代理生效。
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
  login-timeout-seconds: 300                # 未登录踢出
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
  # {seconds}、{player}、{permission}、{count}、{grace}、{uptime}、
  # {remaining}、{time}、{error} 等占位符。
```

## 部署前必填项

- [ ] 在网站配置 `PLUGIN_TOKEN`，并在代理进程设置环境变量 `HYDWIND_PLUGIN_TOKEN`（不要写进仓库）。
- [ ] `website.base-url` 指向可访问的网站；正式开放前切换到 TLS。
- [ ] 安装 `limboapi-1.2.0.jar`；LimboAPI 与 Velocity 版本强绑定，升级 Velocity 前先确认兼容版本。
- [ ] 创建 `/run/hydwind` 且代理进程用户可写；或把 `restart.request-path` 改到可写路径。
- [ ] 配置 systemd `hydwind-restart.path` + `.service`，监听重启请求文件。
- [ ] 确认 `server.main` 与 `velocity.toml` 中的服务器名一致。
- [ ] 管理员组拥有 `hydwind.admin`。

## 受控重启

网站不可达时登录流程按 fail-closed 处理：玩家留在 limbo，看到「认证服务暂时不可用」。`RestartTrigger` 随即做一次健康检查；只有健康检查也失败且 `RestartPolicy` 允许时才写入 `restart.request-path`，内容形如：

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
[Service]
Type=oneshot
ExecStart=/bin/systemctl restart velocity
```

## 命令

- `/login <登录码>`：在 limbo 中完成认证。命令由 LimboAPI 吞掉并交给 `onChat` 解析，不经过 Velocity `CommandManager`；`/login` 注册为 limbo 命令仅为客户端 Tab 补全。
- `/hydwind status`：查看 limbo 玩家数、待认证/已认证会话、离线宽限数、网站健康、重启额度。
- `/hydwind reload`：热重载 `config.yml`；进行中的会话与重启计数保留。
- `/hydwind approve <玩家>`：手动放行未认证会话并转到主服。
- `/hydwind kick <玩家>`：清理会话并断开 limbo 或主服中的玩家。

完整的手工验收项见 [docs/manual-test.md](docs/manual-test.md)。