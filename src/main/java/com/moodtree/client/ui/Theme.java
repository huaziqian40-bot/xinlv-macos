/* 视觉主题：4 套预设（暖阳/夜晚/薄荷/樱花）+ 3 色自定义（背景/卡片/强调色）。
 * 与手机端（安卓 Theme.java）同一套 3 色体系：BG / CARD / ACCENT 可自定义，
 * INK / INK_SOFT / DIVIDER 根据背景亮度自动派生，保证任何配色下文字都清晰。
 * 字段是 static 可变值，各界面通过 Theme.xxx / 样式方法引用；切换后重建界面生效。 */
package com.moodtree.client.ui;

import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Background;
import javafx.scene.paint.Color;

public class Theme {

    // ---- 当前生效的色板（apply() 会改这些值）----
    public static String BG       = "#f6f1e7";   // 页面底色
    public static String CARD     = "#fffdf8";   // 卡片底色
    public static String INK      = "#3d3a34";   // 主文字（按亮度自动派生）
    public static String INK_SOFT = "#8a857a";   // 次要文字（按亮度自动派生）
    public static String ACCENT   = "#7d9b76";   // 强调色（按钮/选中）
    public static String ACCENT_D = "#65835f";   // 强调色（深色变体）
    public static String SIDEBAR  = "#efe9db";   // 侧边栏底色
    public static String DANGER   = "#c9706a";   // 删除/危险
    public static String DIVIDER  = "#f0ebdf";   // 分隔线（BG 与 CARD 中间色）

    // 三个可自定义的颜色（预设值 + 用户覆盖），供设置 UI 作为基准
    public static String presetBg, presetCard, presetAccent;

    /** 4 套预设：{id, 名称, 预览色, bg, card, accent} */
    public static final String[][] PRESETS = {
            {"warm",   "暖阳", "#d2893f", "#f6f1e7", "#fffdf8", "#d2893f"},
            {"night",  "夜晚", "#7d8fb3", "#26241f", "#35322b", "#7d8fb3"},
            {"mint",   "薄荷", "#5ea07c", "#eef6f1", "#fbfffc", "#5ea07c"},
            {"sakura", "樱花", "#d18a9a", "#faf0f2", "#fffafa", "#d18a9a"},
    };

    private static final int IDX_BG = 3;
    private static final int IDX_CARD = 4;
    private static final int IDX_ACCENT = 5;

    /** 应用主题：预设 + 3 色自定义覆盖。空串 = 用预设值。 */
    public static void apply(String id, String bgHex, String cardHex, String accentHex) {
        String pid = (id == null) ? "warm" : id;
        String[] preset = null;
        for (String[] p : PRESETS) {
            if (p[0].equals(pid)) { preset = p; break; }
        }
        if (preset == null) preset = PRESETS[0];

        presetBg = preset[IDX_BG];
        presetCard = preset[IDX_CARD];
        presetAccent = preset[IDX_ACCENT];

        String bgStr = (bgHex != null && !bgHex.isEmpty()) ? bgHex : presetBg;
        String cardStr = (cardHex != null && !cardHex.isEmpty()) ? cardHex : presetCard;
        String accentStr = (accentHex != null && !accentHex.isEmpty()) ? accentHex : presetAccent;

        // 兜底：非法颜色直接回退到对应预设，避免 UI 崩
        if (!isValid(bgStr)) bgStr = presetBg;
        if (!isValid(cardStr)) cardStr = presetCard;
        if (!isValid(accentStr)) accentStr = presetAccent;

        BG = bgStr;
        CARD = cardStr;
        ACCENT = accentStr;
        ACCENT_D = darker(accentStr);
        SIDEBAR = blend(bgStr, cardStr, 0.35f);   // 侧边栏：背景与卡片中间色
        DIVIDER = blend(bgStr, cardStr, 0.5f);
        DANGER = "#c9706a";

        // 文字色按背景亮度自动派生（浅底深字、深底浅字）
        INK = textColorFor(bgStr);
        INK_SOFT = softTextColorFor(bgStr);
    }

    private static boolean isValid(String hex) {
        try { Color.web(hex); return true; } catch (Exception e) { return false; }
    }

    /** 兼容旧签名：只传强调色，bg/card 用预设值。 */
    public static void apply(String id, String accentHex) {
        apply(id, null, null, accentHex);
    }

    /** 应用纯预设（无自定义覆盖） */
    public static void applyPreset(String id) {
        apply(id, null, null, null);
    }

    // ========== 颜色派生 ==========

    /** 文字主色：浅底→深字，深底→浅字 */
    private static String textColorFor(String bg) {
        return luminance(bg) > 0.5 ? "#111111" : "#f0ece4";
    }

    /** 次要文字色 */
    private static String softTextColorFor(String bg) {
        return luminance(bg) > 0.5 ? "#555555" : "#c0b8a8";
    }

    /** WCAG 相对亮度近似（0~1） */
    private static double luminance(String hex) {
        Color c = Color.web(hex);
        return 0.2126 * linearize(c.getRed()) + 0.7152 * linearize(c.getGreen()) + 0.0722 * linearize(c.getBlue());
    }

    private static double linearize(double v) {
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** 两色按比例混合（ratio 0~1，取后者），返回 0-255 分量 */
    private static String blend(String c1, String c2, float ratio) {
        Color a = Color.web(c1), b = Color.web(c2);
        int r = (int) ((a.getRed() * (1 - ratio) + b.getRed() * ratio) * 255);
        int g = (int) ((a.getGreen() * (1 - ratio) + b.getGreen() * ratio) * 255);
        int bl = (int) ((a.getBlue() * (1 - ratio) + b.getBlue() * ratio) * 255);
        return String.format("#%02x%02x%02x", r, g, bl);
    }

    /** 调暗一档（强调色按下/描边用） */
    private static String darker(String hex) {
        try {
            Color c = Color.web(hex).darker();
            return String.format("#%02x%02x%02x",
                    (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255));
        } catch (Exception e) {
            return hex;
        }
    }

    // ========== 颜色工具（与手机端对齐） ==========

    /** 解析十六进制颜色，失败返回灰色 */
    public static String parse(String hex) {
        try {
            Color.web(hex);
            return hex;
        } catch (Exception e) {
            return "#808080";
        }
    }

    /** 判断当前主题是否为深色主题（浅底深字模式之外的自适应判断） */
    public static boolean isDarkTheme() {
        return luminance(BG) < 0.5;
    }

    /** 输入框底色：比 CARD 稍暗（浅主题）或稍亮（深主题），与手机端一致 */
    public static String inputBgColor() {
        return isDarkTheme() ? blend(CARD, "#4a4a4a", 0.3f) : blend(CARD, BG, 0.5f);
    }

    /** 与白色按比例混合（ratio 越大越接近白色），用于心情 chip 的浅色底 */
    public static String lighten(String hex, float ratio) {
        Color c = Color.web(parse(hex));
        int r = (int) ((c.getRed() * (1 - ratio) * 255) + 255 * ratio);
        int g = (int) ((c.getGreen() * (1 - ratio) * 255) + 255 * ratio);
        int b = (int) ((c.getBlue() * (1 - ratio) * 255) + 255 * ratio);
        return String.format("#%02x%02x%02x", r, g, b);
    }

    /** 与黑色按比例混合（ratio 越大越接近黑色），深色主题心情 chip 用 */
    public static String darken(String hex, float ratio) {
        Color c = Color.web(parse(hex));
        int r = (int) (c.getRed() * (1 - ratio) * 255);
        int g = (int) (c.getGreen() * (1 - ratio) * 255);
        int b = (int) (c.getBlue() * (1 - ratio) * 255);
        return String.format("#%02x%02x%02x", r, g, b);
    }

    /** 提高/降低饱和度（factor > 1 提高），心情 chip 用 */
    public static String adjustSaturation(String hex, float factor) {
        try {
            Color c = Color.web(parse(hex));
            javafx.scene.paint.Color hsb = Color.hsb(
                    hue(c), Math.min(1.0, sat(c) * factor), brightness(c));
            return String.format("#%02x%02x%02x",
                    (int) (hsb.getRed() * 255), (int) (hsb.getGreen() * 255), (int) (hsb.getBlue() * 255));
        } catch (Exception e) {
            return hex;
        }
    }

    private static double hue(Color c) {
        double max = Math.max(c.getRed(), Math.max(c.getGreen(), c.getBlue()));
        double min = Math.min(c.getRed(), Math.min(c.getGreen(), c.getBlue()));
        double d = max - min;
        if (d == 0) return 0;
        double h;
        if (max == c.getRed()) h = ((c.getGreen() - c.getBlue()) / d) % 6;
        else if (max == c.getGreen()) h = (c.getBlue() - c.getRed()) / d + 2;
        else h = (c.getRed() - c.getGreen()) / d + 4;
        return (h * 60 + 360) % 360;
    }

    private static double sat(Color c) {
        double max = Math.max(c.getRed(), Math.max(c.getGreen(), c.getBlue()));
        double min = Math.min(c.getRed(), Math.min(c.getGreen(), c.getBlue()));
        return max == 0 ? 0 : (max - min) / max;
    }

    private static double brightness(Color c) {
        return Math.max(c.getRed(), Math.max(c.getGreen(), c.getBlue()));
    }

    // ========== 样式片段工厂（CSS 字符串，对应手机端 drawable 工厂） ==========

    /** 现代滚动条样式（薄、圆角、随主题）—— 返回可加到 ScrollPane 的 CSS */
    public static String scrollbar() {
        String track = isDarkTheme() ? "rgba(255,255,255,0.08)" : "rgba(0,0,0,0.05)";
        String thumb = isDarkTheme() ? "rgba(255,255,255,0.25)" : "rgba(0,0,0,0.18)";
        String thumbHover = isDarkTheme() ? "rgba(255,255,255,0.4)" : "rgba(0,0,0,0.3)";
        return ".scroll-bar { -fx-background-color: transparent; -fx-pref-width: 8; }"
                + ".scroll-bar .track { -fx-background-color: " + track + "; -fx-background-radius: 4; }"
                + ".scroll-bar .thumb { -fx-background-color: " + thumb + "; -fx-background-radius: 4; }"
                + ".scroll-bar .thumb:hover { -fx-background-color: " + thumbHover + "; }"
                + ".scroll-bar .increment-button, .scroll-bar .decrement-button"
                + "{ -fx-background-color: transparent; -fx-padding: 0; }"
                + ".scroll-bar .increment-arrow, .scroll-bar .decrement-arrow { -fx-background-color: transparent; }"
                + ".scroll-pane > .corner { -fx-background-color: transparent; }"
                + ".scroll-pane > .viewport { -fx-background-color: transparent; }";
    }

    /**
     * 让 ScrollPane 完全透明（背景 + 边框 + viewport）。
     * 默认 modena.css 的 ScrollPane 背景是 -fx-background: -fx-box-border, -fx-background 两层
     * （第一层=黑边，第二层=白色背景），必须全部覆盖。
     * 用 -fx-background 简写直接覆盖 modena 的简写，而不是逐属性覆盖，
     * 因为 -fx-background 简写会同时设置 -fx-background-color + -fx-background-image。
     * -fx-box-border 是 looked-up color，也设为透明。
     * viewport 引用了 -fx-control-inner-background 也要设为透明。
     */
    public static void transparentScrollPane(ScrollPane sp) {
        // 程序化直接置空背景（绕过 CSS，优先级最高，最稳）
        sp.setBackground(Background.EMPTY);
        sp.setStyle(
                "-fx-background: transparent, transparent;"
                + "-fx-background-color: transparent;"
                + "-fx-background-insets: 0;"
                + "-fx-border-color: transparent;"
                + "-fx-padding: 0;"
                + "-fx-box-border: transparent;"
                + "-fx-control-inner-background: transparent;"
                + scrollbar()
        );
        // viewport 是 ScrollPane 内部子节点，modena 给它也设了两层背景
        // （-fx-box-border + -fx-control-inner-background），内联样式是父节点上的，
        // 不会作用到子节点——必须在 viewport 本身上直接置透明。
        if (sp.getScene() != null) {
            clearViewport(sp);
        } else {
            sp.sceneProperty().addListener((o, ov, nv) -> {
                if (nv != null) clearViewport(sp);
            });
        }
    }

    /** 清掉 ScrollPane 内部 viewport 的两层背景（黑边 + 白底），让内容区透出页面底色 */
    private static void clearViewport(ScrollPane sp) {
        javafx.application.Platform.runLater(() -> {
            javafx.scene.Node vp = sp.lookup(".viewport");
            if (vp != null) {
                vp.setStyle("-fx-background-color: transparent, transparent;"
                        + "-fx-background-insets: 0; -fx-padding: 0;"
                        + "-fx-box-border: transparent;"
                        + "-fx-control-inner-background: transparent;");
            }
        });
    }

    /** 卡片背景（CARD 色 + 12px 圆角 + 轻阴影） */
    public static String cardBg() {
        return "-fx-background-color: " + CARD + ";"
             + "-fx-background-radius: 12;"
             + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 8, 0, 0, 2);";
    }

    /** 输入框背景（输入框底色 + 圆角） */
    public static String inputBg() {
        return "-fx-background-color: " + inputBgColor() + ";"
             + "-fx-background-radius: 8; -fx-border-color: transparent;";
    }

    /** 主按钮背景（ACCENT 色 + 圆角），带白字 */
    public static String primaryButtonBg() {
        return "-fx-background-color: " + ACCENT + "; -fx-text-fill: white;"
             + "-fx-background-radius: 8;";
    }

    /** 副按钮背景（透明 + ACCENT 边框 + 圆角） */
    public static String outlineButtonBg() {
        return "-fx-background-color: transparent; -fx-text-fill: " + ACCENT + ";"
             + "-fx-border-color: " + ACCENT + "; -fx-border-radius: 8; -fx-background-radius: 8;";
    }

    /** Chip 选中态背景（ACCENT 色 + 大圆角） */
    public static String chipActiveBg() {
        return "-fx-background-color: " + ACCENT + "; -fx-text-fill: white;"
             + "-fx-background-radius: 20;";
    }

    /** 圆角矩形（指定颜色和圆角） */
    public static String roundedRect(String color, int radius) {
        return "-fx-background-color: " + color + "; -fx-background-radius: " + radius + ";";
    }

    // ---- 常用样式片段 ----

    public static String page() {
        return "-fx-background-color: " + BG + ";";
    }

    public static String card() {
        return "-fx-background-color: " + CARD + ";"
             + "-fx-background-radius: 12;"
             + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 8, 0, 0, 2);";
    }

    public static String primaryBtn() {
        return "-fx-background-color: " + ACCENT + "; -fx-text-fill: white;"
             + "-fx-background-radius: 8; -fx-padding: 8 20; -fx-font-size: 14px; -fx-cursor: hand;";
    }

    public static String ghostBtn() {
        return "-fx-background-color: transparent; -fx-text-fill: " + INK_SOFT + ";"
             + "-fx-padding: 8 16; -fx-font-size: 14px; -fx-cursor: hand;";
    }

    public static String dangerBtn() {
        return "-fx-background-color: transparent; -fx-text-fill: " + DANGER + ";"
             + "-fx-padding: 4 10; -fx-font-size: 12px; -fx-cursor: hand;";
    }

    public static String input() {
        return "-fx-background-color: " + inputBgColor() + "; -fx-border-color: transparent;"
             + "-fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 10 12; -fx-font-size: 14px;"
             + "-fx-text-fill: " + INK + ";";
    }

    public static String h1() { return "-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: " + INK + ";"; }
    public static String h2() { return "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: " + INK + ";"; }
    public static String soft() { return "-fx-text-fill: " + INK_SOFT + "; -fx-font-size: 13px;"; }
}