package me.hydwind.gate.web;

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
}