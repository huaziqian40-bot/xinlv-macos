/* phix 首启引导（CONTRACT §7）：「已有 phix 会话凭据吗？」→ 有→登录 / 没有→注册+跳过 / 跳过
 * 完成后标记 phixSessionDone=true，进入原有启动流程（登录页或主界面）。 */
package com.moodtree.client.ui;

import com.google.gson.JsonObject;
import com.moodtree.client.AppContext;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** phix 首启引导页：询问→登录/注册/跳过，全部非阻塞。 */
public class PhixSessionView extends VBox {

    private final AppContext app;

    public PhixSessionView(AppContext app) {
        this.app = app;
        setAlignment(Pos.CENTER);
        setSpacing(14);
        setPadding(new Insets(40));
        setStyle(Theme.page());
        showInquiry();
    }

    // ---- 询问页 ----

    private void showInquiry() {
        getChildren().clear();

        Label q = new Label("已有 phix 会话凭据吗？");
        q.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: " + Theme.INK + ";");
        q.setWrapText(true);
        q.setMaxWidth(360);

        Label hint = new Label("phix 是统一账号系统，登录后记录可跨设备同步");
        hint.setStyle(Theme.soft());
        hint.setWrapText(true);
        hint.setMaxWidth(360);

        Button have = new Button("有，去登录");
        have.setStyle(Theme.primaryBtn());
        have.setMaxWidth(320);
        have.setOnAction(e -> showPhixLogin());

        Button none = new Button("没有，去注册");
        none.setStyle(Theme.primaryBtn());
        none.setMaxWidth(320);
        none.setOnAction(e -> showPhixRegister());

        Button skip = new Button("跳过");
        skip.setStyle(Theme.ghostBtn() + "-fx-border-color: " + Theme.ACCENT
                + "; -fx-border-radius: 8; -fx-text-fill: " + Theme.ACCENT_D + ";");
        skip.setMaxWidth(320);
        skip.setOnAction(e -> finishGuide());

        getChildren().addAll(q, hint, have, none, skip);
    }

    // ---- phix 登录页 ----

    private void showPhixLogin() {
        getChildren().clear();

        Label title = new Label("phix 登录");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: " + Theme.INK + ";");

        Label serverLbl = new Label("服务器地址");
        serverLbl.setStyle(Theme.soft());
        TextField server = new TextField("http://192.168.5.41:8931");
        server.setStyle(Theme.input());
        server.setMaxWidth(360);

        TextField username = new TextField();
        username.setPromptText("账号");
        username.setStyle(Theme.input());
        username.setMaxWidth(360);

        PasswordField password = new PasswordField();
        password.setPromptText("密码");
        password.setStyle(Theme.input());
        password.setMaxWidth(360);

        Label status = new Label();
        status.setStyle(Theme.soft());
        status.setWrapText(true);
        status.setMaxWidth(360);

        Button submit = new Button("登 录");
        submit.setStyle(Theme.primaryBtn());
        submit.setDefaultButton(true);
        submit.setMaxWidth(360);

        Button back = new Button("返回");
        back.setStyle(Theme.ghostBtn());
        back.setOnAction(e -> showInquiry());

        Hyperlink skipLink = new Hyperlink("跳过，稍后再说");
        skipLink.setStyle("-fx-text-fill: " + Theme.ACCENT_D + "; -fx-font-size: 13px;");
        skipLink.setOnAction(e -> finishGuide());

        submit.setOnAction(e -> {
            String srv = server.getText().trim();
            String user = username.getText().trim();
            String pass = password.getText();
            if (srv.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                status.setText("请填写完整信息");
                return;
            }
            submit.setDisable(true);
            status.setText("正在登录…");
            Bg.run(() -> {
                        JsonObject r = app.api.phixLogin(srv, user, pass);
                        String tok = r.has("token") ? r.get("token").getAsString() : "";
                        app.config.setToken(tok);
                        app.config.setUsername(user);
                        app.config.setServerBase(srv);
                        app.config.setGuestMode(false);
                        app.config.save();
                        // 验证连通
                        try { app.api.phixVerifyManifest(srv, tok); } catch (Exception ignored) { }
                        return null;
                    },
                    ok -> finishGuide(),
                    err -> {
                        submit.setDisable(false);
                        String msg = err instanceof com.moodtree.client.api.ApiClient.ApiException
                                ? err.getMessage() : "登录失败：" + err.getMessage();
                        status.setText(msg + "\n");
                        Hyperlink errSkip = new Hyperlink("跳过");
                        errSkip.setStyle("-fx-text-fill: " + Theme.ACCENT_D + "; -fx-font-size: 13px;");
                        errSkip.setOnAction(e2 -> finishGuide());
                        if (!getChildren().contains(errSkip)) getChildren().add(errSkip);
                    });
        });

        Region gap = new Region();
        gap.setPrefHeight(4);

        getChildren().addAll(title, serverLbl, server, username, password,
                submit, status, back, gap, skipLink);
    }

    // ---- phix 注册页 ----

    private void showPhixRegister() {
        getChildren().clear();

        Label title = new Label("phix 注册");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: " + Theme.INK + ";");

        Label serverLbl = new Label("服务器地址");
        serverLbl.setStyle(Theme.soft());
        TextField server = new TextField("http://192.168.5.41:8931");
        server.setStyle(Theme.input());
        server.setMaxWidth(360);

        TextField username = new TextField();
        username.setPromptText("账号");
        username.setStyle(Theme.input());
        username.setMaxWidth(360);

        PasswordField password = new PasswordField();
        password.setPromptText("密码");
        password.setStyle(Theme.input());
        password.setMaxWidth(360);

        PasswordField password2 = new PasswordField();
        password2.setPromptText("再输一遍密码");
        password2.setStyle(Theme.input());
        password2.setMaxWidth(360);

        Label status = new Label();
        status.setStyle(Theme.soft());
        status.setWrapText(true);
        status.setMaxWidth(360);

        Button submit = new Button("注 册");
        submit.setStyle(Theme.primaryBtn());
        submit.setDefaultButton(true);
        submit.setMaxWidth(360);

        Button back = new Button("返回");
        back.setStyle(Theme.ghostBtn());
        back.setOnAction(e -> showInquiry());

        Hyperlink skipLink = new Hyperlink("跳过，稍后再说");
        skipLink.setStyle("-fx-text-fill: " + Theme.ACCENT_D + "; -fx-font-size: 13px;");
        skipLink.setOnAction(e -> finishGuide());

        submit.setOnAction(e -> {
            String srv = server.getText().trim();
            String user = username.getText().trim();
            String pass = password.getText();
            String pass2 = password2.getText();
            if (srv.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                status.setText("请填写完整信息");
                return;
            }
            if (!pass.equals(pass2)) {
                status.setText("两次输入的密码不一样");
                return;
            }
            submit.setDisable(true);
            status.setText("正在注册…");
            Bg.run(() -> {
                        JsonObject r = app.api.phixRegister(srv, user, pass);
                        String tok = r.has("token") ? r.get("token").getAsString() : "";
                        app.config.setToken(tok);
                        app.config.setUsername(user);
                        app.config.setServerBase(srv);
                        app.config.setGuestMode(false);
                        app.config.save();
                        // 验证连通
                        try { app.api.phixVerifyManifest(srv, tok); } catch (Exception ignored) { }
                        return null;
                    },
                    ok -> finishGuide(),
                    err -> {
                        submit.setDisable(false);
                        String msg = err instanceof com.moodtree.client.api.ApiClient.ApiException
                                ? err.getMessage() : "注册失败：" + err.getMessage();
                        status.setText(msg + "\n");
                        Hyperlink errSkip = new Hyperlink("跳过");
                        errSkip.setStyle("-fx-text-fill: " + Theme.ACCENT_D + "; -fx-font-size: 13px;");
                        errSkip.setOnAction(e2 -> finishGuide());
                        if (!getChildren().contains(errSkip)) getChildren().add(errSkip);
                    });
        });

        Region gap = new Region();
        gap.setPrefHeight(4);

        getChildren().addAll(title, serverLbl, server, username, password, password2,
                submit, status, back, gap, skipLink);
    }

    // ---- 完成引导，进入原有流程 ----

    private void finishGuide() {
        app.config.setPhixSessionDone(true);
        app.config.save();
        Platform.runLater(() -> {
            if (app.canEnterMain()) {
                app.showMain();
            } else {
                app.showLogin();
            }
        });
    }
}
