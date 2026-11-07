# HydwindGate

氢风（Hydwind）Minecraft 服务器的进服门禁插件：玩家用网站账号在浏览器登录，复制验证码回到游戏，验证通过后才解除冻结。

- 服务端：Paper 26.3
- Java：25
- 构建：Gradle Kotlin DSL
- 网站：`hydwind-web`（`/gate` 页面 + `/api/plugin/login/verify`）

设计文档：[docs/specs/0001-login-gate.md](docs/specs/0001-login-gate.md)

## 构建

构建统一在 GitHub Actions 上运行（见 `.github/workflows/ci.yml`），**本地不执行 Gradle 构建**，以免环境差异掩盖问题。

- 触发条件：推送到 `feat/**`，或向 `feat/login-gate` 发起 Pull Request
- 手动触发：仓库 **Actions** 页面选择 **CI** → **Run workflow**
- 查看最近一次运行：`gh run list -R NmouZh/hydwind-gate --branch <分支名>`
- 跟踪运行：`gh run watch <run-id> -R NmouZh/hydwind-gate`

CI 使用 Java 25（temurin）与 Gradle 9.7，执行 `gradle build`，并检查构建产物 jar 内包含 `plugin.yml`。

## 状态

工程骨架与 CI 已就绪，登录认证流程待实现。
