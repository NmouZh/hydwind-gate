package me.hydwind.gate.util;

import java.util.Objects;

/**
 * limbo 聊天/命令的共享解析：去掉可选前导 {@code /}，按第一段空白拆出命令名与余下文本。
 *
 * <p>登录码可能包含空格（颜文字），因此余下文本不在这里切分；需要参数列表的调用方
 * 使用 {@link #splitArguments()}。</p>
 *
 * @param command   命令名（保留原始大小写）
 * @param remainder 命令之后的原始文本，已去掉首尾空白；没有参数时为空字符串
 */
public record CommandLine(String command, String remainder) {

    public CommandLine {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(remainder, "remainder");
    }

    /** @return 解析结果；输入为空或只有斜杠/空白时返回 {@code null} */
    public static CommandLine parse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).strip();
        }
        if (trimmed.isEmpty()) {
            return null;
        }

        int separator = indexOfWhitespace(trimmed);
        String command = separator < 0 ? trimmed : trimmed.substring(0, separator);
        String remainder = separator < 0 ? "" : trimmed.substring(separator + 1).strip();
        return new CommandLine(command, remainder);
    }

    /** 按任意空白切分余下文本；没有参数时返回空数组。 */
    public String[] splitArguments() {
        return remainder.isEmpty() ? new String[0] : remainder.split("\\s+");
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