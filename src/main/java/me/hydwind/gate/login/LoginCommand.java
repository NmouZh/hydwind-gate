package me.hydwind.gate.login;

import me.hydwind.gate.auth.LoginCode;
import me.hydwind.gate.util.CommandLine;

/**
 * limbo 内由 {@code LimboSessionHandler#onChat} 收到的聊天/命令。
 *
 * <p>LimboAPI 会吞掉所有聊天与命令并原样交给 {@code onChat}，命令带前导 `/`。
 * 这里只做纯文本解析，不访问 Velocity 或网站。</p>
 */
public record LoginCommand(Status status, String code) {

    public enum Status {
        /** 合法的 {@code /login <码>}，{@link #code()} 非空。 */
        LOGIN,
        /** 不是登录命令，忽略。 */
        NOT_LOGIN,
        /** 是登录命令但没有登录码。 */
        EMPTY_CODE,
        /** 登录码超过网站接受的长度。 */
        TOO_LONG
    }

    public static LoginCommand parse(String raw) {
        CommandLine line = CommandLine.parse(raw);
        if (line == null || !line.command().equalsIgnoreCase("login")) {
            return new LoginCommand(Status.NOT_LOGIN, null);
        }

        String code = line.remainder();
        if (code.isEmpty()) {
            return new LoginCommand(Status.EMPTY_CODE, null);
        }
        if (code.codePointCount(0, code.length()) > LoginCode.MAX_LENGTH) {
            return new LoginCommand(Status.TOO_LONG, null);
        }
        return new LoginCommand(Status.LOGIN, code);
    }
}