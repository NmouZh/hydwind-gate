# HydwindGate

氢风（Hydwind）Minecraft 服务器的进服门禁插件。玩家连上 **Velocity 代理**后进入 LimboAPI 虚拟 limbo，在浏览器打开网站登录拿到登录码，再回到游戏输入 `/login <码>`；验证通过后自动转服。

- 代理：Velocity 4.1.2+（Java 25，Minecraft 26.3 / 协议 777）
- 运行时依赖：LimboAPI 1.2.0（作为独立插件安装）
- 构建：Gradle Kotlin DSL + Shadow，Java 25
- 网站：`hydwind-web`（`/gate` 页面 + `/api/plugin/login/verify`）

设计文档：[docs/specs/0002-velocity-gate.md](docs/specs/0002-velocity-gate.md)
决策记录：[docs/adr/0001-velocity-over-paper.md](docs/adr/0001-velocity-over-paper.md)

## 状态

当前是 **V1（工程骨架与核心移植）**：

- 已从 Paper 插件迁移为单个 Velocity 插件；Paper/Bukkit 依赖与对话框、冻结相关代码已删除。
- 已移植 `WebsiteClient` / `HttpWebsiteClient`、`KaomojiCode`、`LoginSessionStore`、`RestartPolicy`、`RestartTrigger`、`PluginConfig` 与 `Messages`。
- `config.yml` 由 SnakeYAML 读取，SnakeYAML 与 Gson 通过 Shadow 打进插件 jar。
- limbo 登录流程（V2）与管理命令（V3）尚未实现；插件目前只加载配置并装配组件。

## 构建

构建统一在 GitHub Actions 上运行（见 `.github/workflows/ci.yml`），**本地不执行 Gradle 构建**，以免环境差异掩盖问题。

- 触发条件：推送到 `feat/**` / `fix/**`，或向 `feat/velocity-gate` 发起 Pull Request
- 手动触发：仓库 **Actions** 页面选择 **CI** → **Run workflow**
- 查看最近一次运行：`gh run list -R NmouZh/hydwind-gate --branch <分支名>`
- 跟踪运行：`gh run watch <run-id> -R NmouZh/hydwind-gate`

CI 使用 Java 25（temurin）与 Gradle 9.7，执行 `gradle build`，然后检查：

1. 产物 jar 内含 `velocity-plugin.json`（Velocity 注解处理器生成）。
2. `build/limboapi/limboapi-1.2.0.jar` 已被 `downloadLimboApi` 任务下载。

构建依赖：

- Velocity API：`com.velocitypowered:velocity-api:4.1.2-SNAPSHOT`（`https://repo.papermc.io/repository/maven-public/`），`compileOnly` + `annotationProcessor`。
- LimboAPI 1.2.0：Elytrium Maven 与 JitPack 均未发布该版本，Gradle 任务从 GitHub Release 下载到 `build/limboapi/`，以 `compileOnly(files(...))` 引用，**不 shade、不打包**。

## 部署

把构建产物（`build/libs/HydwindGate-0.1.0.jar`）和 `limboapi-1.2.0.jar` 一起放进 Velocity 的 `plugins/`。插件声明了 `@Dependency(id = "limboapi")`，缺少 LimboAPI 时 Velocity 会拒绝加载并给出原因。

配置在 `plugins/hydwind-gate/config.yml`，首次启动时自动生成。环境变量 `HYDWIND_PLUGIN_TOKEN` 非空时优先于 `website.token`。

## 配置

完整注释见 `src/main/resources/config.yml`。主要配置项：

```yaml
website:
  base-url: "http://103.236.99.253:62179"  # 网站根地址
  token: ""                                 # 建议改用环境变量
  health-path: "/api/health"
  connect-timeout-millis: 3000
  read-timeout-millis: 5000
auth:
  login-timeout-seconds: 300
  session-grace-seconds: 300
  prompt-interval-seconds: 10
limbo:
  world: "hydwind_limbo"
  spawn: "0, 64, 0"
server:
  main: "main"                              # velocity.toml 中的服务器名
restart:
  request-path: "/run/hydwind/restart-request"
  cooldown-seconds: 600
  window-seconds: 3600
  max-per-hour: 2
admin:
  permission: "hydwind.admin"
```

## 部署前必填项

- [ ] 在网站配置 `PLUGIN_TOKEN`，并在代理进程设置环境变量 `HYDWIND_PLUGIN_TOKEN`（不要写进仓库）。
- [ ] `website.base-url` 指向可访问的网站；正式开放前切换到 TLS。
- [ ] 安装 `limboapi-1.2.0.jar`；LimboAPI 与 Velocity 版本强绑定，升级 Velocity 前先确认兼容版本。
- [ ] 创建 `/run/hydwind` 且代理进程用户可写；或把 `restart.request-path` 改到可写路径。
- [ ] 配置 systemd `hydwind-restart.path` + `.service`，监听重启请求文件。
- [ ] 确认 `server.main` 与 `velocity.toml` 中的服务器名一致。
- [ ] 管理员组拥有 `hydwind.admin`（V3 起生效）。

## 受控重启

网站不可达时登录流程按 fail-closed 处理。`RestartTrigger` 会做一次健康检查；若健康检查失败且 `RestartPolicy` 允许，则写入 `restart.request-path`。策略默认 10 分钟冷却、每小时最多 2 次；超限、探活异常、文件写入失败都只记录告警。

## 命令

V1 尚未注册命令。limbo 中的 `/login <码>` 与 `/hydwind status|reload|approve|kick` 分别在 V2、V3 实现。