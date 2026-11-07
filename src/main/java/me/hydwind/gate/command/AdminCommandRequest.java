package me.hydwind.gate.command;

import java.util.Locale;
import java.util.Objects;

/**
 * 解析后的 {@code /hydwind} 请求。
 *
 * <p>Velocity {@code CommandManager} 传入已按空格切分的参数；limbo 内的
 * {@code onChat} 传入一整行原始聊天/命令。两个入口共用同一套规则，保证行为一致。</p>
 *
 * @param type   子命令类型
 * @param target 需要玩家名的子命令的目标；缺省时为 {@code null}，由执行器给出对应用法提示
 */
public record AdminCommandRequest(Type type, String target) {

    public enum Type {
        /** 不是 {@code /hydwind}，limbo 内应忽略。 */
        NONE,
        /** 没有子命令。 */
        USAGE,
        /** 未知子命令。 */
        UNKNOWN,
        STATUS,
        RELOAD,
        /** 需要目标玩家；{@link #target()} 可能为 {@code null}。 */
        APPROVE,
        /** 需要目标玩家；{@link #target()} 可能为 {@code null}。 */
        KICK
    }

    public AdminCommandRequest {
        Objects.requireNonNull(type, "type");
    }

    /** 解析 {@code CommandManager} 给出的参数数组（不含命令别名）。 */
    public static AdminCommandRequest parseArgs(String... args) {
        if (args == null || args.length == 0 || args[0] == null || args[0].isBlank()) {
            return new AdminCommandRequest(Type.USAGE, null);
        }

        String subcommand = args[0].trim().toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "status" -> args.length == 1
                    ? new AdminCommandRequest(Type.STATUS, null)
                    : new AdminCommandRequest(Type.USAGE, null);
            case "reload" -> args.length == 1
                    ? new AdminCommandRequest(Type.RELOAD, null)
                    : new AdminCommandRequest(Type.USAGE, null);
            case "approve" -> withTarget(Type.APPROVE, args);
            case "kick" -> withTarget(Type.KICK, args);
            default -> new AdminCommandRequest(Type.UNKNOWN, null);
        };
    }

    /**
     * 解析 limbo {@code onChat} 收到的原始行。只有以 {@code /hydwind}
     * （或直接 {@code hydwind}）开头的行才返回非 {@link Type#NONE}。
     */
    public static AdminCommandRequest parseLine(String raw) {
        if (raw == null) {
            return new AdminCommandRequest(Type.NONE, null);
        }
        String trimmed = raw.strip();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).strip();
        }
        if (trimmed.isEmpty()) {
            return new AdminCommandRequest(Type.NONE, null);
        }

        int separator = indexOfWhitespace(trimmed);
        String command = separator < 0 ? trimmed : trimmed.substring(0, separator);
        if (!command.equalsIgnoreCase("hydwind")) {
            return new AdminCommandRequest(Type.NONE, null);
        }

        String rest = separator < 0 ? "" : trimmed.substring(separator + 1).strip();
        return rest.isEmpty() ? parseArgs() : parseArgs(rest.split("\\s+"));
    }

    public boolean needsPlayerName() {
        return type == Type.APPROVE || type == Type.KICK;
    }

    private static AdminCommandRequest withTarget(Type type, String[] args) {
        if (args.length == 1) {
            return new AdminCommandRequest(type, null);
        }
        if (args.length > 2) {
            return new AdminCommandRequest(Type.USAGE, null);
        }
        String target = args[1] == null ? "" : args[1].trim();
        return new AdminCommandRequest(type, target.isEmpty() ? null : target);
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