/* 网络图片加载工具（JavaFX）：后台下载 PNG 图片，缓存到内存，主线程设到 ImageView。
 * 加载失败时自动回退到 EmojiUtil 渲染的 emoji 兜底，确保始终有图显示。
 * 与安卓端 ImageLoader.java 同模式，URL 由调用方拼 serverBase。 */
package com.moodtree.client.ui;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class ImageLoader {

    private static final Map<String, Image> cache = new HashMap<>();
    private static final int MAX_CACHE = 64; // 最多缓存 64 张图

    /** 加载一张心情/徽章 PNG 图片，返回 ImageView。
     * 加载成功：显示 PNG 图片，指定大小。
     * 加载失败：用 emojiFallback 渲染的 emoji 兜底（同大小）。
     * 缓存命中时同步返回。 */
    public static ImageView load(double size, String url, String emojiFallback) {
        ImageView iv = new ImageView();
        iv.setFitWidth(size);
        iv.setFitHeight(size);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);

        if (url == null || url.isEmpty()) {
            fallback(iv, size, emojiFallback);
            return iv;
        }

        // 先查缓存
        Image cached = cache.get(url);
        if (cached != null) {
            iv.setImage(cached);
            return iv;
        }

        // 后台下载
        Bg.run(() -> {
                    try {
                        Image img = new Image(url, size, size, true, true, true);
                        if (img.isError()) throw new Exception("Image load error: " + img.getException());
                        return img;
                    } catch (Exception e) {
                        return null;
                    }
                },
                img -> {
                    if (img != null) {
                        // 缓存（限制大小防内存泄漏）
                        if (cache.size() >= MAX_CACHE) cache.clear();
                        cache.put(url, img);
                        iv.setImage(img);
                    } else {
                        fallback(iv, size, emojiFallback);
                    }
                },
                err -> fallback(iv, size, emojiFallback));

        return iv;
    }

    /** 从缓存取 Image（没有返回 null，不触发网络请求） */
    public static Image getCached(String url) {
        return cache.get(url);
    }

    /** 清除全部缓存（主题切换或目录刷新时调用） */
    public static void clearCache() {
        cache.clear();
    }

    private static void fallback(ImageView iv, double size, String emoji) {
        Platform.runLater(() -> {
            ImageView fallback = EmojiUtil.emoji(size, emoji);
            iv.setImage(fallback.getImage());
        });
    }
}