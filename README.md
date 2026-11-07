# HydwindGate

氢风（Hydwind）Minecraft 服务器的进服门禁插件：玩家用网站账号在浏览器登录，复制验证码回到游戏，验证通过后才解除冻结。

- 服务端：Paper 26.3
- Java：25
- 构建：Gradle Kotlin DSL
- 网站：`hydwind-web`（`/gate` 页面 + `/api/plugin/login/verify`）

设计文档：[docs/specs/0001-login-gate.md](docs/specs/0001-login-gate.md)
手工验证清单：[docs/manual-test.md](docs/manual-test.md)

## 构建

构建统一在 GitHub Actions 上运行（见 `.github/workflows/ci.yml`），**本地不执行 Gradle 构建**，以免环境差异掩盖问题。

- 触发条件：推送到 `feat/**`，或向 `feat/login-gate` 发起 Pull Request
- 手动触发：仓库 **Actions** 页面选择 **CI** → **Run workflow**
- 查看最近一次运行：`gh run list -R NmouZh/hydwind-gate --branch <分支名>`
- 跟踪运行：`gh run watch <run-id> -R NmouZh/hydwind-gate`

CI 使用 Java 25（temurin）与 Gradle 9.7，执行 `gradle build`，并检查构建产物 jar 内包含 `plugin.yml`。

### 本地联调（仅在你自己的机器上）

仓库接入 `xyz.jpenilla.run-paper` 3.1.0，可执行 `gradle runServer` 自动下载并启动 Paper 26.3。CI 只执行 `build`，不会启动服务器。若该插件与你的 Gradle / Paper 版本不兼容，可手动运行：

```bash
mkdir -p run/plugins
curl -L -o run/paper-26.3-244.jar \
  https://api.papermc.io/v2/projects/paper/versions/26.3/builds/244/downloads/paper-26.3-244.jar
cp build/libs/HydwindGate-*.jar run/plugins/
cd run && java -jar paper-26.3-244.jar --nogui
```

## 命令

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/login` | `hydwindgate.login`（默认所有玩家） | 重新打开登录对话框 |
| `/login <登录码>` | `hydwindgate.login` | 直接提交颜文字码或 `HWG-XXXX` 备用码 |
| `/hydwind status` | `hydwind.admin`（默认 op） | 待认证/已认证数量、网站健康状态、重启剩余额度 |
| `/hydwind reload` | `hydwind.admin` | 热重载配置，不中断进行中的会话、不解冻已冻结玩家 |
| `/hydwind approve <玩家>` | `hydwind.admin` | 手动放行，置为已认证并解冻 |
| `/hydwind kick <玩家>` | `hydwind.admin` | 踢出并清理会话 |

`/hydwind` 的权限名可通过 `admin.permission` 修改；修改后需要给对应组授予该权限。

## 配置

完整注释见 `src/main/resources/config.yml`。主要配置项：

```yaml
website:
  base-url: "http://103.236.99.253:62179"  # 网站根地址
  token: ""                                 # 建议改用环境变量
  health-path: "/api/health"
  connect-timeout-millis: 3000
  read-timeout-millis: 5000
login:
  timeout-seconds: 300
  reconnect-grace-seconds: 300
restart:
  request-file: "/run/hydwind/restart-request"
  cooldown-seconds: 600
  window-seconds: 3600
  max-per-window: 2
admin:
  permission: "hydwind.admin"
spawn:
  world: "world"
  x: 0.0
  y: 64.0
  z: 0.0
```

环境变量 `HYDWIND_PLUGIN_TOKEN` 非空时优先于 `website.token`。

## 部署前必填项

- [ ] 在网站配置 `PLUGIN_TOKEN`，并在服务器设置环境变量 `HYDWIND_PLUGIN_TOKEN`（不要写进仓库）。
- [ ] `website.base-url` 指向可访问的网站；正式开放前切换到 TLS。
- [ ] 创建 `/run/hydwind` 且运行 Minecraft 的用户可写；或把 `restart.request-file` 改到可写路径。
- [ ] 配置 systemd `hydwind-restart.path` + `.service`：监听重启请求文件，出现即重启网站并删除文件。没有该 unit 时插件仍会写文件，但不会真正重启。
- [ ] 确认 `spawn.world` 存在；配置错误时回退到第一个已加载世界并告警。
- [ ] 确认管理员组拥有 `hydwind.admin`。
- [ ] 按 [docs/manual-test.md](docs/manual-test.md) 完成游戏内验证。

## 受控重启

网站不可达时，登录流程按 fail-closed 处理（保持冻结）。同时 `RestartTrigger` 会做一次健康检查；若健康检查失败且 `RestartPolicy` 允许，则写入 `restart.request-file`。策略为 10 分钟冷却、每小时最多 2 次；超限、探活异常、文件写入失败都只记录告警，不影响玩家登录。

## 状态

登录门禁主流程、`/login`、`/hydwind` 管理命令与受控重启已实现。游戏内行为需按手工清单验证。