/* 大西瓜小游戏：WebView 加载本地 game.html（纯 Canvas JS 游戏，与网页端/安卓端一致）。
 * 游戏逻辑完全在 HTML 中，Java 只负责 WebView 配置、资源提取和主题注入。
 * 资源（game.html + 水果贴图）从 classpath 提取到临时目录后加载，支持 localStorage 存最高分。 */
package com.moodtree.client.ui;

import com.moodtree.client.AppContext;
import javafx.concurrent.Worker;
import javafx.geometry.Pos;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class GameView extends VBox implements Refreshable {

    private final AppContext app;
    private final WebView webView = new WebView();
    private final WebEngine engine;
    private static Path tempDir;          // 跨实例共享，避免重复解压

    public GameView(AppContext app) {
        this.app = app;
        setStyle(Theme.page());
        setAlignment(Pos.CENTER);
        getChildren().add(webView);

        // WebView 配置：JS / DOM storage / 无缩放
        engine = webView.getEngine();
        engine.setJavaScriptEnabled(true);
        // JavaFX WebKit 默认启用 DOM storage，无需额外设置

        // 页面加载完成后注入主题色
        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                injectThemeColors();
            }
        });

        // 从临时目录加载游戏
        try {
            Path dir = ensureExtracted();
            Path gameFile = dir.resolve("game.html");
            if (gameFile.toFile().exists()) {
                engine.load(gameFile.toUri().toString());
            }
        } catch (Exception e) {
            // 加载失败时 WebView 显示空白，不影响主界面
            System.err.println("GameView: 加载失败 - " + e.getMessage());
        }
    }

    /** 将 game.html 和水果贴图从 classpath 提取到临时目录 */
    private static synchronized Path ensureExtracted() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) return tempDir;
        tempDir = Files.createTempDirectory("moodtree-game-");
        tempDir.toFile().deleteOnExit();

        // 提取 game.html
        copyResource("/game.html", tempDir.resolve("game.html"));

        // 提取 11 张水果贴图
        Path imagesDir = tempDir.resolve("images/fruits");
        Files.createDirectories(imagesDir);
        for (int i = 0; i <= 10; i++) {
            copyResource("/images/fruits/f" + i + ".png", imagesDir.resolve("f" + i + ".png"));
        }
        return tempDir;
    }

    private static void copyResource(String resourcePath, Path dest) throws IOException {
        try (InputStream is = GameView.class.getResourceAsStream(resourcePath)) {
            if (is == null) throw new FileNotFoundException("Resource not found: " + resourcePath);
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 向 WebView 注入当前主题色（与安卓端 GameFragment.injectThemeColors 逻辑一致） */
    private void injectThemeColors() {
        String bg = Theme.BG;
        String card = Theme.CARD;
        String accent = Theme.ACCENT;
        String ink = Theme.INK;
        String inkSoft = Theme.INK_SOFT;
        // 画布底色：深色主题用 CARD，浅色主题用 CARD 与白色混合
        String canvasBg = Theme.isDarkTheme() ? Theme.CARD : Theme.lighten(Theme.CARD, 0.5f);

        String js = String.format(
            "window.applyTheme('%s','%s','%s','%s','%s','%s');",
            bg, card, accent, ink, inkSoft, canvasBg
        );
        // 在 FX 线程执行（executeScript 要求 FX 线程）
        javafx.application.Platform.runLater(() -> {
            try { engine.executeScript(js); } catch (Exception ignored) { }
        });
    }

    @Override
    public void refresh() {
        // 切回此页时重新注入主题色（用户可能改了主题）
        Worker.State state = engine.getLoadWorker().getState();
        if (state == Worker.State.SUCCEEDED) {
            injectThemeColors();
        }
    }
}