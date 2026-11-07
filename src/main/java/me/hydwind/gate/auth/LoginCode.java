package me.hydwind.gate.auth;

import java.text.Normalizer;

/**
 * 网站签发的一次性码在插件侧的归一化与长度校验。
 *
 * <p>同时处理 GLOSSARY 中的两种码：由两个颜文字拼接的<b>登录码（login code）</b>，
 * 以及无法粘贴颜文字时手动输入的纯 ASCII <b>备用码（fallback code）</b>。
 * 两者都做 NFC 归一化、去掉首尾空白，并折叠 ASCII 字母大小写；颜文字本身不受影响。</p>
 */
public final class LoginCode {

    /** 网站登录码的长度上限（按 Unicode code point 计）。聊天输入上限更高，但登录码远低于它。 */
    public static final int MAX_LENGTH = 64;

    private LoginCode() {
    }

    /** 做 Unicode 归一化并只折叠 ASCII 字母的大小写，颜文字保持原样。 */
    public static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String normalized = Normalizer.normalize(code.strip(), Normalizer.Form.NFC);
        StringBuilder result = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char character = normalized.charAt(i);
            result.append(character >= 'a' && character <= 'z' ? (char) (character - ('a' - 'A')) : character);
        }
        return result.toString();
    }

    /** 在发给网站之前拒绝缺失、空白或超长的码。 */
    public static boolean isBlankOrTooLong(String code) {
        String normalized = normalize(code);
        return normalized == null || normalized.isBlank() || normalized.codePointCount(0, normalized.length()) > MAX_LENGTH;
    }
}
