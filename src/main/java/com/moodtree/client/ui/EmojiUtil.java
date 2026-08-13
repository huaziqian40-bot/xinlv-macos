/* Emoji 渲染工具：用 AWT Graphics2D 绘制彩色 emoji 后转 JavaFX ImageView。
 * macOS 上 JavaFX 的 Prism 文本管线不支持 Apple Color Emoji 的 sbix 彩色位图格式，
 * 导致 emoji 显示为黑白轮廓。AWT 在 macOS 上能正确渲染彩色 emoji，因此用此工具类
 * 替代所有 Label + -fx-font-family: 'Apple Color Emoji' 的写法。 */
package com.moodtree.client.ui;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.awt.*;
import java.awt.image.BufferedImage;

public class EmojiUtil {

    private static Font emojiFont;

    static {
        // macOS 上找 Apple Color Emoji，找不到回退系统默认
        String[] names = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        String preferred = "Apple Color Emoji";
        boolean found = false;
        for (String n : names) {
            if (n.equals(preferred)) { found = true; break; }
        }
        emojiFont = found ? new Font(preferred, Font.PLAIN, 40) : new Font("Segoe UI Emoji", Font.PLAIN, 40);
    }

    /** 将 emoji 文本渲染为指定大小的 ImageView（保留透明通道） */
    public static ImageView emojiToImageView(String emoji, double size) {
        int px = (int) Math.ceil(size * 1.6);
        BufferedImage bi = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float fontSize = (float) (px * 0.85);
        g.setFont(emojiFont.deriveFont(fontSize));
        FontMetrics fm = g.getFontMetrics();
        int x = (px - fm.stringWidth(emoji)) / 2;
        int y = (px - fm.getHeight()) / 2 + fm.getAscent();
        g.drawString(emoji, x, y);
        g.dispose();

        Image fxImage = SwingFXUtils.toFXImage(bi, null);
        ImageView iv = new ImageView(fxImage);
        iv.setFitWidth(size);
        iv.setFitHeight(size);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        return iv;
    }

    /** 快捷方法：返回带 emoji 的 ImageView，适合放在 VBox/HBox 中 */
    public static ImageView emoji(double size, String emoji) {
        return emojiToImageView(emoji, size);
    }
}