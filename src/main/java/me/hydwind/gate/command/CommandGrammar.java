package me.hydwind.gate.command;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import me.hydwind.gate.util.CommandLine;

/**
 * {@code /hydwind} 子命令语法的唯一来源。
 *
 * <p>Velocity 的 {@code CommandManager} 与 limbo 内的 {@code onChat} 都从这里解析；
 * 补全列表与“是否需要目标玩家”的判断由同一份 {@link #SPECS} 派生，避免两处规则漂移。</p>
 */
public final class CommandGrammar {

    /** 命令名（不含前导斜杠）。 */
    private static final String COMMAND_NAME = "hydwind";

    /**
     * 单个子命令的规格。
     *
     * @param name           子命令名，小写
     * @param requiresTarget 是否要求一个目标玩家名
     */
    public record Spec(String name, boolean requiresTarget) {

        public Spec {
            Objects.requireNonNull(name, "name");
        }
    }

    /** 全部子命令；顺序即补全顺序。新增可执行 {@link AdminCommandRequest.Type} 时必须同步这里。 */
    public static final List<Spec> SPECS = List.of(
            new Spec("status", false),
            new Spec("reload", false),
            new Spec("approve", true),
            new Spec("kick", true));

    private CommandGrammar() {
    }

    /**
     * 解析 {@code CommandManager} 给出的参数数组（不含命令别名）。
     *
     * <p>status/reload 不接受多余参数；approve/kick 缺目标时仍返回对应类型，
     * 由执行器输出各自的用法提示。</p>
     */
    public static AdminCommandRequest parseArgs(String... args) {
        if (args == null || args.length == 0 || args[0] == null || args[0].isBlank()) {
            return new AdminCommandRequest(AdminCommandRequest.Type.USAGE, null);
        }

        Optional<Spec> match = find(args[0].trim());
        if (match.isEmpty()) {
            return new AdminCommandRequest(AdminCommandRequest.Type.UNKNOWN, null);
        }

        Spec spec = match.get();
        if (!spec.requiresTarget()) {
            return args.length == 1
                    ? new AdminCommandRequest(typeOf(spec), null)
                    : new AdminCommandRequest(AdminCommandRequest.Type.USAGE, null);
        }

        if (args.length > 2) {
            return new AdminCommandRequest(AdminCommandRequest.Type.USAGE, null);
        }
        if (args.length == 1) {
            return new AdminCommandRequest(typeOf(spec), null);
        }
        String target = args[1] == null ? "" : args[1].trim();
        return new AdminCommandRequest(typeOf(spec), target.isEmpty() ? null : target);
    }

    /**
     * 解析 limbo {@code onChat} 收到的原始行。
     *
     * @return 不是 {@code /hydwind}（或直接 {@code hydwind}）开头的行返回
     *         {@link Optional#empty()}，调用方应继续按登录码处理
     */
    public static Optional<AdminCommandRequest> parseLine(String raw) {
        CommandLine line = CommandLine.parse(raw);
        if (line == null || !line.command().equalsIgnoreCase(COMMAND_NAME)) {
            return Optional.empty();
        }

        String[] args = line.splitArguments();
        return Optional.of(args.length == 0 ? parseArgs() : parseArgs(args));
    }

    /** 按大小写不敏感前缀过滤子命令；{@code null} 视为空前缀（返回全部）。 */
    public static List<String> suggestSubcommands(String prefix) {
        String folded = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return SPECS.stream()
                .map(Spec::name)
                .filter(name -> name.startsWith(folded))
                .toList();
    }

    /** 该子命令类型是否需要一个目标玩家名；非子命令类型（USAGE/UNKNOWN）返回 {@code false}。 */
    public static boolean requiresTarget(AdminCommandRequest.Type type) {
        Objects.requireNonNull(type, "type");
        return find(type.name()).map(Spec::requiresTarget).orElse(false);
    }

    private static Optional<Spec> find(String name) {
        String folded = name.toLowerCase(Locale.ROOT);
        return SPECS.stream()
                .filter(spec -> spec.name().equals(folded))
                .findFirst();
    }

    private static AdminCommandRequest.Type typeOf(Spec spec) {
        return AdminCommandRequest.Type.valueOf(spec.name().toUpperCase(Locale.ROOT));
    }
}
