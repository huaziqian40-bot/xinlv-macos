/* 网络图片加载工具（JavaFX）：后台下载 PNG 图片，缓存到内存+磁盘，主线程设到 ImageView。
 * 加载失败时自动回退到 EmojiUtil 渲染的 emoji 兜底，确保始终有图显示。
 * 首次加载后自动存磁盘（~/.moodtree/cache/images/），后续启动秒开。 */
package com.moodtree.client.ui;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class ImageLoader {

    private static final Map<String, Image> cache = new HashMap<>();
    private static final int MAX_CACHE = 64; // 最多缓存 64 张图

    private static Path diskDir = null;

    /** 初始化磁盘缓存目录（AppContext 构造时调用一次即可） */
    public static void init(Path dataDir) {
        diskDir = dataDir.resolve("cache").resolve("images");
        try {
            Files.createDirectories(diskDir);
        } catch (Exception ignored) { }
    }

    /** 磁盘缓存文件路径 */
    private static Path diskFile(String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        return diskDir.resolve(name);
    }

    /** 加载一张心情/徽章 PNG 图片，返回 ImageView。
     * 加载成功：显示 PNG 图片，指定大小。
     * 加载失败：用 emojiFallback 渲染的 emoji 兜底（同大小）。
     * 缓存命中时同步返回。优先内存→磁盘→网络，自动缓存。 */
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

        // 1. 内存缓存
        Image cached = cache.get(url);
        if (cached != null) {
            iv.setImage(cached);
            return iv;
        }

        // 2. 磁盘缓存（异步加载，因为 Image(InputStream) 是同步阻塞的）
        Path disk = diskFile(url);
        if (diskDir != null && Files.exists(disk)) {
            Bg.run(() -> {
                        try {
                            return new Image(disk.toUri().toURL().toString(), size, size, true, true);
                        } catch (Exception e) {
                            return null;
                        }
                    },
                    img -> {
                        if (img != null) {
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

        // 3. 网络下载 → 存磁盘 + 设图
        Bg.run(() -> {
                    try {
                        byte[] data;
                        try (InputStream in = URI.create(url).toURL().openStream();
                             ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
                            byte[] tmp = new byte[4096];
                            int n;
                            while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
                            data = buf.toByteArray();
                        }
                        // 存磁盘
                        if (diskDir != null) {
                            Files.write(diskFile(url), data);
                        }
                        return new Image(new ByteArrayInputStream(data), size, size, true, true);
                    } catch (Exception e) {
                        return null;
                    }
                },
                img -> {
                    if (img != null) {
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

    /** 预加载全部心情+徽章 PNG 到磁盘缓存（AppContext 后台调用，不阻塞启动）。
     *  首次启动下载全部 15 张，后续启动秒开。 */
    public static void preloadAll(Path dataDir, String serverBase) {
        init(dataDir);
        String[] paths = {
            "images/mood_happy.png", "images/mood_calm.png", "images/mood_excited.png",
            "images/mood_grateful.png", "images/mood_tired.png", "images/mood_anxious.png",
            "images/mood_sad.png", "images/mood_angry.png", "images/mood_lonely.png",
            "images/mood_numb.png",
            "images/badge_5.png", "images/badge_30.png", "images/badge_100.png",
            "images/badge_365.png", "images/badge_1000.png",
        };
        Bg.run(() -> {
            for (String path : paths) {
                String url = serverBase + "/static/" + path;
                Path f = diskFile(url);
                if (Files.exists(f)) continue;
                try (InputStream in = URI.create(url).toURL().openStream()) {
                    Files.copy(in, f, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ignored) { }
            }
            return null;
        });
    }

    private static void fallback(ImageView iv, double size, String emoji) {
        Platform.runLater(() -> {
            ImageView fallback = EmojiUtil.emoji(size, emoji);
            iv.setImage(fallback.getImage());
        });
    }
}