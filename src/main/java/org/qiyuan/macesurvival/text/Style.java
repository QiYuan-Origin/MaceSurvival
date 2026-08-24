package org.qiyuan.macesurvival.text;

/**
 * mcpvp.club 那种阴影字的拼装工具。
 * 阴影靠 <shadow:#404040:1> 实现，这是 1.21.6+ 才支持的组件阴影颜色，
 * 低版本客户端只会忽略它、不会显示成乱码。
 */
public final class Style {

    public static final String SHADOW = "#404040";

    /** 白底阴影，用在 [R] [★] 这类标记上。 */
    public static final String WHITE = "#FFFFFF";
    /** 重锤主题色，整套插件的主色调。 */
    public static final String MACE = "#C9A227";
    public static final String DANGER = "#FF5555";
    public static final String SAFE = "#55FF7F";
    public static final String INFO = "#7FB4FF";
    public static final String MUTED = "#9AA0A6";

    private Style() {
    }

    /** 单色 + 阴影。 */
    public static String shadow(String hex, String content) {
        return "<" + hex + "><shadow:" + SHADOW + ":1>" + content + "</shadow></" + hex + ">";
    }

    public static String shadow(String content) {
        return shadow(WHITE, content);
    }

    /** 渐变 + 阴影。两端颜色刻意要求接近，色差过大很难看。 */
    public static String gradient(String fromHex, String toHex, String content) {
        return "<gradient:" + fromHex + ":" + toHex + "><shadow:" + SHADOW + ":1>"
                + content + "</shadow></gradient>";
    }

    /** [R] / [★] 这种方括号标记。 */
    public static String tag(String hex, String content) {
        return shadow(hex, "[" + content + "]");
    }

    /** 箱子星级用的实心星串，例如 ★★☆。 */
    public static String stars(int filled, int total) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < total; i++) {
            sb.append(i < filled ? '\u2605' : '\u2606');
        }
        return sb.toString();
    }
}
