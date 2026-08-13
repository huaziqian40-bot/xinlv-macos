/* 我的页面：头像 + 用户名、连胜、徽章墙、总记录数（在线拉 /api/v1/profile/，离线用缓存）+ 设置区。
 * 头像从 profile.avatar_url 加载，无头像时显示用户名首字圆形底。 */
package com.moodtree.client.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moodtree.client.AppContext;
import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

public class MeView extends VBox implements Refreshable {

    private final AppContext app;
    private final VBox profileBox = new VBox(14);
    private final Label stateLabel = new Label();
    private boolean loaded;

    public MeView(AppContext app) {
        this.app = app;
        setSpacing(16);
        setPadding(new Insets(24));
        setStyle(Theme.page());

        Label title = new Label("我的");
        title.setStyle(Theme.h1());
        stateLabel.setStyle(Theme.soft());

        VBox body = new VBox(14, profileBox, buildSettings());
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        Theme.transparentScrollPane(scroll);
        ScrollSensitivity.boost(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(title, stateLabel, scroll);
        refresh();
    }

    @Override
    public void refresh() {
        if (loaded) return;
        loaded = true;
        if (!app.loggedIn()) {
            renderGuest();
            return;
        }
        Bg.run(() -> {
                    if (app.api.ping()) {
                        JsonObject p = app.api.profile();
                        app.db.kvSet("profile_cache", p.toString());
                        return p;
                    }
                    String cached = app.db.kvGet("profile_cache");
                    return cached == null ? null : JsonParser.parseString(cached).getAsJsonObject();
                },
                this::render,
                err -> stateLabel.setText("加载失败：" + err.getMessage()));
    }

    /** 游客模式：本地统计（连胜/总条数在本机算）+ 登录引导，徽章联网后才有 */
    private void renderGuest() {
        profileBox.getChildren().clear();
        stateLabel.setText("");

        int streak = 0, total = 0;
        try {
            total = app.db.countAlive();
            java.util.List<java.time.LocalDate> dates = app.db.listDistinctDates();
            // 从今天（或昨天）往前数连续有记录的天数
            java.time.LocalDate cursor = java.time.LocalDate.now();
            if (!dates.contains(cursor)) cursor = cursor.minusDays(1);
            while (dates.contains(cursor)) {
                streak++;
                cursor = cursor.minusDays(1);
            }
        } catch (Exception ignored) { }

        ImageView fire = EmojiUtil.emoji(40, "🔥");
        Label days = new Label(streak + " 天");
        days.setStyle("-fx-font-size: 32px; -fx-font-weight: bold; -fx-text-fill: " + Theme.INK + ";");
        Label cap = new Label("连续记录 · 共 " + total + " 条（本机）");
        cap.setStyle(Theme.soft());
        HBox streakCard = new HBox(16, fire, new VBox(2, days, cap));
        streakCard.setAlignment(Pos.CENTER_LEFT);
        streakCard.setPadding(new Insets(20));
        streakCard.setStyle(Theme.card());

        Label hint = new Label("登录后：记录自动同步到云端、解锁徽章墙、多设备互通，已有记录不会丢");
        hint.setStyle("-fx-font-size: 14px; -fx-text-fill: " + Theme.INK + ";");
        hint.setWrapText(true);
        Button loginBtn = new Button("登录 / 注册");
        loginBtn.setStyle(Theme.primaryBtn());
        loginBtn.setOnAction(e -> app.showLogin());
        VBox guestCard = new VBox(10, hint, loginBtn);
        guestCard.setPadding(new Insets(20));
        guestCard.setStyle(Theme.card());

        profileBox.getChildren().addAll(streakCard, guestCard);
        animateIn(streakCard, 0);
        animateIn(guestCard, 1);
    }

    private void render(JsonObject p) {
        profileBox.getChildren().clear();
        if (p == null) {
            stateLabel.setText("离线且暂无缓存数据，联网后这里会显示你的连胜和徽章");
            return;
        }
        stateLabel.setText("");

        // ---- 头像 + 用户名卡片 ----
        javafx.scene.Node header = buildProfileHeader(p);
        profileBox.getChildren().add(header);
        animateIn(header, 0);

        // ---- 连胜卡 ----
        int streak = p.has("streak") ? p.get("streak").getAsInt() : 0;
        ImageView fire = EmojiUtil.emoji(40, "🔥");
        Label days = new Label(streak + " 天");
        days.setStyle("-fx-font-size: 32px; -fx-font-weight: bold; -fx-text-fill: " + Theme.INK + ";");
        Label cap = new Label("连续记录");
        cap.setStyle(Theme.soft());
        HBox streakCard = new HBox(16, fire, new VBox(2, days, cap));
        streakCard.setAlignment(Pos.CENTER_LEFT);
        streakCard.setPadding(new Insets(20));
        streakCard.setStyle(Theme.card());
        profileBox.getChildren().add(streakCard);
        animateIn(streakCard, 1);

        // ---- 统计 ----
        int total = p.has("total_entries") ? p.get("total_entries").getAsInt() : 0;
        String joined = p.has("date_joined") && !p.get("date_joined").isJsonNull()
                ? p.get("date_joined").getAsString().substring(0, 10) : "";
        Label stat = new Label("共记录 " + total + " 条心情" + (joined.isEmpty() ? "" : " · " + joined + " 加入"));
        stat.setStyle(Theme.soft());
        profileBox.getChildren().add(stat);
        animateIn(stat, 2);

        // ---- 徽章墙 ----
        Label badgeTitle = new Label("徽章墙");
        badgeTitle.setStyle(Theme.h2());
        FlowPane badges = new FlowPane(10, 10);
        if (p.has("badges")) {
            for (JsonElement el : p.getAsJsonArray("badges")) {
                JsonObject b = el.getAsJsonObject();
                ImageView emoji = EmojiUtil.emoji(28, b.get("emoji").getAsString());
                Label name = new Label(b.get("name").getAsString());
                name.setStyle("-fx-font-size: 13px; -fx-text-fill: " + Theme.INK + ";");
                Label need = new Label("连续 " + b.get("days").getAsInt() + " 天");
                need.setStyle("-fx-font-size: 11px; -fx-text-fill: " + Theme.INK_SOFT + ";");
                VBox badge = new VBox(4, emoji, name, need);
                badge.setAlignment(Pos.CENTER);
                badge.setPadding(new Insets(14, 20, 14, 20));
                badge.setStyle(Theme.card());
                badges.getChildren().add(badge);
            }
        }
        if (badges.getChildren().isEmpty()) {
            Label none = new Label("还没有徽章，从连续记录 3 天开始收集吧");
            none.setStyle(Theme.soft());
            badges.getChildren().add(none);
        }
        profileBox.getChildren().add(badgeTitle);
        profileBox.getChildren().add(badges);
        animateIn(badgeTitle, 3);
        animateIn(badges, 4);
    }

    /** 淡入 + 上移动画（与手机端一致，index 控制依次延迟） */
    private void animateIn(javafx.scene.Node v, int index) {
        v.setOpacity(0);
        v.setTranslateY(18);
        FadeTransition ft = new FadeTransition(Duration.millis(350), v);
        ft.setFromValue(0);
        ft.setToValue(1);
        ft.setDelay(Duration.millis(index * 120));
        TranslateTransition tt = new TranslateTransition(Duration.millis(350), v);
        tt.setFromY(18);
        tt.setToY(0);
        tt.setDelay(Duration.millis(index * 120));
        ft.play();
        tt.play();
    }

    /** 头像 + 用户名卡片：头像从 profile.avatar_url 加载，无头像时显示用户名首字圆形底 */
    private VBox buildProfileHeader(JsonObject p) {
        String username = p.has("username") ? p.get("username").getAsString() : "用户";
        String bio = p.has("bio") && !p.get("bio").isJsonNull()
                ? p.get("bio").getAsString() : "";
        String avatarUrl = p.has("avatar_url") && !p.get("avatar_url").isJsonNull()
                ? p.get("avatar_url").getAsString() : "";

        // 头像：圆形裁剪
        int size = 56;
        StackPane avatarWrap = new StackPane();
        avatarWrap.setPrefSize(size, size);
        avatarWrap.setMinSize(size, size);
        avatarWrap.setMaxSize(size, size);

        Circle clip = new Circle(size / 2.0, size / 2.0, size / 2.0);
        avatarWrap.setClip(clip);

        Region avatarBg = new Region();
        avatarBg.setStyle("-fx-background-color: " + Theme.CARD + ";");
        avatarBg.setPrefSize(size, size);
        avatarWrap.getChildren().add(avatarBg);

        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            // 后台加载头像，避免阻塞 UI
            Bg.run(() -> {
                        try {
                            return new Image(avatarUrl, size, size, true, true, true);
                        } catch (Exception e) {
                            return null;
                        }
                    },
                    img -> {
                        if (img != null && !img.isError()) {
                            ImageView iv = new ImageView(img);
                            iv.setFitWidth(size);
                            iv.setFitHeight(size);
                            iv.setPreserveRatio(true);
                            avatarWrap.getChildren().add(iv);
                        } else {
                            avatarWrap.getChildren().add(initialAvatar(username, size));
                        }
                    },
                    err -> avatarWrap.getChildren().add(initialAvatar(username, size)));
        } else {
            avatarWrap.getChildren().add(initialAvatar(username, size));
        }

        // 用户名 + 签名
        Label nameLabel = new Label(username);
        nameLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: " + Theme.INK + ";");
        VBox col = new VBox(4);
        col.setAlignment(Pos.CENTER_LEFT);
        col.getChildren().add(nameLabel);
        if (!bio.isEmpty()) {
            Label bioLabel = new Label(bio);
            bioLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: " + Theme.INK_SOFT + ";");
            col.getChildren().add(bioLabel);
        }

        HBox card = new HBox(16, avatarWrap, col);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(20));
        card.setStyle(Theme.card());
        return new VBox(card);
    }

    /** 无头像时：用户名首字 + 强调色圆形底 */
    private Label initialAvatar(String username, int size) {
        Label initial = new Label(username.isEmpty() ? "?" : username.substring(0, 1).toUpperCase());
        initial.setStyle("-fx-background-color: " + Theme.ACCENT + "; -fx-text-fill: white;"
                + "-fx-background-radius: " + (size / 2.0) + ";"
                + "-fx-font-size: " + (size * 0.45) + "px; -fx-font-weight: bold;"
                + "-fx-alignment: center; -fx-min-width: " + size + "; -fx-min-height: " + size + ";");
        return initial;
    }

    /** 设置区：主题（预设 + 3 色自定义）/ 服务器地址 / 刷新推荐目录 */
    private VBox buildSettings() {
        Label h = new Label("设置");
        h.setStyle(Theme.h2());

        // ---- 主题卡片：预设 + 3 色编辑（与手机端一致）----
        VBox themeCard = buildThemeCard();
        VBox.setMargin(themeCard, new Insets(6, 0, 0, 0));

        // ---- 服务器地址（技术细节藏在这里，登录页不显示）----
        Label serverLabel = new Label("服务器地址");
        serverLabel.setStyle(Theme.soft());
        TextField server = new TextField(app.config.serverBase());
        server.setStyle(Theme.input());
        server.setMaxWidth(360);
        Button saveServer = new Button("保存");
        saveServer.setStyle(Theme.primaryBtn());
        Label saveState = new Label();
        saveState.setStyle(Theme.soft());
        saveServer.setOnAction(e -> {
            app.config.setServerBase(server.getText());
            app.config.save();
            saveState.setText("已保存，重启后完全生效");
        });
        HBox serverRow = new HBox(10, server, saveServer, saveState);
        serverRow.setAlignment(Pos.CENTER_LEFT);

        Button refreshCatalog = new Button("刷新推荐内容缓存");
        refreshCatalog.setStyle(Theme.ghostBtn() + "-fx-border-color: " + Theme.ACCENT
                + "; -fx-border-radius: 8; -fx-text-fill: " + Theme.ACCENT_D + ";");
        Label catState = new Label();
        catState.setStyle(Theme.soft());
        refreshCatalog.setOnAction(e -> {
            refreshCatalog.setDisable(true);
            catState.setText("刷新中…");
            Bg.run(() -> app.sync.refreshCatalog(),
                    ok -> {
                        refreshCatalog.setDisable(false);
                        catState.setText(ok ? "已更新（离线也能给推荐）" : "刷新失败，检查网络");
                    },
                    err -> {
                        refreshCatalog.setDisable(false);
                        catState.setText("刷新失败：" + err.getMessage());
                    });
        });
        HBox catRow = new HBox(10, refreshCatalog, catState);
        catRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10, h, themeCard,
                serverLabel, serverRow, catRow);
        box.setPadding(new Insets(16));
        box.setStyle(Theme.card());
        VBox.setMargin(box, new Insets(10, 0, 0, 0));
        return box;
    }

    // ========== 主题专用卡片（3 色编辑，与手机端一致） ==========

    private VBox buildThemeCard() {
        VBox box = new VBox(10);
        Label title = new Label("主题");
        title.setStyle(Theme.h2());

        // 预设行：4 预设按钮
        HBox presetRow = new HBox(10);
        presetRow.setAlignment(Pos.CENTER_LEFT);
        String curId = app.config.themeId();
        for (String[] preset : Theme.PRESETS) {
            String id = preset[0], name = preset[1], preview = preset[2];
            javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(9,
                    javafx.scene.paint.Color.web(preview));
            dot.setStroke(javafx.scene.paint.Color.web("#00000033"));
            Button b = new Button(name, dot);
            boolean active = id.equals(curId);
            b.setStyle("-fx-background-color: " + (active ? Theme.CARD : "transparent") + ";"
                    + "-fx-border-color: " + (active ? Theme.ACCENT : "#00000022") + ";"
                    + "-fx-border-radius: 8; -fx-background-radius: 8;"
                    + "-fx-padding: 6 14; -fx-font-size: 13px; -fx-cursor: hand;"
                    + "-fx-text-fill: " + Theme.INK + ";");
            b.setOnAction(e -> app.applyTheme(id, "", "", ""));
            presetRow.getChildren().add(b);
        }

        box.getChildren().addAll(title, presetRow,
                colorEditRow("🎨 背景色", app.config.themeBg(), Theme.presetBg, 3,
                        hex -> app.applyTheme(app.config.themeId(), hex,
                                app.config.themeCard(), app.config.accent())),
                colorEditRow("📦 卡片色", app.config.themeCard(), Theme.presetCard, 4,
                        hex -> app.applyTheme(app.config.themeId(), app.config.themeBg(),
                                hex, app.config.accent())),
                colorEditRow("🔘 强调色", app.config.accent(), Theme.presetAccent, 5,
                        hex -> app.applyTheme(app.config.themeId(), app.config.themeBg(),
                                app.config.themeCard(), hex)));
        return box;
    }

    /** 一行颜色编辑区：标签 + 4 色板 + 加号按钮（打开取色器）+ 恢复预设 */
    private VBox colorEditRow(String label, String curHex, String presetHex, int presetIdx,
                              java.util.function.Consumer<String> onApply) {
        VBox row = new VBox(6);

        // 标签行：当前色预览圆点 + 名称 + HEX
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        String hex = (curHex != null && !curHex.isEmpty()) ? curHex : presetHex;
        javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(8,
                safeColor(hex));
        dot.setStroke(javafx.scene.paint.Color.web("#00000033"));
        Label tv = new Label(label + "  " + hex);
        tv.setStyle("-fx-text-fill: " + Theme.INK + "; -fx-font-size: 13px;");
        header.getChildren().addAll(dot, tv);

        // 色板：4 候选色 + 加号
        HBox swatches = new HBox(6);
        swatches.setAlignment(Pos.CENTER_LEFT);
        java.util.List<String> candidates = new java.util.ArrayList<>();
        candidates.add(presetHex);
        for (String[] p : Theme.PRESETS) {
            String hex2 = p[presetIdx];
            if (!hex2.equals(presetHex)) candidates.add(hex2);
        }
        for (int i = 0; i < 4 && i < candidates.size(); i++) {
            String hex2 = candidates.get(i);
            Button sw = new Button();
            sw.setStyle("-fx-background-color: " + hex2 + "; -fx-min-width: 44; -fx-min-height: 34;"
                    + "-fx-background-radius: 8; -fx-cursor: hand;"
                    + "-fx-border-color: " + (hex.equalsIgnoreCase(hex2) ? Theme.INK : "#00000022") + ";"
                    + "-fx-border-radius: 8;");
            sw.setOnAction(e -> onApply.accept(hex2));
            swatches.getChildren().add(sw);
        }
        Button plus = new Button("＋");
        plus.setStyle("-fx-background-color: " + blendTransparent() + "; -fx-min-width: 44; -fx-min-height: 34;"
                + "-fx-background-radius: 8; -fx-border-color: #00000022; -fx-border-radius: 8;"
                + "-fx-text-fill: " + Theme.ACCENT + "; -fx-font-size: 18px; -fx-cursor: hand;");
        plus.setOnAction(e -> showColorPicker(label, curHex, presetHex, onApply));
        swatches.getChildren().add(plus);

        Button resetBtn = new Button("恢复预设");
        resetBtn.setStyle(Theme.ghostBtn() + "-fx-font-size: 12px;");
        resetBtn.setOnAction(e -> onApply.accept(""));

        row.getChildren().addAll(header, swatches, resetBtn);
        return row;
    }

    /** 打开取色器弹窗（用 Stage + 文本输入代替 ColorPicker，避免 ColorPicker 弹出面板在场景重建后残留） */
    private void showColorPicker(String label, String curHex, String presetHex,
                                 java.util.function.Consumer<String> onApply) {
        String initial = (curHex != null && !curHex.isEmpty()) ? curHex : presetHex;

        javafx.stage.Stage pickerStage = new javafx.stage.Stage();
        pickerStage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        pickerStage.setTitle("选择" + label);
        if (getScene() != null && getScene().getWindow() != null)
            pickerStage.initOwner(getScene().getWindow());

        // HEX 输入框
        TextField hexInput = new TextField(initial);
        hexInput.setStyle(Theme.input());
        hexInput.setMaxWidth(180);
        Label hexLabel = new Label("HEX 色值");
        hexLabel.setStyle(Theme.soft());

        // 颜色预览方块
        Region preview = new Region();
        preview.setPrefSize(48, 48);
        preview.setStyle("-fx-background-color: " + initial + "; -fx-background-radius: 8;"
                + "-fx-border-color: #00000022; -fx-border-radius: 8;");
        hexInput.textProperty().addListener((obs, old, val) -> {
            String v = val.startsWith("#") ? val : "#" + val;
            try {
                javafx.scene.paint.Color.web(v);
                preview.setStyle("-fx-background-color: " + v + "; -fx-background-radius: 8;"
                        + "-fx-border-color: #00000022; -fx-border-radius: 8;");
            } catch (Exception ignored) { }
        });

        // 常用色板
        String[] commonColors = {
                "#d2893f", "#e8a87c", "#f5cba7", "#fdf2e9",
                "#7d8fb3", "#a8b5d4", "#c5d0e8", "#e8edf5",
                "#5ea07c", "#85c4a0", "#b5ddc5", "#ddf0e5",
                "#d18a9a", "#e8b0bf", "#f2ccd6", "#fae8ed",
                "#e74c3c", "#f39c12", "#2ecc71", "#3498db",
                "#9b59b6", "#1abc9c", "#e67e22", "#34495e"
        };
        FlowPane swatches = new FlowPane(6, 6);
        Label swatchLabel = new Label("快速选择");
        swatchLabel.setStyle(Theme.soft());
        for (String hex : commonColors) {
            Button sw = new Button();
            sw.setStyle("-fx-background-color: " + hex + "; -fx-min-width: 36; -fx-min-height: 28;"
                    + "-fx-background-radius: 6; -fx-cursor: hand;"
                    + "-fx-border-color: #00000022; -fx-border-radius: 6;");
            sw.setOnAction(e -> hexInput.setText(hex));
            swatches.getChildren().add(sw);
        }

        // 确定 / 取消
        Button ok = new Button("确定");
        ok.setStyle(Theme.primaryBtn());
        Button cancel = new Button("取消");
        cancel.setStyle(Theme.ghostBtn());
        HBox buttons = new HBox(10, ok, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(14,
                new HBox(16, preview, new VBox(6, hexLabel, hexInput)),
                swatchLabel, swatches, buttons);
        content.setPadding(new Insets(16));
        content.setStyle("-fx-background-color: " + Theme.CARD + ";");

        pickerStage.setScene(new javafx.scene.Scene(content));

        ok.setOnAction(e -> {
            String raw = hexInput.getText().trim();
            if (!raw.startsWith("#")) raw = "#" + raw;
            final String val = raw;
            try {
                javafx.scene.paint.Color.web(val);
                pickerStage.close();
                Platform.runLater(() -> onApply.accept(val));
            } catch (Exception ex) {
                hexInput.setStyle(Theme.input() + "-fx-border-color: #c9706a;");
            }
        });
        cancel.setOnAction(e -> pickerStage.close());

        pickerStage.show();
    }

    private static javafx.scene.paint.Color safeColor(String hex) {
        try { return javafx.scene.paint.Color.web(hex); } catch (Exception e) { return javafx.scene.paint.Color.GRAY; }
    }

    private static String blendTransparent() {
        // 半透明黑（加号底）
        return "rgba(0,0,0,0.04)";
    }
}