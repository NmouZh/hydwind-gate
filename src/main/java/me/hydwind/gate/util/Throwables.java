package me.hydwind.gate.util;

/**
 * 把异常及其 cause 链压缩成一行，供诊断日志使用。
 *
 * <p>只输出异常类名与 message，不包含堆栈；日志调用方仍可把异常对象作为
 * 最后一个参数传入以获得完整堆栈。自引用或过长的 cause 链会被截断。</p>
 */
public final class Throwables {

    private static final int MAX_CAUSES = 5;

    private Throwables() {
    }

    /** 生成如 {@code java.net.ConnectException: refused <- java.io.IOException: ...} 的描述。 */
    public static String describe(Throwable throwable) {
        if (throwable == null) {
            return "-";
        }
        StringBuilder description = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < MAX_CAUSES) {
            if (depth > 0) {
                description.append(" <- ");
            }
            description.append(current.getClass().getName());
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                description.append(": ").append(message);
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                description.append(" <- [自引用]");
                return description.toString();
            }
            current = cause;
            depth++;
        }
        if (current != null) {
            description.append(" <- ...");
        }
        return description.toString();
    }
}