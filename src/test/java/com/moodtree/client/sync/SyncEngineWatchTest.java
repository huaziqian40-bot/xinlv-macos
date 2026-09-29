/* 云变更长轮询（/api/v1/sync/watch/）的决策逻辑与循环行为的单元测试。
 * 只测纯逻辑：HTTP 调用用 FakeWatcher 注入桩代替，绝不真连服务器；
 * 「跑一轮同步」用计数钩子代替，不碰数据库/网络。
 * 覆盖：
 *   - changed=true → 标记要同步、更新游标；
 *   - changed=false 且无 retry_after → 立即重挂（delayMs=0），不睡；
 *   - changed=false 且带 retry_after → 按服务端让的秒数等，绝不热循环；
 *   - 抛异常 → 退避重试、循环不死；
 *   - 停止 → 循环及时退出。 */
package com.moodtree.client.sync;

import com.google.gson.JsonObject;
import com.moodtree.client.Config;
import com.moodtree.client.api.ApiClient;
import com.moodtree.client.db.LocalDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncEngineWatchTest {

    private Config config;
    private ApiClient api;
    private LocalDb db;
    private SyncEngine engine;
    private Path tempDir;

    /** 桩 watcher：按脚本喂响应；thrower 置位时抛异常；不给响应时阻塞（测停止/超时用） */
    private static class FakeWatcher implements SyncEngine.WatchCaller {
        final LinkedBlockingQueue<JsonObject> responses = new LinkedBlockingQueue<>();
        final AtomicReference<Exception> thrower = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public JsonObject call(String since) throws Exception {
            calls.incrementAndGet();
            Exception ex = thrower.get();
            if (ex != null) throw ex;            // 模拟"这一次调用失败"
            return responses.take();             // 无响应则阻塞，模拟服务端挂住/断网
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        config = new Config();
        config.setToken("测试令牌");            // 只改内存，不落盘
        api = new ApiClient(config);
        tempDir = Files.createTempDirectory("moodtree-watch-test");
        db = new LocalDb(tempDir.resolve("test.db"));
        engine = new SyncEngine(config, api, db);
    }

    @AfterEach
    void tearDown() throws Exception {
        engine.stopWatch();                     // 兜底收尾，不泄漏后台线程
        db.close();
        // 清理临时目录（失败无所谓，系统也会清）
        try { Files.deleteIfExists(tempDir.resolve("test.db")); } catch (Exception ignored) { }
    }

    private static JsonObject watch(boolean changed, String cursor, Integer retryAfter) {
        JsonObject o = new JsonObject();
        o.addProperty("changed", changed);
        if (cursor != null) o.addProperty("cursor", cursor);
        if (retryAfter != null) o.addProperty("retry_after", retryAfter);
        return o;
    }

    // ---------- 决策逻辑（纯，不起线程） ----------

    @Test
    void changedTrueTriggersSyncAndUpdatesCursor() {
        engine.startWatch();
        try {
            SyncEngine.WatchDecision d = engine.decideWatch(watch(true, "c1", null));
            assertTrue(d.doSync, "changed=true 应触发同步");
            assertEquals(0, d.delayMs, "同步后应立即重挂，不需要退避");
            assertEquals("c1", engine.watchCursor(), "游标应更新为新值");
        } finally {
            engine.stopWatch();
        }
    }

    @Test
    void changedTrueWithoutCursorFallsBackToServerTime() {
        engine.startWatch();
        try {
            JsonObject resp = watch(true, null, null);
            resp.addProperty("server_time", "2026-01-01T00:00:00Z");
            engine.decideWatch(resp);
            assertEquals("2026-01-01T00:00:00Z", engine.watchCursor(),
                    "changed=true 却没给游标时，应用服务端时间兜底当游标");
        } finally {
            engine.stopWatch();
        }
    }

    @Test
    void changedFalseWithoutRetryAfterReissuesImmediately() {
        SyncEngine.WatchDecision d = engine.decideWatch(watch(false, "c2", null));
        assertFalse(d.doSync);
        assertEquals(0, d.delayMs, "普通超时（changed=false 且无 retry_after）应立即重挂");
    }

    @Test
    void changedFalseWithRetryAfterSleepsThatLong() {
        SyncEngine.WatchDecision d = engine.decideWatch(watch(false, "c3", 5));
        assertFalse(d.doSync);
        assertEquals(5000L, d.delayMs, "服务端让等 5 秒，就等 5 秒再重挂，绝不热循环");
    }

    // ---------- 循环行为（起线程，HTTP 用桩） ----------

    @Test
    void loopSyncingOnChange_reissuesAndBacksOffCorrectly() throws Exception {
        FakeWatcher watcher = new FakeWatcher();
        AtomicInteger syncs = new AtomicInteger();
        engine.watchCaller = watcher;
        engine.onWatchChange = syncs::incrementAndGet;
        engine.startWatch();

        // 第一次：changed=true（并带游标）→ 应跑一轮同步、更新游标
        watcher.responses.put(watch(true, "loop-1", null));
        // 第二次到第 N 次：普通超时，立即重挂，直到喂入"照常继续"的响应
        for (int i = 0; i < 5; i++) {
            watcher.responses.put(watch(false, "loop-1", null));
        }
        // 等它跑完前几次（同步至少一次）
        long deadline = System.currentTimeMillis() + 5000;
        while (syncs.get() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(syncs.get() >= 1, "changed=true 应触发至少一轮同步");
        assertEquals("loop-1", engine.watchCursor(), "循环里也应把游标更新掉");
        engine.stopWatch();
    }

    @Test
    void exceptionBacksOffAndLoopKeepsRunning() throws Exception {
        FakeWatcher watcher = new FakeWatcher();
        engine.watchCaller = watcher;
        engine.onWatchChange = () -> { };
        watcher.thrower.set(new RuntimeException("模拟网络异常"));
        engine.startWatch();

        // 第一次调用即抛异常 → 循环退避 3 秒后再挂；只要线程还活着、还会再调用就算"没死"
        long deadline = System.currentTimeMillis() + 6000;
        while (watcher.calls.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(watcher.calls.get() >= 2, "抛异常后应退避重试，循环不能死");
        assertTrue(engine.isWatching(), "异常被吞掉后，watch 仍在运行");
        engine.stopWatch();
    }

    @Test
    void shutdownStopsLoopPromptly() throws Exception {
        FakeWatcher watcher = new FakeWatcher();
        engine.watchCaller = watcher;
        engine.onWatchChange = () -> { };
        engine.startWatch();

        // 不给任何响应 → 循环阻塞在挂起的第一次调用里（模拟服务端长挂）
        long deadline = System.currentTimeMillis() + 5000;
        while (watcher.calls.get() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(watcher.calls.get() >= 1, "循环应已进入挂起的调用");
        long start = System.currentTimeMillis();
        engine.stopWatch();
        long elapsed = System.currentTimeMillis() - start;
        assertFalse(engine.isWatching(), "停止后 watch 应立即退出");
        assertTrue(elapsed < 3000, "停止应当在约 2 秒内完成，got " + elapsed + "ms");
        // 再确认真停了：立刻 restart 不应报错
        engine.startWatch();
        assertTrue(engine.isWatching());
        engine.stopWatch();
    }

    /** startWatch 在未登录（无令牌）时应直接跳过，不起线程 */
    @Test
    void startWatchSkipsWhenNotLoggedIn() {
        config.setToken("");
        engine.startWatch();
        assertFalse(engine.isWatching(), "没有令牌就不该起长轮询（不拿无凭据请求骚扰服务器）");
    }

    /** 老服务端没有 /watch 时返回 404 → 循环只退避重试，不抛出、不打断正常同步 */
    @Test
    void notFoundIsSwallowedAndBackedOff() throws Exception {
        FakeWatcher watcher = new FakeWatcher();
        engine.watchCaller = watcher;
        engine.onWatchChange = () -> { };
        // 第一次就抛 404（老服务端没这个端点）
        watcher.thrower.set(new ApiClient.ApiException(404, "老服务端没有 /watch"));
        engine.startWatch();

        long deadline = System.currentTimeMillis() + 6000;
        while (watcher.calls.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(watcher.calls.get() >= 2, "404 也应退避重试，而不是退出或抛出");
        assertTrue(engine.isWatching());
        engine.stopWatch();
    }
}
