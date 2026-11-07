# HydwindGate

氢风进服门禁的领域词汇。实现细节不写在这里。

## 账号

**账号（account）**:
网站上的玩家账号，由 QQ 机器人注册创建。账号名等于游戏 ID，另有仅用于展示的中文别名。
_Avoid_: 用户、登录名

## 游戏 ID

**游戏 ID（game ID）**:
玩家在 Minecraft 中的用户名，ASCII 字母、数字、下划线，3–16 位。离线模式下也是玩家身份的唯一依据。
_Avoid_: 昵称、中文名

## 别名

**别名（nickname）**:
账号上的中文显示名，只用于提示和展示，不参与身份匹配。
_Avoid_: 游戏名、ID

## 门禁

**门禁（gate）**:
HydwindGate 对未认证玩家的拦截与放行机制，包含 limbo 拦截、登录提示和校验。
_Avoid_: 登录插件、白名单

## limbo

**limbo**:
LimboAPI 创建的虚拟世界。未认证玩家在转服前停留在这里，只能看到登录提示，不能连接任何后端服务器。
_Avoid_: 冻结世界、等候室

## 登录码

**登录码（login code）**:
网站在玩家登录后生成的一次性登录码，由两个颜文字拼接而成，5 分钟有效。插件侧由 `LoginCode`（原 `KaomojiCode`）统一归一化，同时处理登录码与备用码。
_Avoid_: 密码、验证码（太泛）

## 备用码

**备用码（fallback code）**:
与登录码绑定同一登录请求的纯 ASCII 一次性码，用于无法粘贴颜文字时手动输入。
_Avoid_: 备用密码

## 登录流程

**登录流程（login flow）**:
从进服创建会话、提交登录码、网站校验到转服或超时的完整决策序列，由纯逻辑 `LoginFlow` 编排，平台 Adapter 只执行发消息、转服和断开。
_Avoid_: 登录逻辑、登录管理器（旧 Paper 实现）

## 登录会话

**登录会话（login session）**:
一个玩家连接从进服到退出的认证状态，四态：未认证（PENDING）、验证中（VERIFYING）、已认证（AUTHENTICATED）、已超时（TIMED_OUT）。每个会话在进服时固定自己的登录截止时刻。
_Avoid_: 登录状态、token

## 登录截止

**登录截止（login deadline）**:
登录会话创建时按「进服时刻 + `auth.login-timeout-seconds`」算出的截止时刻，写到会话里后不再改变。per-player 定时任务与 60 秒兜底 sweep 都以它为准，reload 调大超时不会延长已有会话。
_Avoid_: 超时时间（配置值）、过期时间

## 校验编号

**校验编号（verification id）**:
每次成功进入验证（PENDING → VERIFYING）时由会话分配的递增编号（`0` 表示尚未提交）；编号按玩家 UUID 跨会话单调递增，重连后不从 1 重来。网站结果只有编号匹配且会话仍在验证中才会被接受，迟到或重复的结果直接丢弃。
_Avoid_: 请求 ID、token

## 管理动作

**管理动作（admin action）**:
管理员对单个玩家执行的一次 approve 或 kick，由 `AdminActionService` 统一维护“先写会话、再转服/断开”的顺序不变量。
_Avoid_: 管理命令、管理员操作（命令只是入口）

## 命令语法

**命令语法（command grammar）**:
`/hydwind` 子命令名、参数与补全规则的唯一来源，Velocity 命令入口和 limbo `onChat` 共用同一份定义。
_Avoid_: 命令解析器（只覆盖解析）、命令表

## 断线宽限

**断线宽限（reconnect grace）**:
同一 UUID、同一 IP 的玩家断线后 5 分钟内重连，可跳过重新登录。
_Avoid_: 自动登录、记住我

## 运行快照

**运行快照（runtime snapshot）**:
一次配置代际里成套使用的配置、文案、网站客户端与重启触发器；热重载整体替换快照，读取方在单次操作内复用同一份。
_Avoid_: 配置对象、运行时状态（太泛）

## 代际

**代际（generation）**:
一次启动或 reload 构建出的一整套运行时依赖及其生命周期。`RuntimeHolder` 一次发布新代际并返回旧代际，旧代际发布后关闭；读取方在单次操作内只使用同一代际。
_Avoid_: 版本、配置副本

## 网站

**网站（website）**:
`hydwind-web`，提供账号、`/gate` 页面和插件校验接口。
_Avoid_: 后端、服务端（与 Minecraft 服务端混淆）

## 受控重启

**受控重启（controlled restart）**:
插件在网站传输层不可达（健康检查 `transportFailure`）时触发的一次网站重启，受冷却和次数上限约束。
_Avoid_: 自动重启（太泛）

## 传输层失败

**传输层失败（transport failure）**:
verify 请求没有拿到可用响应（连接失败、超时或 5xx），或 health 探测在网络层失败。它与契约不兼容分开处理：verify 的 5xx 视为传输层失败；health 接口返回非 200（含 5xx）属于契约不兼容，只告警、不触发重启。只有传输层失败才可能触发受控重启，玩家看到「认证服务暂时不可用」。
_Avoid_: 网站故障（太泛）、接口错误

## 契约不兼容

**契约不兼容（contract mismatch）**:
网站给出了 HTTP 响应，但状态码、响应体字段或结构与插件约定不一致；按配置异常提示玩家，只告警，不触发受控重启。
_Avoid_: 网站不可达、接口错误（不可达属于传输层失败）
