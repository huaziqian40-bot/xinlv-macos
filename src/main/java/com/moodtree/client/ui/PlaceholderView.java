/* 占位页：推荐/树洞/我的 正在开发时临时显示。 */
package com.moodtree.client.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

public class PlaceholderView extends VBox implements Refreshable {

    public PlaceholderView(String key) {
        setAlignment(Pos.CENTER);
        setSpacing(10);
        setStyle(Theme.page());
        Label icon = new Label("🚧");
        icon.setStyle("-fx-font-size: 48px;");
        Label text = new Label("「" + key + "」页面开发中…");
        text.setStyle(Theme.soft());
        getChildren().addAll(icon, text);
    }

    @Override
    public void refresh() { }
}
