/* 登录/注册页：只留账号密码（服务器地址在"我的→设置"里改，不在这里暴露技术细节）。
 * 顶部「登录 / 注册」切换；注册多一个确认密码和免责声明勾选，成功后直接进主界面。
 * 不想注册可以点"先逛逛"以游客身份使用，数据存本机，登录后自动同步上云。 */
package com.moodtree.client.ui;

import com.google.gson.JsonObject;
import com.moodtree.client.AppContext;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public class LoginView extends VBox {

    private final AppContext app;

    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final PasswordField password2 = new PasswordField();
    private final CheckBox agree = new CheckBox("我已阅读并同意");
    private final Button submit = new Button();
    private final Label status = new Label();
    private boolean registerMode;

    public LoginView(AppContext app) {
        this.app = app;
        setAlignment(Pos.CENTER);
        setSpacing(14);
        setPadding(new Insets(40));
        setStyle(Theme.page());

        Label logo = new Label();
        // 登录页顶部彩色圆点树 logo（无底 PNG）；加载失败退回 emoji
        try {
            javafx.scene.image.ImageView logoImg = new javafx.scene.image.ImageView(
                    new javafx.scene.image.Image(getClass().getResourceAsStream("/logo.png")));
            logoImg.setFitWidth(88);
            logoImg.setFitHeight(88);
            logoImg.setPreserveRatio(true);
            logo.setGraphic(logoImg);
        } catch (Exception ignored) {
            logo.setText("🌳");
            logo.setStyle("-fx-font-size: 56px;");
        }
        Label title = new Label("心履");
        title.setStyle(Theme.h1());
        Label sub = new Label("把心情种下，让它慢慢发芽");
        sub.setStyle(Theme.soft());

        // ---- 登录 / 注册 切换 ----
        Button tabLogin = new Button("登录");
        Button tabRegister = new Button("注册");
        tabLogin.setOnAction(e -> setMode(false, tabLogin, tabRegister));
        tabRegister.setOnAction(e -> setMode(true, tabLogin, tabRegister));
        HBox tabs = new HBox(0, tabLogin, tabRegister);
        tabs.setAlignment(Pos.CENTER);

        username.setPromptText("账号");
        username.setStyle(Theme.input());
        username.setMaxWidth(320);
        password.setPromptText("密码");
        password.setStyle(Theme.input());
        password.setMaxWidth(320);
        password2.setPromptText("再输一遍密码");
        password2.setStyle(Theme.input());
        password2.setMaxWidth(320);

        // 免责声明勾选 + 链接（用浏览器打开网页版免责声明星页）
        Hyperlink disclaimer = new Hyperlink("《免责声明》");
        disclaimer.setStyle("-fx-text-fill: " + Theme.ACCENT_D + "; -fx-font-size: 13px;");
        disclaimer.setOnAction(e -> openDisclaimer());
        agree.setStyle("-fx-font-size: 13px; -fx-text-fill: " + Theme.INK + ";");
        HBox agreeRow = new HBox(0, agree, disclaimer);
        agreeRow.setAlignment(Pos.CENTER);

        submit.setStyle(Theme.primaryBtn());
        submit.setDefaultButton(true);
        submit.setMaxWidth(320);

        status.setStyle(Theme.soft());
        status.setWrapText(true);
        status.setMaxWidth(320);

        Hyperlink guest = new Hyperlink("不想注册？先随便逛逛（游客模式）");
        guest.setStyle("-fx-text-fill: " + Theme.ACCENT_D + "; -fx-font-size: 13px;");
        guest.setOnAction(e -> app.enterGuest());
        Label guestNote = new Label("游客的数据只保存在这台电脑，登录后自动同步到云端");
        guestNote.setStyle("-fx-text-fill: " + Theme.INK_SOFT + "; -fx-font-size: 12px;");

        Region gap = new Region();
        gap.setPrefHeight(4);

        submit.setOnAction(e -> submit());

        getChildren().addAll(logo, title, sub, gap, tabs, username, password,
                password2, agreeRow, submit, status, guest, guestNote);
        // 开发调试：-Dmoodtree.auth=register 启动直接显示注册形态
        setMode("register".equals(System.getProperty("moodtree.auth")), tabLogin, tabRegister);
    }

    /** 切换登录/注册形态：注册多显示确认密码和免责声明 */
    private void setMode(boolean register, Button tabLogin, Button tabRegister) {
        registerMode = register;
        password2.setVisible(register);
        password2.setManaged(register);
        ((HBox) agree.getParent()).setVisible(register);
        ((HBox) agree.getParent()).setManaged(register);
        submit.setText(register ? "注 册" : "登 录");
        status.setText("");
        String on = "-fx-background-color: " + Theme.ACCENT + "; -fx-text-fill: white;"
                + "-fx-padding: 6 22; -fx-font-size: 13px; -fx-cursor: hand;";
        String off = "-fx-background-color: transparent; -fx-text-fill: " + Theme.INK_SOFT + ";"
                + "-fx-padding: 6 22; -fx-font-size: 13px; -fx-cursor: hand;"
                + "-fx-border-color: #00000022;";
        tabLogin.setStyle((register ? off : on) + "-fx-background-radius: 8 0 0 8; -fx-border-radius: 8 0 0 8;");
        tabRegister.setStyle((register ? on : off) + "-fx-background-radius: 0 8 8 0; -fx-border-radius: 0 8 8 0;");
    }

    private void submit() {
        String name = username.getText().trim();
        String pw = password.getText();
        if (registerMode) {
            if (!pw.equals(password2.getText())) {
                status.setText("两次输入的密码不一样");
                return;
            }
            if (!agree.isSelected()) {
                status.setText("请先勾选同意《免责声明》");
                return;
            }
        }
        submit.setDisable(true);
        status.setText(registerMode ? "正在注册…" : "正在登录…");
        Bg.run(() -> {
                    JsonObject r = registerMode
                            ? app.api.register(name, pw)
                            : app.api.login(name, pw);
                    app.config.setToken(r.get("token").getAsString());
                    app.config.setUsername(r.get("username").getAsString());
                    app.config.setGuestMode(false);   // 转正：不再是游客
                    app.config.save();
                    app.sync.refreshCatalog();        // 离线推荐用的内容缓存
                    app.sync.sync();                  // 游客期间的记录会在这里一并推上云
                    return null;
                },
                ok -> app.showMain(),
                err -> {
                    submit.setDisable(false);
                    status.setText(err instanceof com.moodtree.client.api.ApiClient.ApiException
                            ? err.getMessage()
                            : (registerMode ? "注册失败：" : "登录失败：") + err.getMessage());
                });
    }

    private void openDisclaimer() {
        try {
            java.awt.Desktop.getDesktop().browse(
                    new java.net.URI(app.config.serverBase() + "/disclaimer/"));
        } catch (Exception e) {
            status.setText("打不开浏览器，免责声明可在网页版查看");
        }
    }
}
