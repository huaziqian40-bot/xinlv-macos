/* 后台任务工具：网络/数据库操作都不能堵 JavaFX 界面线程。
 * Bg.run(任务, 成功回调, 失败回调) —— 回调自动切回界面线程。 */
package com.moodtree.client.ui;

import javafx.application.Platform;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class Bg {

    @FunctionalInterface
    public interface Task<T> { T get() throws Exception; }

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "moodtree-bg");
        t.setDaemon(true);   // 关窗即退出，不挂进程
        return t;
    });

    public static <T> void run(Task<T> task, Consumer<T> onOk, Consumer<Exception> onErr) {
        POOL.submit(() -> {
            try {
                T result = task.get();
                if (onOk != null) Platform.runLater(() -> onOk.accept(result));
            } catch (Exception e) {
                if (onErr != null) Platform.runLater(() -> onErr.accept(e));
            }
        });
    }

    /** 只跑不要结果 */
    public static void run(Task<?> task) {
        run(task, null, e -> e.printStackTrace());
    }
}
