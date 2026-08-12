/* 滚轮灵敏度工具：默认每格滚 40px，这里乘上 factor 放大，让滚轮更跟手。
 * 挂到 ScrollPane 上，接管滚轮事件并手动换算成视口位移。 */
package com.moodtree.client.ui;

import javafx.scene.control.ScrollPane;
import javafx.scene.input.ScrollEvent;

public class ScrollSensitivity {

    /** 放大系数：2.5 倍（用户反馈原来太慢） */
    private static final double FACTOR = 2.5;

    /** 给 ScrollPane 绑定放大的滚轮滚动 */
    public static void boost(ScrollPane scroll) {
        scroll.addEventFilter(ScrollEvent.SCROLL, e -> {
            double deltaY = e.getDeltaY();
            if (deltaY == 0) return;
            double range = scroll.getContent().getBoundsInParent().getHeight()
                    - scroll.getViewportBounds().getHeight();
            if (range <= 0) return;
            double delta = deltaY * FACTOR / range;
            double v = scroll.getVvalue() - delta;
            scroll.setVvalue(v < 0 ? 0 : (v > 1 ? 1 : v));
            e.consume();
        });
    }
}