package me.hydwind.gate.login;

/**
 * limbo 内由 {@code LimboSessionHandler#onChat} 收到的聊天/命令。
 *
 * <p>LimboAPI 会吞掉所有聊天与命令并原样交给 {@code onChat}，命令带前导 `/`。
 * 这里只做纯文本解析，不访问 Velocity 或网站。</p>
 */
public record LoginCommand(Status status, String code) {

    /** Minecraft 聊天/命令内容上限；登录码必须远低于它。 */
    public static final int MAX_CODE_LENGTH = 256;

    public enum Status {
        /** 合法的 {@code /login <码>}，{@link #code()} 非空。 */
        LOGIN,
        /** 不是登录命令，忽略。 */
        NOT_LOGIN,
        /** 是登录命令但没有登录码。 */
        EMPTY_CODE,
        /** 登录码超过聊天安全长度。 */
        TOO_LONG
    }

    public static LoginCommand parse(String raw) {
        if (raw == null) {
            return new LoginCommand(Status.NOT_LOGIN, null);
        }
        String trimmed = raw.strip();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).strip();
        }
        if (trimmed.isEmpty()) {
            return new LoginCommand(Status.NOT_LOGIN, null);
        }

        int separator = indexOfWhitespace(trimmed);
        String command = separator < 0 ? trimmed : trimmed.substring(0, separator);
        if (!command.equalsIgnoreCase("login")) {
            return new LoginCommand(Status.NOT_LOGIN, null);
        }

        String code = separator < 0 ? "" : trimmed.substring(separator + 1).strip();
        if (code.isEmpty()) {
            return new LoginCommand(Status.EMPTY_CODE, null);
        }
        if (code.length() > MAX_CODE_LENGTH) {
            return new LoginCommand(Status.TOO_LONG, null);
        }
        return new LoginCommand(Status.LOGIN, code);
    }

    private static int indexOfWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}