/* 心履 macOS 桌面客户端 —— 程序入口。
 * 离线可用、联网与服务器（/api/v1/）同步数据，界面独立于网页版。
 * 启动路由：本地有令牌 → 直接进主界面（后台同步）；否则进登录页。
 * macOS 差异：设置应用名（菜单栏显示"心履"），Dock 图标用 .icns。 */
package com.moodtree.client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

public class Main extends Application {

    private AppContext app;

    @Override
    public void start(Stage stage) {
        // macOS 菜单栏应用名由 jpackage 的 --mac-package-name 控制（见 build.sh）
        // 编译阶段无需额外设置

        try {
            app = new AppContext(stage);
        } catch (Exception e) {
            // 本地数据库打不开等致命问题：提示后直接退出
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.ERROR,
                    "启动失败：" + e.getMessage());
            alert.setHeaderText("心履启动失败");
            alert.showAndWait();
            Platform.exit();
            return;
        }

        stage.setTitle("心履");
        // 窗口/任务栏图标（彩色圆点树 logo）
        try {
            stage.getIcons().add(new javafx.scene.image.Image(
                    getClass().getResourceAsStream("/logo.png")));
        } catch (Exception ignored) { }
        stage.setMinWidth(940);
        stage.setMinHeight(620);
        stage.setOnHidden(e -> app.close());

        if (app.canEnterMain()) {
            app.showMain();
        } else {
            app.showLogin();
        }
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}