/* 推荐页：选心情 → 音乐/小行动/心理小知识/视频。
 * 在线时调 /api/v1/recommend/（与网页版同一套推荐逻辑）；
 * 离线时用本地目录缓存（登录时同步下来的）自行筛选，音乐仅在线可播。
 * 卡片带淡入+上移动画，chip 颜色与安卓端一致（浅底浅色、深底深色）。 */
package com.moodtree.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moodtree.client.AppContext;
import com.moodtree.client.model.MoodMeta;
import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RecommendView extends VBox implements Refreshable {

    private final AppContext app;
    private final FlowPane moodBar = new FlowPane(8, 8);
    private final VBox resultBox = new VBox(14);
    private final Label stateLabel = new Label("选一个心情，给你一些陪伴");
    private final ToggleGroup moodGroup = new ToggleGroup();
    private String selectedMood;
    private MediaPlayer player;          // 同时只放一首
    private Button playingBtn;

    public RecommendView(AppContext app) {
        this.app = app;
        setSpacing(16);
        setPadding(new Insets(24));
        setStyle(Theme.page());

        Label title = new Label("今日推荐");
        title.setStyle(Theme.h1());

        for (MoodMeta m : MoodMeta.all()) {
            ToggleButton chip = new ToggleButton();
            HBox chipContent = new HBox(6, ImageLoader.load(18, app.config.serverBase() + "/static/" + m.image, m.emoji), new Label(m.label));
            chipContent.setAlignment(Pos.CENTER);
            chip.setGraphic(chipContent);
            chip.setUserData(m.key);
            chip.setToggleGroup(moodGroup);
            chip.setStyle("-fx-background-radius: 20;"
                    + "-fx-padding: 6 14; -fx-font-size: 13px; -fx-cursor: hand;");
            // 选中态/未选中态样式（与安卓端一致：浅底浅色、深底深色）
            boolean isDark = Theme.isDarkTheme();
            String color = m.color;
            String unselected, selected, textColor;
            if (isDark) {
                unselected = Theme.darken(color, 0.75f);
                selected = Theme.darken(color, 0.50f);
                textColor = "#f0ece4";
            } else {
                unselected = Theme.lighten(color, 0.85f);
                selected = Theme.lighten(color, 0.60f);
                textColor = Theme.INK;
            }
            chip.selectedProperty().addListener((obs, old, sel) -> {
                chip.setStyle("-fx-background-radius: 20;"
                        + "-fx-padding: 6 14; -fx-font-size: 13px; -fx-cursor: hand;"
                        + "-fx-background-color: " + (sel ? selected : unselected) + ";"
                        + "-fx-text-fill: " + (sel ? color : textColor) + ";");
            });
            // 初始样式
            chip.setStyle("-fx-background-color: " + unselected + ";"
                    + "-fx-background-radius: 20;"
                    + "-fx-padding: 6 14; -fx-font-size: 13px; -fx-cursor: hand;"
                    + "-fx-text-fill: " + textColor + ";");
            chip.setOnAction(e -> {
                if (chip.isSelected()) select(m.key);
                else moodGroup.selectToggle(null);
            });
            moodBar.getChildren().add(chip);
        }

        stateLabel.setStyle(Theme.soft());
        resultBox.setFillWidth(true);

        VBox body = new VBox(12, stateLabel, resultBox);
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        Theme.transparentScrollPane(scroll);
        ScrollSensitivity.boost(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(title, moodBar, scroll);
        // 入场动画：标题、心情条、滚动区依次淡入
        animateIn(title, 0);
        animateIn(moodBar, 1);
        animateIn(scroll, 2);
    }

    /** 外部调用：选中某心情并加载推荐（用于记心情后自动跳转） */
    public void showMood(String mood) {
        for (javafx.scene.Node n : moodBar.getChildren()) {
            if (n instanceof ToggleButton tb && mood.equals(tb.getUserData())) {
                moodGroup.selectToggle(tb);
                tb.setSelected(true);
                select(mood);
                return;
            }
        }
    }

    private void select(String mood) {
        selectedMood = mood;
        stopPlayer();
        MoodMeta m = MoodMeta.of(mood);
        stateLabel.setText("正在为你准备「" + m.label + "」的推荐…");
        resultBox.getChildren().clear();
        Bg.run(() -> {
                    if (app.api.ping()) {
                        return app.api.recommend(mood);          // 在线：服务器推荐
                    }
                    return offlineRecommend(mood);               // 离线：本地缓存推荐
                },
                this::render,
                err -> stateLabel.setText("推荐加载失败：" + err.getMessage()));
    }

    /** 离线推荐：从目录缓存按心情筛选后随机挑几条（规则向服务端看齐：歌曲/活动/小知识/视频） */
    private JsonObject offlineRecommend(String mood) throws Exception {
        JsonObject rec = new JsonObject();
        rec.addProperty("mood", mood);
        rec.addProperty("offline", true);
        rec.add("info", JsonParser.parseString("\"（离线推荐来自本地缓存）\""));

        JsonArray songs = new JsonArray();
        JsonArray activities = new JsonArray();
        JsonArray tips = new JsonArray();
        JsonObject video = null;

        List<JsonObject> pool = new ArrayList<>();
        for (String p : app.db.catalogAll("songs")) {
            JsonObject o = JsonParser.parseString(p).getAsJsonObject();
            if (hasMood(o, mood)) pool.add(o);
        }
        Collections.shuffle(pool);
        pool.stream().limit(3).forEach(songs::add);

        pool.clear();
        for (String p : app.db.catalogAll("activities")) {
            JsonObject o = JsonParser.parseString(p).getAsJsonObject();
            if (hasMood(o, mood)) pool.add(o);
        }
        Collections.shuffle(pool);
        pool.stream().limit(3).forEach(a -> activities.add(a.get("text")));

        pool.clear();
        for (String p : app.db.catalogAll("tips")) {
            JsonObject o = JsonParser.parseString(p).getAsJsonObject();
            if (!o.has("moods") || hasMood(o, mood)) pool.add(o);
        }
        Collections.shuffle(pool);
        pool.stream().limit(2).forEach(tips::add);

        for (String p : app.db.catalogAll("videos")) {
            JsonObject o = JsonParser.parseString(p).getAsJsonObject();
            if (hasMood(o, mood)) { video = o; break; }
        }

        rec.add("songs", songs);
        rec.add("activities", activities);
        rec.add("tips", tips);
        if (video != null) rec.add("video", video);
        return rec;
    }

    private boolean hasMood(JsonObject o, String mood) {
        if (!o.has("moods") || !o.get("moods").isJsonArray()) return false;
        for (JsonElement el : o.getAsJsonArray("moods")) {
            if (mood.equals(el.getAsString())) return true;
        }
        return false;
    }

    private void render(JsonObject rec) {
        MoodMeta m = MoodMeta.of(rec.get("mood").getAsString());
        boolean offline = rec.has("offline") && rec.get("offline").getAsBoolean();
        resultBox.getChildren().clear();

        JsonArray songs = rec.has("songs") ? rec.getAsJsonArray("songs") : null;
        JsonArray acts = rec.has("activities") ? rec.getAsJsonArray("activities") : null;
        JsonArray tips = rec.has("tips") ? rec.getAsJsonArray("tips") : null;
        boolean hasVideo = rec.has("video") && rec.get("video").isJsonObject();
        boolean any = (songs != null && songs.size() > 0) || (acts != null && acts.size() > 0)
                || (tips != null && tips.size() > 0) || hasVideo;
        if (!any) {
            stateLabel.setText(offline
                    ? "本地还没有推荐内容缓存：联网登录一次后，离线也能用推荐"
                    : "这份心情暂时没有推荐内容");
            return;
        }
        stateLabel.setText("给「" + m.label + "」的你" + (offline ? "（离线缓存内容）" : ""));

        int delay = 0;

        // ---- 音乐 ----
        if (songs != null && songs.size() > 0) {
            VBox songCard = card("🎵 听点音乐");
            for (JsonElement el : songs) {
                JsonObject s = el.getAsJsonObject();
                String text = s.get("title").getAsString() + " - " + s.get("artist").getAsString();
                String url = s.has("url") && !s.get("url").isJsonNull() ? s.get("url").getAsString() : "";
                Button play = new Button("▶");
                play.setStyle(Theme.ghostBtn() + "-fx-text-fill: " + Theme.ACCENT + "; -fx-font-size: 14px;");
                Label name = new Label(text);
                name.setStyle("-fx-font-size: 14px; -fx-text-fill: " + Theme.INK + ";");
                HBox.setHgrow(name, Priority.ALWAYS);
                name.setMaxWidth(Double.MAX_VALUE);
                HBox row = new HBox(8, play, name);
                row.setAlignment(Pos.CENTER_LEFT);
                if (url.isEmpty() || offline) {
                    play.setDisable(true);   // 离线只有名字，音频在服务器上
                } else {
                    play.setOnAction(e -> togglePlay(url, play));
                }
                songCard.getChildren().add(row);
            }
            resultBox.getChildren().add(songCard);
            animateIn(songCard, delay++);
        }

        // ---- 小行动 ----
        if (acts != null && acts.size() > 0) {
            VBox actCard = card("🌱 可以试试这些小事");
            for (JsonElement el : acts) {
                Label t = new Label("· " + el.getAsString());
                t.setWrapText(true);
                t.setStyle("-fx-font-size: 14px; -fx-text-fill: " + Theme.INK + ";");
                actCard.getChildren().add(t);
            }
            resultBox.getChildren().add(actCard);
            animateIn(actCard, delay++);
        }

        // ---- 心理小知识 ----
        if (tips != null && tips.size() > 0) {
            VBox tipCard = card("💡 心理小知识");
            for (JsonElement el : tips) {
                JsonObject t = el.getAsJsonObject();
                Label h = new Label(t.get("title").getAsString());
                h.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + Theme.INK + ";");
                Label c = new Label(t.get("content").getAsString());
                c.setWrapText(true);
                c.setStyle("-fx-font-size: 13px; -fx-text-fill: " + Theme.INK + ";");
                tipCard.getChildren().addAll(h, c);
                if (t.has("source") && !t.get("source").getAsString().isEmpty()) {
                    Label src = new Label("出处：" + t.get("source").getAsString());
                    src.setStyle(Theme.soft());
                    tipCard.getChildren().add(src);
                }
            }
            resultBox.getChildren().add(tipCard);
            animateIn(tipCard, delay++);
        }

        // ---- 视频 ----
        if (rec.has("video") && rec.get("video").isJsonObject()) {
            JsonObject v = rec.getAsJsonObject();
            VBox videoCard = card("🎬 看个视频");
            Button link = new Button(v.get("title").getAsString() + "（浏览器打开）");
            link.setStyle(Theme.ghostBtn() + "-fx-text-fill: " + Theme.ACCENT + ";");
            link.setOnAction(e -> openBrowser(v.get("url").getAsString()));
            videoCard.getChildren().add(link);
            resultBox.getChildren().add(videoCard);
        }

        Region pad = new Region();
        pad.setPrefHeight(20);
        resultBox.getChildren().add(pad);
    }

    /** 卡片淡入动画（带递增延迟，依次弹出）—— 与手机端 RecommendFragment.animateIn 对齐 */
    private void animateIn(javafx.scene.Node v, int delay) {
        v.setOpacity(0);
        v.setTranslateY(20);
        FadeTransition ft = new FadeTransition(Duration.millis(300), v);
        ft.setFromValue(0);
        ft.setToValue(1);
        ft.setDelay(Duration.millis(delay * 120));
        TranslateTransition tt = new TranslateTransition(Duration.millis(300), v);
        tt.setFromY(20);
        tt.setToY(0);
        tt.setDelay(Duration.millis(delay * 120));
        ft.play();
        tt.play();
    }

    private VBox card(String title) {
        Label h = new Label(title);
        h.setStyle(Theme.h2());
        VBox box = new VBox(8, h);
        box.setPadding(new Insets(16));
        box.setStyle(Theme.cardBg());
        return box;
    }

    private void togglePlay(String url, Button btn) {
        if (playingBtn == btn) {
            stopPlayer();
            return;
        }
        stopPlayer();
        try {
            player = new MediaPlayer(new Media(url));
            player.setOnEndOfMedia(this::stopPlayer);
            player.setOnError(() -> Platform.runLater(this::stopPlayer));
            player.play();
            playingBtn = btn;
            btn.setText("⏸");
        } catch (Exception e) {
            stateLabel.setText("播放失败：" + e.getMessage());
        }
    }

    private void stopPlayer() {
        if (player != null) {
            try { player.stop(); player.dispose(); } catch (Exception ignored) { }
            player = null;
        }
        if (playingBtn != null) {
            playingBtn.setText("▶");
            playingBtn = null;
        }
    }

    private void openBrowser(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
        } catch (Exception e) {
            stateLabel.setText("打不开浏览器，请手动访问：" + url);
        }
    }

    @Override
    public void refresh() {
        // 心情定义可能被目录刷新覆盖，重建心情条（简单起见整页重选当前心情）
        if (selectedMood == null) return;
    }
}