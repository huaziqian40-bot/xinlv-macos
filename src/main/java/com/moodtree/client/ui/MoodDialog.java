/* 记心情弹窗：10 个心情按钮（中性底色，选中时强调色描边）+ 强度无极滑动条 + 备注。
 * 新建和编辑共用（编辑传入已有记录）。保存按钮用强调色，取消按钮无边框。 */
package com.moodtree.client.ui;

import com.moodtree.client.model.MoodEntry;
import com.moodtree.client.model.MoodMeta;
import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.LocalDate;

public class MoodDialog extends Dialog<MoodEntry> {

    public MoodDialog(LocalDate date, MoodEntry editing) {
        setTitle(editing == null ? "记录心情" : "修改记录");
        setHeaderText(editing == null
                ? date.getMonthValue() + "月" + date.getDayOfMonth() + "日，现在感觉怎么样？"
                : "修改这条心情记录");
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Button okBtn = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okBtn.setText("保存");
        okBtn.setStyle(Theme.primaryBtn());
        Button cancelBtn = (Button) getDialogPane().lookupButton(ButtonType.CANCEL);
        cancelBtn.setText("取消");
        cancelBtn.setStyle(Theme.ghostBtn());

        ToggleGroup group = new ToggleGroup();
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        int i = 0;
        for (MoodMeta m : MoodMeta.all()) {
            ToggleButton tile = new ToggleButton(m.emoji + " " + m.label);
            tile.setUserData(m.key);
            tile.setToggleGroup(group);
            tile.setPrefSize(104, 44);
            tile.setStyle(moodTileStyle(m, false));
            // 选中态：心情色描边（与手机端 chip 选中态对齐）
            group.selectedToggleProperty().addListener((obs, old, now) -> {
                boolean sel = now != null && m.key.equals(now.getUserData());
                tile.setStyle(moodTileStyle(m, sel));
            });
            if (editing != null && m.key.equals(editing.mood)) tile.setSelected(true);
            grid.add(tile, i % 5, i / 5);
            i++;
        }

        // ---- 情绪强度无极滑动条 ----
        int initLevel = editing != null ? editing.intensityLevel : 2;
        int initPct = editing != null ? editing.intensityPercent : 50;
        Label intensityLabel = new Label("情绪强度：");
        intensityLabel.setStyle(Theme.soft());
        Label intensityText = new Label(labelForPct(initPct));
        intensityText.setStyle("-fx-font-size: 13px; -fx-text-fill: " + Theme.ACCENT + "; -fx-font-weight: bold;");
        Slider intensitySlider = new Slider(0, 100, initPct);
        intensitySlider.setShowTickMarks(false);
        intensitySlider.setShowTickLabels(false);
        intensitySlider.setPrefWidth(300);
        // 选心情时显示强度行
        VBox intensityRow = new VBox(6);
        HBox intensityHead = new HBox(8, intensityLabel, intensityText);
        intensityHead.setAlignment(Pos.CENTER_LEFT);
        intensityRow.getChildren().addAll(intensityHead, intensitySlider);
        intensityRow.setPadding(new Insets(8, 0, 0, 0));
        intensityRow.setVisible(editing != null);  // 编辑时可见，新建时选心情后显示

        // 选心情时显示强度行
        group.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now != null) {
                intensityRow.setVisible(true);
                // 用选中心情色更新滑块样式
                String key = (String) now.getUserData();
                MoodMeta m = MoodMeta.of(key);
                intensitySlider.setStyle(
                    "-fx-control-inner-background: " + m.color + ";"
                  + "-fx-accent: " + m.color + ";"
                  + "-fx-background-color: " + m.color + "33;"
                );
            }
        });

        // 拖动更新标签
        intensitySlider.valueProperty().addListener((obs, old, val) -> {
            int pct = val.intValue();
            intensityText.setText(labelForPct(pct));
        });

        Label noteLabel = new Label("想说点什么？（可以留空）");
        noteLabel.setStyle(Theme.soft());
        TextArea note = new TextArea(editing == null ? "" : editing.note);
        note.setPromptText("今天发生了什么…");
        note.setPrefRowCount(3);
        note.setWrapText(true);
        note.setStyle(Theme.input());

        VBox body = new VBox(14, grid, intensityRow, noteLabel, note);
        body.setPadding(new Insets(16));
        body.setAlignment(Pos.CENTER_LEFT);
        getDialogPane().setContent(body);
        getDialogPane().setStyle(Theme.page());

        // 弹窗缩放淡入动画（与手机端 MoodDialogFragment scale_in 对齐）
        setOnShowing(d -> {
            javafx.scene.Node content = getDialogPane().getContent();
            ScaleTransition st = new ScaleTransition(Duration.millis(250), content);
            st.setFromX(0.85);
            st.setFromY(0.85);
            st.setToX(1.0);
            st.setToY(1.0);
            st.play();
        });

        setResultConverter(bt -> {
            if (bt != ButtonType.OK || group.getSelectedToggle() == null) return null;
            String mood = (String) group.getSelectedToggle().getUserData();
            int pct = (int) intensitySlider.getValue();
            int level = levelForPct(pct);
            if (editing != null) {
                editing.mood = mood;
                editing.note = note.getText().trim();
                editing.intensityLevel = level;
                editing.intensityPercent = pct;
                editing.touchLocal();
                return editing;
            }
            return MoodEntry.create(date, mood, note.getText(), level, pct);
        });
    }

    /** 心情按钮：中性底色（CARD），选中时心情色描边 + 微色底 */
    private static String moodTileStyle(MoodMeta m, boolean selected) {
        if (selected) {
            String bg = Theme.lighten(m.color, 0.75f);
            return "-fx-background-color: " + bg + "; -fx-background-radius: 10;"
                    + "-fx-font-size: 14px; -fx-text-fill: " + Theme.INK + "; -fx-cursor: hand;"
                    + "-fx-border-color: " + m.color + "; -fx-border-width: 2; -fx-border-radius: 10;";
        }
        return "-fx-background-color: " + Theme.CARD + "; -fx-background-radius: 10;"
                + "-fx-font-size: 14px; -fx-text-fill: " + Theme.INK + "; -fx-cursor: hand;"
                + "-fx-border-color: transparent; -fx-border-width: 2; -fx-border-radius: 10;";
    }

    private static String labelForPct(int pct) {
        if (pct <= 20) return "略微";
        else if (pct <= 45) return "有点";
        else if (pct <= 70) return "相当";
        else return "十分";
    }

    private static int levelForPct(int pct) {
        if (pct <= 20) return 1;
        else if (pct <= 45) return 2;
        else if (pct <= 70) return 3;
        else return 4;
    }
}