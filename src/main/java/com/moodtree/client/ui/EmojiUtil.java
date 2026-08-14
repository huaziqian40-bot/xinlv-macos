/* Emoji 渲染工具：使用系统 emoji 字体绘制到带安全边距的透明图片，再转为 JavaFX ImageView。
 * 不直接依赖 Label 的裁剪和主题文字色，确保日历、弹窗、徽章和连续记录图标在两种主题下都可见。 */
package com.moodtree.client.ui;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Locale;

public class EmojiUtil {

    private static final Font[] CANDIDATE_FONTS = createCandidateFonts();

    private static Font[] createCandidateFonts() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] names;
        if (os.contains("mac")) {
            names = new String[]{"Apple Color Emoji", "Helvetica", "Arial Unicode MS"};
        } else if (os.contains("win")) {
            names = new String[]{"Segoe UI Emoji", "Segoe UI Symbol", "Noto Color Emoji", "Arial Unicode MS"};
        } else {
            names = new String[]{"Noto Color Emoji", "Segoe UI Emoji", "DejaVu Sans", "Arial Unicode MS"};
        }

        String[] installed = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
        java.util.List<Font> result = new java.util.ArrayList<>();
        for (String name : names) {
            for (String family : installed) {
                if (family.equalsIgnoreCase(name)) {
                    result.add(new Font(family, Font.PLAIN, 40));
                    break;
                }
            }
        }
        if (result.isEmpty()) {
            result.add(new Font(Font.SANS_SERIF, Font.PLAIN, 40));
        }
        return result.toArray(new Font[0]);
    }

    private static Font fontFor(String emoji) {
        for (Font font : CANDIDATE_FONTS) {
            if (font.canDisplayUpTo(emoji) == -1) return font;
        }
        return CANDIDATE_FONTS[0];
    }

    /** 将 emoji 文本渲染为指定大小的 ImageView，保留透明通道和四周安全边距。 */
    public static ImageView emojiToImageView(String emoji, double size) {
        if (emoji == null || emoji.isEmpty()) return new ImageView();

        // 放大源画布再缩小显示，避免彩色字体的位图边缘被裁掉。
        int px = Math.max(32, (int) Math.ceil(size * 2.4));
        BufferedImage bi = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Clear);
        g.fillRect(0, 0, px, px);
        g.setComposite(java.awt.AlphaComposite.SrcOver);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        Font font = fontFor(emoji).deriveFont((float) (px * 0.82));
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(emoji);
        int x = Math.max(1, (px - textWidth) / 2);
        int y = (px - fm.getHeight()) / 2 + fm.getAscent();
        // 防止个别字体的 ascent/descent 计算把 glyph 推出透明画布。
        y = Math.max(fm.getAscent(), Math.min(px - fm.getDescent(), y));
        g.drawString(emoji, x, y);
        g.dispose();

        Image fxImage = SwingFXUtils.toFXImage(bi, null);
        ImageView iv = new ImageView(fxImage);
        iv.setFitWidth(size);
        iv.setFitHeight(size);
        iv.setPreserveRatio(false);
        iv.setSmooth(true);
        return iv;
    }

    /** 快捷方法：返回带 emoji 的 ImageView，适合放在 VBox/HBox 中。 */
    public static ImageView emoji(double size, String emoji) {
        return emojiToImageView(emoji, size);
    }
}
