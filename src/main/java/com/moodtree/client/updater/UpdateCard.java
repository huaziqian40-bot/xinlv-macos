/* 心履「发现新版本」卡片 —— 卡片确认制更新交互。
 *
 * 与 Android 端一致：检测到新版本后弹卡片，给出版本号、更新说明，
 * 三个按钮「取消 / 跳过本版本 / 更新」。只有在用户点「更新」后，
 * 调用方才真正下载并替换。本类只负责展示与返回用户选择，不做任何下载。
 *
 * 在 JavaFX 应用线程（Platform.runLater）上调用 showAndWait 使用。
 */
package com.moodtree.client.updater;

import com.moodtree.client.ui.Theme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class UpdateCard {

    /** 用户的选择。 */
    public enum Choice { CANCEL, SKIP, UPDATE }

    private static final String DEFAULT_NOTES = "本次更新以稳定性与体验改进为主。";

    private UpdateCard() { }

    /**
     * 弹出模态卡片并阻塞（须在 FX 线程调用，如 Platform.runLater 内）。
     * 返回用户选择；若意外的拿不到（例如 FX 已退出）返回 CANCEL。
     */
    public static Choice showAndWait(String latestVersion, String currentVersion, String latestNotes) {
        String notesText = (latestNotes == null || latestNotes.trim().isEmpty())
                ? DEFAULT_NOTES : latestNotes;

        // 卡片
        VBox card = new VBox(10);
        card.setPadding(new Insets(22, 22, 18, 22));
        card.setPrefWidth(400);
        card.setMaxWidth(440);
        card.setStyle(Theme.card() + "-fx-border-color: " + Theme.DIVIDER + "; -fx-border-radius: 12;");

        Label title = new Label("发现新版本");
        title.setStyle(Theme.h2());

        Label version = new Label("新版本 v" + latestVersion + "（当前 v" + currentVersion + "）");
        version.setStyle("-fx-text-fill: " + Theme.ACCENT + "; -fx-font-size: 14px; -fx-font-weight: bold;");
        version.setWrapText(true);

        Label section = new Label("本次更新内容");
        section.setStyle(Theme.h2() + "-fx-font-size: 13px;");

        Label notesLabel = new Label(notesText);
        notesLabel.setWrapText(true);
        notesLabel.setTextAlignment(TextAlignment.LEFT);
        notesLabel.setStyle(Theme.soft() + "-fx-line-spacing: 2;");

        ScrollPane notePane = new ScrollPane(notesLabel);
        notePane.setFitToWidth(true);
        notePane.setMaxHeight(210);
        Theme.transparentScrollPane(notePane);

        // 三个按钮
        Button cancel = button("取消", Theme.ghostBtn());
        Button skip   = button("跳过本版本", Theme.outlineButtonBg());
        Button update = button("更新", Theme.primaryBtn());
        update.setDefaultButton(true);

        HBox buttons = new HBox(10, cancel, skip, update);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(6, 0, 0, 0));

        card.getChildren().addAll(title, version, section, notePane, buttons);

        // 半透明遮罩全屏 + 卡片居中
        StackPane root = new StackPane(card);
        root.setStyle("-fx-background-color: rgba(0,0,0,0.30);");
        StackPane.setAlignment(card, Pos.CENTER);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);

        final Stage stage = new Stage(StageStyle.TRANSPARENT);
        stage.setScene(scene);
        stage.setResizable(false);

        // 让卡片可拖动（TRANSPARENT 无系统标题栏），两个坐标轴都跟随
        final double[] drag = new double[2];
        card.setOnMousePressed(e -> {
            drag[0] = e.getScreenX() - stage.getX();
            drag[1] = e.getScreenY() - stage.getY();
        });
        card.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - drag[0]);
            stage.setY(e.getScreenY() - drag[1]);
        });

        final Choice[] result = new Choice[] { Choice.CANCEL };
        cancel.setOnAction(e -> { result[0] = Choice.CANCEL; stage.close(); });
        skip.setOnAction(e -> { result[0] = Choice.SKIP; stage.close(); });
        update.setOnAction(e -> { result[0] = Choice.UPDATE; stage.close(); });
        // 关闭窗口（Esc 等）视为「取消」：result 默认即为 CANCEL
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) stage.close();
        });

        stage.showAndWait();
        return result[0];
    }

    private static Button button(String text, String style) {
        Button b = new Button(text);
        b.setStyle(style + "-fx-cursor: hand;");
        return b;
    }
}