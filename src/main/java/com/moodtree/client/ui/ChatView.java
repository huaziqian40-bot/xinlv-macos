/* AI 树洞页：仅联网可用（AI 在服务器上，危机硬拦截也在服务器做，与网页版完全一致）。
 * 气泡式聊天界面；命中危机词时服务器返回 crisis:true，用醒目的求助卡片展示。
 * 输入框无边框，滚动条现代风格。 */
package com.moodtree.client.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moodtree.client.AppContext;
import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

public class ChatView extends VBox implements Refreshable {

    private final AppContext app;
    private final VBox messages = new VBox(10);
    private final ScrollPane scroll;
    private final TextField input = new TextField();
    private final Button send = new Button("发送");
    private final Label banner = new Label();
    private boolean sending;
    private boolean historyLoaded;

    public ChatView(AppContext app) {
        this.app = app;
        setSpacing(12);
        setPadding(new Insets(24));
        setStyle(Theme.page());

        // ---- 顶栏 ----
        Label title = new Label("AI 树洞");
        title.setStyle(Theme.h1());
        Label sub = new Label("这里说的每句话都只属于你的树洞");
        sub.setStyle(Theme.soft());
        Button clear = new Button("清空对话");
        clear.setStyle(Theme.dangerBtn());
        clear.setOnAction(e -> clearHistory());
        HBox topText = new HBox(10, title, sub);
        topText.setAlignment(Pos.BASELINE_LEFT);
        HBox top = new HBox(topText, clear);
        HBox.setHgrow(topText, Priority.ALWAYS);
        top.setAlignment(Pos.CENTER_LEFT);

        // ---- 离线/提示横幅 ----
        banner.setWrapText(true);
        banner.setVisible(false);
        banner.setManaged(false);

        // ---- 消息区 ----
        messages.setPadding(new Insets(8));
        scroll = new ScrollPane(messages);
        scroll.setFitToWidth(true);
        Theme.transparentScrollPane(scroll);
        ScrollSensitivity.boost(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        messages.heightProperty().addListener((o, a, b) -> scroll.setVvalue(1.0));   // 自动滚到底

        // ---- 输入区 ----
        input.setPromptText("想聊点什么…");
        input.setStyle(Theme.input());
        HBox.setHgrow(input, Priority.ALWAYS);
        input.setOnAction(e -> send());
        send.setStyle(Theme.primaryBtn());
        send.setOnAction(e -> send());
        HBox inputRow = new HBox(10, input, send);
        inputRow.setAlignment(Pos.CENTER);

        getChildren().addAll(top, banner, scroll, inputRow);
        // 页面入场动画：标题、消息区、输入区依次淡入
        animateIn(top, 0);
        animateIn(scroll, 1);
        animateIn(inputRow, 2);
        refresh();
    }

    /** 淡入 + 上移动画（index 控制依次延迟） */
    private void animateIn(javafx.scene.Node v, int index) {
        v.setOpacity(0);
        v.setTranslateY(16);
        FadeTransition ft = new FadeTransition(Duration.millis(350), v);
        ft.setFromValue(0);
        ft.setToValue(1);
        ft.setDelay(Duration.millis(index * 120));
        TranslateTransition tt = new TranslateTransition(Duration.millis(350), v);
        tt.setFromY(16);
        tt.setToY(0);
        tt.setDelay(Duration.millis(index * 120));
        ft.play();
        tt.play();
    }

    @Override
    public void refresh() {
        if (historyLoaded) return;
        historyLoaded = true;
        if (!app.loggedIn()) {
            // 游客：树洞在服务器上，必须登录；日历/记录不受影响
            showBanner("🌱 树洞要登录后才能陪你聊天。点左下角「登录 / 注册」，游客期间的记录不会丢。");
            input.setDisable(true);
            send.setDisable(true);
            return;
        }
        Bg.run(() -> {
                    if (!app.api.ping()) return null;      // 离线
                    return app.api.chatHistory();
                },
                resp -> {
                    if (resp == null) {
                        showBanner("🌧 现在不在线，树洞需要联网才能陪你聊。心情记录和推荐离线也能用。");
                        input.setDisable(true);
                        send.setDisable(true);
                        return;
                    }
                    for (JsonElement el : resp.getAsJsonArray("messages")) {
                        JsonObject m = el.getAsJsonObject();
                        addBubble(m.get("role").getAsString(), m.get("content").getAsString(), false);
                    }
                    if (messages.getChildren().isEmpty()) {
                        addBubble("assistant", "你好呀，我是你的树洞。开心或难过的事，都可以说给我听。", false);
                    }
                },
                err -> showBanner("聊天记录加载失败：" + err.getMessage()));
    }

    private void showBanner(String text) {
        banner.setText(text);
        banner.setStyle("-fx-background-color: " + Theme.inputBgColor() + "; -fx-background-radius: 8;"
                + "-fx-padding: 10 14; -fx-font-size: 13px; -fx-text-fill: " + Theme.INK + ";");
        banner.setVisible(true);
        banner.setManaged(true);
    }

    private void send() {
        String text = input.getText().trim();
        if (text.isEmpty() || sending) return;
        sending = true;
        send.setDisable(true);
        input.setDisable(true);
        input.clear();
        addBubble("user", text, false);
        Label thinking = addBubble("assistant", "…", false);

        Bg.run(() -> app.api.chat(text),
                resp -> {
                    sending = false;
                    send.setDisable(false);
                    input.setDisable(false);
                    input.requestFocus();
                    messages.getChildren().remove(thinking);
                    boolean crisis = resp.has("crisis") && resp.get("crisis").getAsBoolean();
                    String reply = resp.get("reply").getAsString();
                    if (crisis && resp.has("hotline")) {
                        reply += "\n\n📞 心理援助热线：" + resp.get("hotline").getAsString();
                    }
                    addBubble("assistant", reply, crisis);
                },
                err -> {
                    sending = false;
                    send.setDisable(false);
                    input.setDisable(false);
                    messages.getChildren().remove(thinking);
                    addBubble("assistant", "（发送失败：" + err.getMessage() + "）", false);
                });
    }

    /** 主动消息到达时从外部追加一条（与普通气泡完全一致），供 MainShell 轮询调用。 */
    public void addProactiveMessage(String text) {
        addBubble("assistant", text, false);
    }

    /** 加一条气泡。crisis=true 时用醒目的暖色求助卡。 */
    private Label addBubble(String role, String text, boolean crisis) {
        Label bubble = new Label(text);
        bubble.setWrapText(true);
        bubble.setMaxWidth(520);
        bubble.setPadding(new Insets(10, 14, 10, 14));

        HBox row = new HBox(bubble);
        if ("user".equals(role)) {
            bubble.setStyle("-fx-background-color: " + Theme.ACCENT + "; -fx-text-fill: white;"
                    + "-fx-background-radius: 14 14 4 14; -fx-font-size: 14px;"
                    + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.08), 6, 0, 0, 1);");
            row.setAlignment(Pos.CENTER_RIGHT);
        } else if (crisis) {
            String crisisBg = Theme.isDarkTheme() ? "#50302f" : "#fdeaea";
            String crisisInk = Theme.isDarkTheme() ? "#ffd8d2" : "#8a3b34";
            bubble.setStyle("-fx-background-color: " + crisisBg + "; -fx-text-fill: " + crisisInk + ";"
                    + "-fx-background-radius: 14 14 14 4; -fx-font-size: 14px;"
                    + "-fx-border-color: #c9706a; -fx-border-radius: 14 14 14 4;");
            row.setAlignment(Pos.CENTER_LEFT);
        } else {
            bubble.setStyle("-fx-background-color: " + Theme.CARD + "; -fx-text-fill: " + Theme.INK + ";"
                    + "-fx-background-radius: 14 14 14 4; -fx-font-size: 14px;"
                    + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 6, 0, 0, 1);");
            row.setAlignment(Pos.CENTER_LEFT);
        }
        row.setPadding(new Insets(0, 60, 0, 0));
        if ("user".equals(role)) row.setPadding(new Insets(0, 0, 0, 60));
        messages.getChildren().add(row);
        // 新气泡淡入动画（与手机端 ChatFragment 对齐）
        row.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(300), row);
        ft.setFromValue(0);
        ft.setToValue(1);
        ft.play();
        return bubble;
    }

    private void clearHistory() {
        Bg.run(() -> {
                    app.api.chatClear();
                    return null;
                },
                ok -> {
                    messages.getChildren().clear();
                    addBubble("assistant", "对话已经清空啦。想说点什么新开始？", false);
                },
                err -> showBanner("清空失败：" + err.getMessage()));
    }
}