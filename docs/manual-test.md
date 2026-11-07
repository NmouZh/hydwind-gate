# HydwindGate 手工验证清单（V4 · Velocity + LimboAPI）

> 自动化测试只覆盖纯逻辑与 HTTP 契约（命令解析、文案映射、会话状态/宽限、配置解析、重启策略、`WebsiteClient` 对本地 HTTP 桩）。limbo 内的真实客户端交互、转服与网络故障必须按本清单手工验证，**不在 CI 中运行**。
>
> 建议每次发布前完整走一遍，并在 `docs/daily/` 记录结果。

## 0. 准备

### 版本与安装

- [ ] Java 25（`java -version` 显示 25）。
- [ ] Velocity 4.1.2+（推荐最新 4.1.x）。
- [ ] LimboAPI 1.2.0，与 Velocity 版本配套。
- [ ] 客户端与后端均为 Minecraft 26.3（协议 777）。
- [ ] 后端 Paper 26.3 服务器已在 `velocity.toml` 的 `servers` 中，且名称与 `server.main` 一致。

```bash
# 从 CI artifact 下载本插件（先看最近一次成功运行）
gh run list -R NmouZh/hydwind-gate --branch feat/velocity-gate --status success
gh run download <run-id> -R NmouZh/hydwind-gate -n hydwind-gate

# 下载 LimboAPI 1.2.0
curl -fL -o plugins/limboapi-1.2.0.jar \
  https://github.com/Elytrium/LimboAPI/releases/download/1.2.0/limboapi-1.2.0.jar

# plugins/ 中应同时存在：
#   HydwindGate-0.1.0.jar
#   limboapi-1.2.0.jar
```

### 运行环境

- [ ] 网站 `/api/health` 返回 `{"ok":true}`。
- [ ] 网站侧 `PLUGIN_TOKEN` 已配置；代理进程设置 `HYDWIND_PLUGIN_TOKEN`（值一致）。
- [ ] `website.base-url` 指向可访问网站，`/gate` 页面可打开。
- [ ] 网站准备账号 A、B；A 的账号名与测试游戏 ID 相同。
- [ ] `restart.request-path` 父目录可写（默认 `/run/hydwind`）。
- [ ] 可选：配置 systemd `hydwind-restart.path` 监听 `restart.request-path`，用于验证受控重启。

## 1. 未认证玩家进入 limbo

- [ ] 以未登录游戏 ID 连接：不被直接踢出，而是进入 limbo 虚拟世界。
- [ ] 玩家不会掉入虚空（插件调用 `disableFalling`）。
- [ ] 聊天栏出现「氢风门禁」标题与登录说明。
- [ ] 说明中的网站地址可点击；点击后浏览器打开 `website.base-url/gate`。
- [ ] 每隔 `auth.prompt-interval-seconds`（默认 10 秒）重复提示，直到认证或超时。
- [ ] 客户端输入 `/login` 时能出现 Tab 补全（仅命令名）。
- [ ] limbo 内聊天、其他命令不会到达 Velocity `CommandManager`，也不会发送给后端。

## 2. `/login` 成功路径

- [ ] 在网站 `/gate` 登录账号 A，复制颜文字登录码。
- [ ] limbo 中输入 `/login <颜文字码>`：先看到「正在验证登录码」。
- [ ] 随后显示成功文案（优先中文别名），并自动连接到 `server.main`。
- [ ] 后端服务器正常收到玩家；玩家可以移动、聊天、执行命令。
- [ ] 再执行 `/login <码>`：提示已完成认证（若仍处于 limbo）。

## 3. 备用码与解析细节

- [ ] `/login HWG-XXXX`（ASCII 备用码，大小写混写）成功。
- [ ] `/login` 无参数：提示先填写登录码，不请求网站。
- [ ] `/login <超过 64 字符的码>`：提示「登录码过长」，不请求网站。
- [ ] 前后多余空格不影响解析；颜文字内部的空格不被截断。

## 4. 错误码

逐项确认玩家仍在 limbo，且文案准确：

- [ ] `invalid_code`：随机错误码 → 「登录码不存在」。
- [ ] `expired`：生成码后等待超过 5 分钟 → 「登录码已过期」。
- [ ] `used`：同一个码成功一次后再提交 → 「登录码已被使用」。
- [ ] `game_id_mismatch`：用账号 B 的码在账号 A 的游戏 ID 下提交 → 「网站账号名与你的游戏 ID 不一致」；若网站返回 `expectedGameId`，文案点名该 ID。
- [ ] `401`：临时填错插件令牌并重载 → 玩家看到「认证服务配置异常」，控制台有 401 告警，不触发重启。
- [ ] 恢复正确令牌后再次提交有效码：成功。

## 5. 429 限流

- [ ] 快速连续提交错误码，直到网站返回 429。
- [ ] 玩家看到「尝试过多，请 X 秒后再试」；X 来自 `retryAfterMs` 或 `Retry-After`。
- [ ] 响应没有重试时间时显示「尝试过多，请稍后再试」。
- [ ] 限流**不**触发受控重启（`restart.request-path` 不应出现）。

## 6. 超时踢出

- [ ] 未登录玩家在 limbo 挂机超过 `auth.login-timeout-seconds`（默认 300 秒）。
- [ ] 玩家被踢出，断开原因包含「登录超时」。
- [ ] 超时后迟到的网站成功响应不会把玩家放行（会话已是 `TIMED_OUT`）。
- [ ] 再次连接：重新进入 limbo。

## 7. 断线宽限

- [ ] 已认证玩家断线后 5 分钟内、同 UUID + 同 IP 重连：直接进入后端，不出现 limbo。
- [ ] 更换 IP 后重连：重新进入 limbo。
- [ ] 超过 `auth.session-grace-seconds` 后重连：重新进入 limbo。
- [ ] 代理重启后重连：会话清空（仅内存），重新进入 limbo。
- [ ] 未认证玩家断线后重连：不享受宽限，重新进入 limbo。
- [ ] 宽限过期记录由每分钟的维护任务清理，`/hydwind status` 或日志可见。

## 8. 后端隔离

- [ ] limbo 中的玩家无法通过任何方式连接到后端（`ServerPreConnectEvent` 拒绝）。
- [ ] 没有会话的玩家尝试直连后端被拒绝。
- [ ] 已认证玩家断线后在宽限内重新连接，可以再次连接到后端。

## 9. 网站不可达（fail-closed）

- [ ] 停止网站或断开网络，limbo 中提交有效码。
- [ ] 玩家看到「认证服务暂时不可用，请稍后重试」，**仍留在 limbo**。
- [ ] 控制台出现网站不可达与受控重启日志。
- [ ] 恢复网站后再次提交有效码：登录成功。

## 10. 受控重启文件与冷却

- [ ] 停止网站，limbo 中提交有效码；健康检查也失败且策略允许时，`restart.request-path` 出现。
- [ ] 文件内容包含：
  - `reason=website-unreachable`
  - `requested-at=<ISO-8601 时间>`
- [ ] 若配置了 systemd `hydwind-restart.path`，文件出现后 `hydwind-restart.service` 被触发一次。
- [ ] 冷却期内（默认 600 秒）再次制造不可达：文件不会再次写入，日志出现「被策略拒绝（冷却中或已达到每小时上限）」。
- [ ] 滚动窗口内（默认 3600 秒）第 3 次触发被拒绝；窗口滑出后额度恢复。
- [ ] `/hydwind status` 的重启额度与下次允许时间与上述行为一致。
- [ ] `restart.request-path` 指向不可写目录时：只记录 `WARN`，登录流程不受影响。

## 11. `/hydwind` 管理命令

用一个拥有 `hydwind.admin` 权限的管理员账号，分别在主服与 limbo 中执行（limbo 内命令由 `onChat` 解析，不经过 `CommandManager`）：

- [ ] `/hydwind status`：
  - [ ] Limbo 中玩家数。
  - [ ] 待认证（PENDING/VERIFYING）与已认证（含离线宽限）数量。
  - [ ] 网站健康（`uptime` 秒）或「不可达」。
  - [ ] 重启额度；额度为 0 时显示「下次允许 HH:mm」。
  - [ ] 管理员权限名。
- [ ] `/hydwind reload`：修改 `auth.prompt-interval-seconds` 或 `messages.gate-title` 后执行，日志出现重载成功；进行中的 limbo 玩家不被打断，`/hydwind status` 的重启额度不被清零。
- [ ] `/hydwind approve <玩家>`：
  - [ ] 对 limbo 中未认证玩家执行：玩家收到「管理员已手动放行你的登录」，随后转到 `server.main`。
  - [ ] 重复执行：提示「玩家已经通过认证」。
  - [ ] 对不存在的玩家执行：提示「没有找到玩家 ... 的登录会话」。
- [ ] `/hydwind kick <玩家>`：
  - [ ] 对 limbo 中玩家执行：玩家被断开，会话与宽限记录被清理。
  - [ ] 对主服中玩家执行：玩家被断开，会话被清理。
  - [ ] 对不存在玩家执行：提示「没有找到玩家 ... 的登录会话」。
- [ ] `/hydwind` / `/hydwind unknown`：分别显示用法与未知子命令提示。
- [ ] 无 `hydwind.admin` 权限的玩家执行 `/hydwind status`：只收到无权限提示。
- [ ] 主服内输入 `/hydwind ` 时能看到子命令 Tab 补全；`approve` / `kick` 后能补全在线玩家名。

## 12. 配置消费

- [ ] 修改 `auth.prompt-interval-seconds` 并重载：提示间隔变化。
- [ ] 修改 `auth.login-timeout-seconds` 并重载：超时时间变化。
- [ ] 修改 `limbo.spawn` 后重启代理：新出生点生效；仅重载时日志提示需要重启。
- [ ] `server.main` 指向不存在的服务器时登录成功：日志报错且玩家被断开，不会卡在 limbo。
- [ ] 删除/未安装 `limboapi-1.2.0.jar`：Velocity 因插件依赖拒绝加载并给出明确原因。
- [ ] `website.token` 留空且未设置 `HYDWIND_PLUGIN_TOKEN`：启动日志告警，校验返回 401。
- [ ] `website.base-url` 留空：插件拒绝启用并记录明确错误。

## 13. 收尾

- [ ] 控制台无插件异常堆栈（策略拒绝与重启文件写入失败属于预期 `WARN`）。
- [ ] 所有 limbo 内玩家最终要么认证转服、要么超时/断线被清理。
- [ ] 人工确认 26.3 客户端能识别可点击 URL（Adventure `ClickEvent.openUrl`）。
- [ ] 记录本次验证使用的 Velocity/LimboAPI/客户端版本与结果。