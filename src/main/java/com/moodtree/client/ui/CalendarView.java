/* 日历页（首页）：左侧月历 + 当天心情记录列表。
 * 一天可多条；月历格子的代表心情规则与网页版一致：
 * 有重复心情取重复最多的，否则取最新一条。
 * 记录心情后自动跳转推荐页（通过 onMoodSaved 回调）。 */
package com.moodtree.client.ui;

import com.moodtree.client.AppContext;
import com.moodtree.client.model.MoodEntry;
import com.moodtree.client.model.MoodMeta;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class CalendarView extends BorderPane implements Refreshable {

    private final AppContext app;
    private final Consumer<String> onMoodSaved;
    private YearMonth month = YearMonth.now();
    private LocalDate selected = LocalDate.now();

    private GridPane grid;
    private Label monthLabel;
    private VBox detailList;
    private Label detailTitle;
    private Timeline clickTimer;   // 单击延迟刷新，避免和双击冲突

    public CalendarView(AppContext app) {
        this(app, null);
    }

    public CalendarView(AppContext app, Consumer<String> onMoodSaved) {
        this.app = app;
        this.onMoodSaved = onMoodSaved;
        setStyle(Theme.page());
        setPadding(new Insets(24));

        // ---- 顶栏：月份切换 ----
        Button prev = navBtn("‹");
        Button next = navBtn("›");
        prev.setOnAction(e -> { month = month.minusMonths(1); refresh(); });
        next.setOnAction(e -> { month = month.plusMonths(1); refresh(); });
        monthLabel = new Label();
        monthLabel.setStyle(Theme.h1());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox top = new HBox(12, prev, monthLabel, next, spacer);
        top.setAlignment(Pos.CENTER_LEFT);

        // ---- 记心情按钮（日历上方居中，大号醒目）----
        Button add = new Button("＋ 记心情");
        add.setStyle(Theme.primaryBtn() + "-fx-font-size: 16px; -fx-padding: 10 28;");
        add.setMaxWidth(Double.MAX_VALUE);
        add.setOnAction(e -> openMoodDialog(null));
        HBox addRow = new HBox(add);
        addRow.setPadding(new Insets(4, 0, 8, 0));

        VBox header = new VBox(6, top, addRow);
        setTop(header);

        // ---- 中间：月历格子 ----
        grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(6);
        for (int c = 0; c < 7; c++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(100.0 / 7);
            grid.getColumnConstraints().add(cc);
        }
        VBox.setVgrow(grid, Priority.ALWAYS);
        setCenter(grid);
        BorderPane.setMargin(grid, new Insets(16, 0, 16, 0));

        // ---- 底部：选中日期的记录列表 ----
        detailTitle = new Label();
        detailTitle.setStyle(Theme.h2());
        detailList = new VBox(8);
        VBox detail = new VBox(10, detailTitle, detailList);
        detail.setPadding(new Insets(16));
        detail.setStyle(Theme.cardBg());
        setBottom(detail);

        refresh();
    }

    private Button navBtn(String text) {
        Button b = new Button(text);
        b.setStyle(Theme.ghostBtn() + "-fx-font-size: 18px;");
        return b;
    }

    @Override
    public void refresh() {
        monthLabel.setText(month.getYear() + "年" + month.getMonthValue() + "月");
        renderGrid();
        renderDetail();
    }

    /** 取某月全部记录，按日期分组算代表心情 */
    private Map<LocalDate, MoodMeta> representatives(List<MoodEntry> entries) {
        Map<LocalDate, List<MoodEntry>> byDate = new LinkedHashMap<>();
        for (MoodEntry e : entries) byDate.computeIfAbsent(e.date, k -> new ArrayList<>()).add(e);

        Map<LocalDate, MoodMeta> rep = new HashMap<>();
        for (var day : byDate.entrySet()) {
            // 统计每个心情出现次数与最后出现时刻
            Map<String, int[]> counts = new HashMap<>();
            Map<String, MoodEntry> latest = new HashMap<>();
            for (MoodEntry e : day.getValue()) {
                counts.computeIfAbsent(e.mood, k -> new int[1])[0]++;
                latest.merge(e.mood, e, (a, b) ->
                        (a.at != null && b.at != null && a.at.isAfter(b.at)) ? a : b);
            }
            String best = null;
            int bestCount = -1;
            for (var m : counts.entrySet()) {
                if (best == null || m.getValue()[0] > bestCount
                        || (m.getValue()[0] == bestCount
                            && isLater(latest.get(m.getKey()), latest.get(best)))) {
                    best = m.getKey();
                    bestCount = m.getValue()[0];
                }
            }
            rep.put(day.getKey(), MoodMeta.of(best));
        }
        return rep;
    }

    private boolean isLater(MoodEntry a, MoodEntry b) {
        if (a == null) return false;
        if (b == null || b.at == null) return true;
        return a.at != null && a.at.isAfter(b.at);
    }

    private void renderGrid() {
        grid.getChildren().clear();
        String[] heads = {"一", "二", "三", "四", "五", "六", "日"};
        for (int c = 0; c < 7; c++) {
            Label h = new Label(heads[c]);
            h.setStyle(Theme.soft());
            h.setMaxWidth(Double.MAX_VALUE);
            h.setAlignment(Pos.CENTER);
            grid.add(h, c, 0);
        }

        List<MoodEntry> entries;
        Map<LocalDate, MoodMeta> rep;
        try {
            entries = app.db.listForMonth(month.toString());
        } catch (Exception e) {
            entries = List.of();
        }
        rep = representatives(entries);

        LocalDate first = month.atDay(1);
        int offset = first.getDayOfWeek().getValue() - 1;   // 周一开头
        LocalDate today = LocalDate.now();

        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            LocalDate date = month.atDay(d);
            int cellIndex = offset + d - 1;
            int row = cellIndex / 7 + 1;
            int col = cellIndex % 7;

            Label num = new Label(String.valueOf(d));
            num.setStyle("-fx-font-size: 13px; -fx-text-fill: " + Theme.INK + ";");

            MoodMeta m = rep.get(date);
            ImageView emoji = m == null ? new ImageView() : EmojiUtil.emoji(22, m.emoji);

            VBox cell = new VBox(2, num, emoji);
            cell.setAlignment(Pos.TOP_CENTER);
            cell.setPadding(new Insets(8, 4, 8, 4));
            cell.setMinHeight(64);

            String bg = Theme.CARD;
            String border = "-fx-border-color: transparent;";
            boolean isFuture = date.isAfter(today);
            if (isFuture) {
                bg = Theme.isDarkTheme() ? "#2a2a2a" : "#e8e4dc";
                cell.setOpacity(0.5);
            }
            if (date.equals(today)) border = "-fx-border-color: " + Theme.ACCENT + "; -fx-border-width: 2;";
            if (date.equals(selected)) bg = "#e9e2d0";
            if (m != null && !isFuture) bg = m.color + "55";   // 心情色做淡底，未来日期不显示心情色
            cell.setStyle("-fx-background-color: " + bg + "; -fx-background-radius: 10;"
                    + border + "-fx-border-radius: 10; -fx-cursor: " + (isFuture ? "default" : "hand") + ";");

            // 单击选中（延迟刷新，等可能的双击）；双击打开记心情弹窗（未来日期除外）
            cell.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) {
                    if (clickTimer != null) clickTimer.stop();
                    selected = date;
                    if (!isFuture) openMoodDialog(null);
                } else {
                    selected = date;
                    if (clickTimer != null) clickTimer.stop();
                    clickTimer = new Timeline(new KeyFrame(Duration.millis(230), ev -> refresh()));
                    clickTimer.play();
                }
            });
            grid.add(cell, col, row);
        }
    }

    private void renderDetail() {
        detailTitle.setText(selected.getMonthValue() + "月" + selected.getDayOfMonth()
                + "日  " + weekName(selected) + (selected.equals(LocalDate.now()) ? "（今天）" : ""));
        detailList.getChildren().clear();

        List<MoodEntry> entries;
        try {
            entries = app.db.listForDate(selected);
        } catch (Exception e) {
            entries = List.of();
        }
        if (entries.isEmpty()) {
            Label empty = new Label("这一天还没有记录，点右上角「＋ 记心情」写一条吧");
            empty.setStyle(Theme.soft());
            detailList.getChildren().add(empty);
            return;
        }

        DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");
        for (MoodEntry e : entries) {
            MoodMeta m = MoodMeta.of(e.mood);
            ImageView emoji = EmojiUtil.emoji(20, m.emoji);
            Label time = new Label(e.at == null ? "" : e.at.format(hm));
            time.setStyle(Theme.soft());
            time.setMinWidth(46);
            Label note = new Label(e.note.isEmpty() ? "（" + m.label + "）" : e.note);
            note.setStyle("-fx-font-size: 14px; -fx-text-fill: " + Theme.INK + ";");
            note.setWrapText(true);
            HBox.setHgrow(note, Priority.ALWAYS);
            note.setMaxWidth(Double.MAX_VALUE);

            // 强度标签
            String intensityLabel = intensityText(e.intensityLevel, e.intensityPercent);
            Label intensity = new Label(intensityLabel);
            intensity.setStyle("-fx-font-size: 11px; -fx-text-fill: " + Theme.ACCENT + ";");

            Region dirtyMark = new Region();
            if (e.dirty) {
                Label dot = new Label("●待同步");
                dot.setStyle("-fx-text-fill: #d1905f; -fx-font-size: 11px;");
                dirtyMark = dot;
            }

            Button del = new Button("删除");
            del.setStyle(Theme.dangerBtn());
            del.setOnAction(ev -> deleteEntry(e));

            HBox row = new HBox(10, emoji, time, note, intensity, dirtyMark, del);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(8, 12, 8, 12));
            row.setStyle("-fx-background-color: " + m.color + "33; -fx-background-radius: 8; -fx-cursor: hand;");
            // 点击行进入编辑（删除按钮除外，按钮事件不会被行点击抢走）
            row.setOnMouseClicked(ev -> openMoodDialog(e));
            detailList.getChildren().add(row);

            // 记录行淡入动画
            row.setOpacity(0);
            FadeTransition rowFade = new FadeTransition(Duration.millis(250), row);
            rowFade.setFromValue(0);
            rowFade.setToValue(1);
            rowFade.setDelay(Duration.millis(detailList.getChildren().size() * 40));
            rowFade.play();
        }
    }

    private String weekName(LocalDate d) {
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return names[d.getDayOfWeek().getValue() - 1];
    }

    /** 根据强度百分位返回中文标签 */
    private static String intensityText(int level, int pct) {
        String[] labels = {"", "略微", "有点", "相当", "十分"};
        String l = level >= 1 && level <= 4 ? labels[level] : "";
        if (pct > 0) return l + " · " + pct + "%";
        return l;
    }

    private void openMoodDialog(MoodEntry editing) {
        // 禁止记录未来情绪
        if (selected.isAfter(LocalDate.now())) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "不能记录未来的心情哦，等到了那一天再来吧", ButtonType.OK);
            alert.setHeaderText("日期无效");
            ((Button) alert.getDialogPane().lookupButton(ButtonType.OK)).setText("知道了");
            alert.showAndWait();
            return;
        }
        MoodDialog dialog = new MoodDialog(selected, editing);
        dialog.showAndWait();
        MoodEntry entry = dialog.getResult();
        if (entry != null) {
            Bg.run(() -> {
                        app.db.saveLocal(entry);
                        app.sync.sync();
                        return null;
                    }, ok -> {
                        refresh();
                        // 记录心情后自动跳转推荐页并选中对应心情（编辑时沿用心情）
                        if (onMoodSaved != null) onMoodSaved.accept(entry.mood);
                    },
                    err -> refresh());
        }
    }

    private void deleteEntry(MoodEntry e) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "删掉这条记录？（同步后网页端和其他设备也会删除）", ButtonType.OK, ButtonType.CANCEL);
        alert.setHeaderText("确认删除");
        ((Button) alert.getDialogPane().lookupButton(ButtonType.OK)).setText("删除");
        ((Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("取消");
        alert.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            e.deleted = true;
            e.touchLocal();
            Bg.run(() -> {
                        app.db.saveLocal(e);
                        app.sync.sync();
                        return null;
                    }, ok -> refresh(), err -> refresh());
        });
    }
}