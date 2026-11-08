package me.hydwind.gate.flow;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import me.hydwind.gate.auth.LoginCode;
import me.hydwind.gate.auth.LoginSession;
import me.hydwind.gate.auth.LoginSessionStore;
import me.hydwind.gate.login.LoginCommand;
import me.hydwind.gate.login.LoginFeedback;
import me.hydwind.gate.ui.Messages;
import me.hydwind.gate.web.VerifyResult;
import me.hydwind.gate.web.WebsiteClient;

/**
 * 登录流程编排：解析登录命令、推进会话状态、调用网站校验并把结果翻译成可执行的决定。
 *
 * <p>纯逻辑，不 import Velocity / LimboAPI。发消息、转服、断开连接等平台动作由
 * {@code LoginSessionHandler} 根据返回值执行。所有时间都来自注入的 {@link Clock}。</p>
 */
public final class LoginFlow {

    private final LoginSessionStore sessions;
    private final WebsiteClient website;
    private final Messages messages;
    private final Clock clock;

    public LoginFlow(LoginSessionStore sessions, WebsiteClient website,
                     Messages messages, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.website = Objects.requireNonNull(website, "website");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 处理 limbo 内收到的一行聊天/命令。
     *
     * <p>登录会话由 Adapter 在玩家进服时通过 {@code LoginSessionStore#join} 创建；
     * 这里<b>不会</b>补建会话，会话不存在时直接拒绝，避免绕过进服流程。</p>
     */
    public SubmitResult submit(LoginIdentity identity, String rawLine) {
        Objects.requireNonNull(identity, "identity");

        LoginCommand command = LoginCommand.parse(rawLine);
        if (command.status() == LoginCommand.Status.NOT_LOGIN) {
            return simple(SubmitKind.IGNORED);
        }

        Optional<LoginSession> session = sessions.find(identity.uuid());
        if (session.isPresent()) {
            // 连接代际或 IP 对不上：这是旧连接/顶替连接在借受害者的会话提交。
            if (!sessions.isCurrentConnection(identity.uuid(), identity.connectionId())
                    || !session.get().ip().equals(identity.ip())) {
                return simple(SubmitKind.REJECTED);
            }
            if (session.get().isAuthenticated()) {
                return simple(SubmitKind.ALREADY_AUTHENTICATED);
            }
        }

        return switch (command.status()) {
            case EMPTY_CODE -> simple(SubmitKind.EMPTY_CODE);
            case TOO_LONG -> simple(SubmitKind.TOO_LONG);
            case LOGIN -> beginVerification(identity, command.code());
            case NOT_LOGIN -> simple(SubmitKind.IGNORED); // 上面已处理，保留穷尽分支
        };
    }

    /**
     * 把网站校验结果应用到会话，并决定 Adapter 的后续动作。
     *
     * @param verificationId {@link #submit} 返回的编号，用于丢弃迟到结果
     * @param code           归一化后的登录码
     */
    public VerifyOutcome verify(LoginIdentity identity, long verificationId, String code) {
        Objects.requireNonNull(identity, "identity");

        VerifyResult result;
        try {
            result = website.verify(code, identity.gameId(), identity.uuid());
        } catch (RuntimeException exception) {
            // WebsiteClient 的契约要求不抛网络异常；保留防御，把意外异常当作网站不可达。
            // 这里没有 Logger，诊断日志由 WebsiteClient 实现或 Adapter 负责。
            result = VerifyResult.unreachable();
        }

        LoginFeedback feedback = LoginFeedback.from(result, messages, identity.gameId());
        if (feedback.authenticated()) {
            if (!sessions.authenticationSucceeded(
                    identity.uuid(), identity.connectionId(), verificationId, clock.instant())) {
                // 会话已超时、已断线或编号不匹配：迟到的成功结果不得放行，
                // 也不再向玩家播报成功文案。
                return new VerifyOutcome(false, false, false, "", null);
            }
            return new VerifyOutcome(true, false, false, feedback.message(), result.status());
        }

        // 失败结果也必须归属到发起它的那次校验：玩家可能已经重试并产生了新编号。
        // 迟到的失败结果不得改写新尝试的状态，也不该向玩家播报旧文案或触发重启。
        if (!sessions.authenticationFailed(
                identity.uuid(), identity.connectionId(), verificationId, clock.instant())) {
            return new VerifyOutcome(false, false, false, "", null);
        }
        return new VerifyOutcome(false, feedback.triggerRestart(),
                isContractMismatch(result.status()), feedback.message(), result.status());
    }

    /** 会话自己的登录截止时刻已到：标记 TIMED_OUT。 */
    public boolean timeout(LoginIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return sessions.timeOut(identity.uuid(), identity.connectionId(), clock.instant());
    }

    private SubmitResult beginVerification(LoginIdentity identity, String code) {
        if (sessions.find(identity.uuid()).isEmpty()) {
            return simple(SubmitKind.REJECTED);
        }

        OptionalLong verificationId = sessions.beginVerification(
                identity.uuid(), identity.connectionId(), clock.instant());
        if (verificationId.isEmpty()) {
            // 只有 PENDING 能进入 VERIFYING；验证中、已超时、已认证都会被拒绝。
            return simple(SubmitKind.REJECTED);
        }
        return new SubmitResult(SubmitKind.VERIFYING, verificationId.getAsLong(),
                LoginCode.normalize(code));
    }

    /** 网站响应与插件契约不兼容：只提示配置异常，不应触发重启。 */
    private static boolean isContractMismatch(VerifyResult.Status status) {
        return status == VerifyResult.Status.CONTRACT_MISMATCH;
    }

    private static SubmitResult simple(SubmitKind kind) {
        return new SubmitResult(kind, 0L, null);
    }
}