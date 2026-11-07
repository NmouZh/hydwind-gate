package me.hydwind.gate.web;

import java.net.URI;
import java.net.URISyntaxException;

/** URL 小工具。 */
public final class Urls {

    private Urls() {
    }

    /** 去掉 URL 末尾的一个或多个斜杠；{@code null} 原样返回。 */
    public static String stripTrailingSlashes(String url) {
        if (url == null) {
            return null;
        }
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }

    /**
     * 判断 {@code url} 是否可作为插件的网站根地址：必须是带主机名的绝对
     * http/https URI，且不包含查询串、片段或用户信息。
     *
     * <p>查询串与片段会破坏后续拼接的接口路径；用户信息（{@code user:pass@}）
     * 既没有用途，也会在诊断日志里泄露凭据，因此一并拒绝。方法只做纯判断，
     * 不访问网络，便于启动时快速失败。</p>
     */
    public static boolean isAbsoluteHttpUrl(String url) {
        if (url == null) {
            return false;
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            return uri.isAbsolute()
                    && scheme != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }
}