/* 主框架：左侧导航 + 右侧内容区 + 底部同步状态。
 * 定时（60秒）和数据变更后自动同步；断网静默，恢复后自动补传。
 * 情绪视觉影响：最新心情的叠色（径向渐变）+ 雨滴动画（难过/孤独/麻木）。 */
package com.moodtree.client.ui;

import com.moodtree.client.AppContext;
import com.moodtree.client.model.MoodEntry;
import com.moodtree.client.model.MoodMeta;
import com.moodtree.client.sync.SyncEngine;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

public class MainShell extends BorderPane {

    private final AppContext app;
    private final StackPane content = new StackPane();
    private final Map<String, Button> navButtons = new LinkedHashMap<>();
    private final Map<String, javafx.scene.Node> views = new LinkedHashMap<>();
    private final Label syncStatus = new Label("尚未同步");
    private String currentKey;
    private boolean syncing;

    // ---- 情绪视觉影响 ----
    private final Pane moodOverlay = new Pane();
    private final Pane rainContainer = new Pane();
    private Timeline rainTimeline;
    private final Random random = new Random();

    public MainShell(AppContext app) {
        this.app = app;
        setStyle(Theme.page());

        // ---- 左侧导航 ----
        VBox sidebar = new VBox(6);
        sidebar.setPrefWidth(190);
        sidebar.setPadding(new Insets(20, 12, 20, 12));
        sidebar.setStyle("-fx-background-color: " + Theme.CARD + ";");

        Label logo = new Label("心履");
        logo.setStyle(Theme.h2());
        // 侧边栏顶部放彩色圆点树 logo（无底 PNG）
        try {
            javafx.scene.image.ImageView logoImg = new javafx.scene.image.ImageView(
                    new javafx.scene.image.Image(getClass().getResourceAsStream("/logo.png")));
            logoImg.setFitWidth(30);
            logoImg.setFitHeight(30);
            logoImg.setPreserveRatio(true);
            logo.setGraphic(logoImg);
        } catch (Exception ignored) { }
        // 左上角不显示用户 ID（与安卓端一致，只保留 logo 标题）
        VBox head = new VBox(logo);
        head.setPadding(new Insets(0, 0, 18, 8));

        addNav("calendar", "心 情 日 历", ICON_CALENDAR);
        addNav("recommend", "今 日 推 荐", ICON_RECOMMEND);
        addNav("chat", "A I 树 洞", ICON_CHAT);
        addNav("game", "小 西 瓜", ICON_GAME);
        addNav("me", "我 的", ICON_ME);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        syncStatus.setStyle("-fx-text-fill: " + Theme.INK_SOFT + "; -fx-font-size: 11px;");
        syncStatus.setWrapText(true);

        Button syncBtn = new Button("↻ 立即同步");
        syncBtn.setStyle(Theme.ghostBtn());
        syncBtn.setMaxWidth(Double.MAX_VALUE);
        syncBtn.setOnAction(e -> syncNow());
        syncBtn.setVisible(app.loggedIn());          // 游客没有云端可同步
        syncBtn.setManaged(app.loggedIn());

        // 游客显示登录入口；已登录显示退出
        Button authBtn = new Button(app.loggedIn() ? "退出登录" : "登录 / 注册");
        authBtn.setStyle(app.loggedIn() ? Theme.dangerBtn()
                : Theme.ghostBtn() + "-fx-text-fill: " + Theme.ACCENT_D + ";");
        authBtn.setMaxWidth(Double.MAX_VALUE);
        authBtn.setOnAction(e -> {
            if (app.loggedIn()) app.logout();
            else app.showLogin();
        });

        sidebar.getChildren().add(head);
        sidebar.getChildren().addAll(navButtons.values());
        sidebar.getChildren().addAll(spacer, syncStatus, syncBtn, authBtn);
        setLeft(sidebar);

        // ---- 内容区 + 情绪视觉叠层 ----
        content.setPadding(new Insets(0));
        Pane contentWrapper = new StackPane();
        contentWrapper.getChildren().add(content);

        // 情绪叠色（半透明径向渐变，鼠标穿透，不影响点击）
        moodOverlay.setPickOnBounds(false);
        moodOverlay.setMouseTransparent(true);
        moodOverlay.setVisible(false);
        contentWrapper.getChildren().add(moodOverlay);   // 叠在 content 之上

        // 雨滴容器（鼠标穿透）
        rainContainer.setPickOnBounds(false);
        rainContainer.setMouseTransparent(true);
        rainContainer.setVisible(false);
        contentWrapper.getChildren().add(rainContainer);  // 叠在最上层

        setCenter(contentWrapper);

        // 登录用户每分钟自动同步一次（失败静默）
        Timeline timer = new Timeline(new KeyFrame(Duration.seconds(60), e -> syncNow()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();

        // ---- AI 主动消息轮询（登录用户，每 60 秒） ----
        Timeline proactiveTimer = new Timeline(new KeyFrame(Duration.seconds(60), e -> pollProactive()));
        proactiveTimer.setCycleCount(Timeline.INDEFINITE);
        proactiveTimer.play();
    }

    // ============ AI 主动消息轮询 + 系统通知 ============

    private void pollProactive() {
        if (!app.loggedIn()) return;
        String since = app.config.lastProactiveCheck();
        Bg.run(() -> {
                    if (!app.api.ping()) return null;
                    return app.api.chatProactive(since.isEmpty() ? null : since);
                },
                resp -> {
                    if (resp == null) return;
                    String serverTime = "";
                    if (resp.has("server_time") && !resp.get("server_time").isJsonNull()) {
                        serverTime = resp.get("server_time").getAsString();
                    }
                    if (resp.has("messages") && resp.get("messages").isJsonArray()) {
                        for (JsonElement el : resp.getAsJsonArray("messages")) {
                            JsonObject m = el.getAsJsonObject();
                            String content = m.has("content") ? m.get("content").getAsString() : "";
                            if (content.isEmpty()) continue;
                            if (currentKey != null && currentKey.equals("chat")
                                    && views.get("chat") instanceof ChatView cv) {
                                cv.addProactiveMessage(content);
                            } else {
                                showProactiveNotification(content);
                            }
                        }
                    }
                    if (!serverTime.isEmpty()) {
                        app.config.setLastProactiveCheck(serverTime);
                        app.config.save();
                    }
                },
                err -> { /* 静默 */ });
    }

    /** 系统托盘通知（类似微信消息提醒） */
    private void showProactiveNotification(String text) {
        try {
            if (!java.awt.SystemTray.isSupported()) return;
            java.awt.SystemTray tray = java.awt.SystemTray.getSystemTray();
            java.awt.TrayIcon[] icons = tray.getTrayIcons();
            if (icons.length == 0) return;
            String body = text.length() > 120 ? text.substring(0, 120) + "…" : text;
            icons[0].displayMessage("🌳 树洞来信", body, java.awt.TrayIcon.MessageType.INFO);
        } catch (Exception e) { /* 静默 */ }
    }

    private void addNav(String key, String text, String icon) {
        Button b = new Button(text, navIcon(icon));
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setPadding(new Insets(10, 14, 10, 14));
        b.setOnAction(e -> show(key));
        navButtons.put(key, b);
        styleNav(key, false);
    }

    /** 安卓端同款简笔画图标（Material 线性图标，细描边，随主题文字色） */
    private static final String ICON_CALENDAR =
            "M19,3h-1V1h-2v2H8V1H6v2H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5C21,3.9 20.1,3 19,3zM19,19H5V9h14V19zM5,7V5h14v2H5zM7,11h2v2H7V11zM11,11h2v2h-2V11zM15,11h2v2h-2V11z";
    private static final String ICON_RECOMMEND =
            "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z";
    private static final String ICON_CHAT =
            "M20,2H4c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2zM6,9h12v2H6V9zM14,14H6v-2h8V14zM18,8H6V6h12V8z";
    private static final String ICON_ME =
            "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z";
    private static final String ICON_GAME =
            "M20.7,4.1C20,3.6 19.2,3.4 18.4,3.4c-1.2,0 -2.4,0.4 -3.5,1H9.1C6.9,4.4 4.9,5.2 3.4,6.7c-1,1 -1.5,2.3 -1.5,3.7 0,3.3 1.9,8.4 3.6,10.1 0.9,0.9 1.9,0.9 2.6,0.2 1.1,-1.1 2.6,-1.7 3.9,-1.7h0c1.3,0 2.8,0.6 3.9,1.7 0.7,0.7 1.7,0.7 2.6,-0.2 1.7,-1.7 3.6,-6.8 3.6,-10.1 0,-1.4 -0.5,-2.7 -1.5,-3.7zM8.5,11h-2v-2h-2v2h-2v2h2v2h2v-2h2V11zM15,12.75c-0.69,0 -1.25,-0.56 -1.25,-1.25s0.56,-1.25 1.25,-1.25 1.25,0.56 1.25,1.25 -0.56,1.25 -1.25,1.25zM17.5,15.25c-0.69,0 -1.25,-0.56 -1.25,-1.25s0.56,-1.25 1.25,-1.25 1.25,0.56 1.25,1.25 -0.56,1.25 -1.25,1.25zM18,11.25c-0.55,0 -1,-0.45 -1,-1s0.45,-1 1,-1 1,0.45 1,1 -0.45,1 -1,1z";

    /** 用 SVG path 生成简笔画图标（描边风格，随主题 INK_SOFT 色，比主文字淡），返回 Node 可作按钮图形 */
    private static javafx.scene.Node navIcon(String pathData) {
        try {
            javafx.scene.shape.SVGPath svg = new javafx.scene.shape.SVGPath();
            svg.setContent(pathData);
            svg.setFill(null);
            svg.setStroke(javafx.scene.paint.Color.web(Theme.INK_SOFT));
            svg.setStrokeWidth(1.4);
            svg.setScaleX(0.9);
            svg.setScaleY(0.9);
            return svg;
        } catch (Exception e) {
            return new Label("·");
        }
    }

    private void styleNav(String key, boolean selected) {
        Button b = navButtons.get(key);
        b.setStyle(selected
                ? "-fx-background-color: " + Theme.CARD + "; -fx-background-radius: 10;"
                  + "-fx-font-size: 14px; -fx-text-fill: " + Theme.INK + "; -fx-font-weight: bold; -fx-cursor: hand;"
                : "-fx-background-color: transparent; -fx-font-size: 14px;"
                  + "-fx-text-fill: " + Theme.INK_SOFT + "; -fx-cursor: hand;");
        // 图标颜色也随选中态切换：选中用 INK（深），未选中用 INK_SOFT（淡）
        if (b.getGraphic() instanceof javafx.scene.shape.SVGPath svg) {
            svg.setStroke(javafx.scene.paint.Color.web(selected ? Theme.INK : Theme.INK_SOFT));
        }
    }

    public void show(String key) {
        if (key.equals(currentKey)) return;
        javafx.scene.Node view = views.computeIfAbsent(key, this::buildView);

        // 页面切换淡入动画（与手机端 fragment 过渡对齐）
        view.setOpacity(0);
        content.getChildren().setAll(view);
        FadeTransition fade = new FadeTransition(Duration.millis(200), view);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();

        navButtons.keySet().forEach(k -> styleNav(k, k.equals(key)));
        currentKey = key;
        app.setLastViewKey(key);
        if (view instanceof Refreshable r) r.refresh();
    }

    private javafx.scene.Node buildView(String key) {
        return switch (key) {
            case "calendar" -> new CalendarView(app, mood -> showMoodRecommend(mood));
            case "recommend" -> new RecommendView(app);
            case "chat" -> new ChatView(app);
            case "me" -> new MeView(app);
            case "game" -> new GameView(app);
            default -> new PlaceholderView(key);
        };
    }

    /** 记录心情后：切到推荐页并选中对应心情（视图已缓存，直接复用实例） */
    private void showMoodRecommend(String mood) {
        show("recommend");
        if (views.get("recommend") instanceof RecommendView rv) rv.showMood(mood);
    }

    /** 进入主界面后的首次同步 */
    public void onShown() {
        // 开发调试：-Dmoodtree.view=recommend 可启动后直接跳到指定页
        String jump = System.getProperty("moodtree.view");
        if (jump != null && navButtons.containsKey(jump)) {
            show(jump);
        } else {
            show(app.lastViewKey());
        }
        updateMoodVisual();
        syncNow();
    }

    public void syncNow() {
        if (syncing) return;
        if (!app.loggedIn()) {
            syncStatus.setText("游客模式：记录已保存在本机，登录后自动上云");
            return;
        }
        syncing = true;
        syncStatus.setText("同步中…");
        Bg.run(() -> app.sync.sync(),
                r -> {
                    syncing = false;
                    String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
                    if (r.error != null) {
                        syncStatus.setText((r.offline ? "离线，数据已存本地 " : r.error + " ") + time);
                    } else {
                        syncStatus.setText(r.summary() + "  " + time);
                    }
                    updateMoodVisual();
                    javafx.scene.Node v = views.get(currentKey);
                    if (v instanceof Refreshable rv) rv.refresh();
                },
                err -> {
                    syncing = false;
                    syncStatus.setText("同步失败：" + err.getMessage());
                });
    }

    // ============ 情绪视觉影响 ============

    /** 从数据库取最新一条心情，更新叠色和雨滴效果 */
    public void updateMoodVisual() {
        Bg.run(() -> {
                    try {
                        MoodEntry latest = app.db.getLatest();
                        if (latest == null) return null;
                        MoodMeta meta = MoodMeta.of(latest.mood);
                        return new Object[]{meta, latest.intensityLevel, latest.intensityPercent};
                    } catch (Exception e) {
                        return null;
                    }
                },
                data -> {
                    if (data == null) {
                        moodOverlay.setVisible(false);
                        rainContainer.setVisible(false);
                        stopRain();
                        return;
                    }
                    MoodMeta meta = (MoodMeta) data[0];
                    int intensityLevel = (int) data[1];
                    int intensityPercent = (int) data[2];

                    // 叠色：径向渐变，颜色来自心情色，强度缩放透明度
                    double opacity = 0.08;
                    if (intensityLevel >= 2) opacity = 0.12;
                    if (intensityLevel >= 3) opacity = 0.18;
                    if (intensityLevel >= 4) opacity = 0.25;
                    // 透明度再按百分位微调
                    opacity *= (0.8 + 0.4 * intensityPercent / 100.0);

                    String color = meta.color;
                    try {
                        Color c = Color.web(color);
                        int r = (int) (c.getRed() * 255);
                        int g = (int) (c.getGreen() * 255);
                        int b = (int) (c.getBlue() * 255);
                        String rgba = String.format("rgba(%d,%d,%d,%f)", r, g, b, opacity);
                        // 用 Region 实现径向渐变背景（CSS 方式，JavaFX 支持）
                        Region overlayRegion = new Region();
                        overlayRegion.setStyle(
                                "-fx-background-color: radial-gradient("
                                        + "focus-angle 0deg, focus-distance 0%, "
                                        + "center 50% 0%, radius 60%, "
                                        + rgba + " 0%, transparent 70%);");
                        overlayRegion.setMouseTransparent(true);
                        moodOverlay.getChildren().setAll(overlayRegion);
                    } catch (Exception e) {
                        moodOverlay.getChildren().clear();
                    }
                    moodOverlay.setVisible(true);

                    // 雨滴动画：难过/孤独/麻木 三种心情
                    String[] rainMoods = {"sad", "lonely", "numb"};
                    boolean shouldRain = false;
                    for (String rm : rainMoods) {
                        if (rm.equals(meta.key)) { shouldRain = true; break; }
                    }
                    if (shouldRain && intensityLevel >= 1) {
                        startRain();
                    } else {
                        stopRain();
                        rainContainer.setVisible(false);
                    }
                },
                err -> { /* 静默 */ });
    }

    private void startRain() {
        if (rainTimeline != null) return;
        rainContainer.setVisible(true);
        rainTimeline = new Timeline(new KeyFrame(Duration.millis(120), e -> spawnRainDrop()));
        rainTimeline.setCycleCount(Timeline.INDEFINITE);
        rainTimeline.play();
    }

    private void stopRain() {
        if (rainTimeline != null) {
            rainTimeline.stop();
            rainTimeline = null;
        }
        rainContainer.getChildren().clear();
    }

    private void spawnRainDrop() {
        if (!rainContainer.isVisible()) return;
        double w = rainContainer.getWidth();
        if (w <= 0) w = 800;
        Rectangle drop = new Rectangle(1.5, 14, Color.rgb(150, 160, 200, 0.2));
        drop.setX(random.nextDouble() * w);
        drop.setY(-20);
        rainContainer.getChildren().add(drop);

        double duration = 0.6 + random.nextDouble() * 0.4;
        TranslateTransition tt = new TranslateTransition(Duration.seconds(duration), drop);
        tt.setByY(rainContainer.getHeight() + 40);
        tt.setOnFinished(ev -> rainContainer.getChildren().remove(drop));
        tt.play();
    }
}
