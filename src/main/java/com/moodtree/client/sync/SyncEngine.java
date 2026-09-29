/* 同步引擎：把本地脏记录推上去（sync/push），再把服务器的新变化拉下来（sync/pull）。
 * 规则与服务端完全一致：uuid 去重、updated_at 最新者赢、墓碑软删。
 * 失败不抛异常——返回 SyncResult，离线时静默跳过，等下次联网再试。
 * UI 层在后台线程调用 sync()。 */
package com.moodtree.client.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moodtree.client.Config;
import com.moodtree.client.api.ApiClient;
import com.moodtree.client.db.LocalDb;
import com.moodtree.client.model.MoodEntry;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SyncEngine {

    // ---------- 云端变更长轮询（watch）相关常量 ----------

    /** 服务端 /api/v1/sync/watch/ 最多挂约这么久（秒），之后回 changed=false */
    public static final int WATCH_HOLD_SECONDS = 25;

    /** 客户端读超时（秒）。**必须比服务端挂起时间长**：服务端挂 25 秒、客户端给 35 秒，
     *  否则会在服务端刚要返回时被本地掐断，出现一连串无意义的失败重连。 */
    public static final int WATCH_TIMEOUT_SECONDS = 35;

    /** 长轮询出错/断线后的退避重试间隔（毫秒）。正常超时（changed=false 且无 retry_after）
     *  不睡、立刻重挂；只有出错、或服务端让等（retry_after）才走这个退避，绝不热循环。 */
    public static final int WATCH_RETRY_MS = 3000;

    public static class SyncResult {
        public int pushed, pulled, failed;
        public String error;          // null = 成功（或离线跳过）
        public boolean offline;       // 连不上服务器

        public static SyncResult error(String msg, boolean offline) {
            SyncResult r = new SyncResult();
            r.error = msg;
            r.offline = offline;
            return r;
        }

        public String summary() {
            if (error != null) return error;
            return "已同步（上传 " + pushed + " 条，下载 " + pulled + " 条）";
        }
    }

    private final Config config;
    private final ApiClient api;
    private final LocalDb db;

    public SyncEngine(Config config, ApiClient api, LocalDb db) {
        this.config = config;
        this.api = api;
        this.db = db;
    }

    // ---- 云端变更长轮询的运行时状态（排障用） ----

    /** watch 是否正在跑（daemon 后台线程，见 startWatch） */
    private volatile boolean watchRunning;
    /** 上次 watch 返回的不透明游标：客户端只把它原样带回下次请求，不解语义 */
    private volatile String watchCursor = "";
    /** 最近一次 watch 真正返回 changed=true 的时刻（毫秒时间戳；0 = 从没命中过） */
    private volatile long lastWatchHitAt;
    private volatile Thread watchThread;
    private final Object watchLock = new Object();

    /** 测试钩子：watch 的实际 HTTP 调用可注入，默认走 api.watchEntries（见 apiWatch） */
    interface WatchCaller { JsonObject call(String since) throws Exception; }
    volatile WatchCaller watchCaller = this::apiWatch;

    /** 测试钩子：watch 到变化后要跑的"一轮完整同步"，默认 this::sync，可注入观察 */
    interface WatchChangeTrigger { void run(); }
    volatile WatchChangeTrigger onWatchChange = this::sync;

    /** 执行一轮完整同步：先推后拉。未登录直接跳过。 */
    public SyncResult sync() {
        if (config.token().isEmpty()) {
            return SyncResult.error("未登录", false);
        }
        try {
            return doSync();
        } catch (ApiClient.ApiException e) {
            if (e.status == 401) {
                return SyncResult.error("登录已过期，请重新登录", false);
            }
            return SyncResult.error(e.getMessage(), e.isOffline());
        } catch (SQLException e) {
            return SyncResult.error("本地数据库出错：" + e.getMessage(), false);
        }
    }

    private SyncResult doSync() throws ApiClient.ApiException, SQLException {
        SyncResult r = new SyncResult();

        // ---- 推：本地脏记录 → 服务器 ----
        List<MoodEntry> dirty = db.listDirty();
        if (!dirty.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (MoodEntry e : dirty) arr.add(e.toJson());
            JsonObject payload = new JsonObject();
            payload.add("entries", arr);
            JsonObject resp = api.pushEntries(payload);

            // 服务端按 uuid 报错的条目不能去掉脏标记，其余全部标干净
            Set<String> bad = new HashSet<>();
            if (resp.has("errors")) {
                for (JsonElement el : resp.getAsJsonArray("errors")) {
                    JsonObject eo = el.getAsJsonObject();
                    if (eo.has("uuid")) bad.add(eo.get("uuid").getAsString());
                }
            }
            Set<String> ok = new HashSet<>();
            for (MoodEntry e : dirty) {
                if (!bad.contains(e.uuid)) ok.add(e.uuid);
            }
            db.markClean(ok);
            r.pushed = ok.size();
            r.failed = bad.size();
        }

        // ---- 拉：服务器变化 → 本地 ----
        String since = db.kvGet("last_sync");
        JsonObject resp = api.pullEntries(since);
        for (JsonElement el : resp.getAsJsonArray("entries")) {
            MoodEntry e = MoodEntry.fromJson(el.getAsJsonObject());
            if (db.saveFromServer(e)) r.pulled++;
        }
        // 保存服务端时间作为下次增量起点（服务端权威时钟，避免本机时间不准）
        if (resp.has("server_time")) {
            db.kvSet("last_sync", resp.get("server_time").getAsString());
        }
        return r;
    }

    /** 刷新推荐目录缓存（登录后或用户手动刷新时调用；离线静默失败） */
    public boolean refreshCatalog() {
        try {
            JsonObject cat = api.catalog();
            for (String kind : new String[]{"songs", "activities", "tips", "videos"}) {
                if (!cat.has(kind)) continue;
                db.catalogClear(kind);
                for (JsonElement el : cat.getAsJsonArray(kind)) {
                    JsonObject o = el.getAsJsonObject();
                    db.catalogPut(kind, o.get("id").getAsInt(), o.toString());
                }
            }
            // 心情定义也缓存下来，客户端离线兜底定义可被服务端覆盖
            if (cat.has("moods")) {
                db.kvSet("moods_cache", cat.get("moods").toString());
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 云端变更长轮询（watch） ====================
    //
    // 目的：手机/网页端记了一条心情后，桌面端不用等下一次手动同步，几个 RTT 内就能看到。
    // 做法：一条 daemon 后台线程对着 /api/v1/sync/watch/ 长轮询。服务端挂住请求，
    // 数据一变或 25 秒没变就回一次；客户端拿到 changed=true 立刻跑一轮正常同步，
    // 否则继续挂。原有的 60 秒定时同步 + 手动同步原样保留，作为兜底。

    /** 启动长轮询。须已登录、有令牌；未登录直接跳过（不拿游客身份去骚扰服务器）。
     *  幂等：已经跑着就什么都不做。 */
    public void startWatch() {
        if (config.token().isEmpty()) return;
        synchronized (watchLock) {
            watchRunning = true;
            if (watchThread != null && watchThread.isAlive()) return;   // 已在跑
            // 开局游标：用上次拉取游标（与 pull 的 since 同源，都是 ISO8601），
            // 这样从上次同步的位置继续挂，不至于漏掉中间的变化。
            if (watchCursor.isEmpty()) {
                try { String s = db.kvGet("last_sync"); watchCursor = s == null ? "" : s; }
                catch (SQLException e) { watchCursor = ""; }
            }
            watchThread = new Thread(this::watchLoop, "moodtree-sync-watch");
            watchThread.setDaemon(true);   // 关窗即退出，不挂进程
            watchThread.start();
        }
    }

    /** 停止长轮询：置标记 + 中断在途的挂起请求，等线程收尾，避免泄漏。 */
    public void stopWatch() {
        Thread t;
        synchronized (watchLock) {
            watchRunning = false;
            t = watchThread;
            watchThread = null;
        }
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();                      // HttpClient 阻塞在 send 时会被中断，挂起请求随即中止
            try { t.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    /** watch 是否正在跑（排障用，等价于 phix 的 watching） */
    public boolean isWatching() { return watchRunning; }

    /** 当前 watch 游标 / 最近一次真正命中变化的时刻（毫秒时间戳，0=从没命中） */
    public String watchCursor() { return watchCursor; }
    public long lastWatchHitAt() { return lastWatchHitAt; }

    /** watch 状态快照：watching / watch_cursor / last_watch_hit_at，供界面排障展示 */
    public java.util.Map<String, Object> watchStatus() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("watching", watchRunning);
        m.put("watch_cursor", watchCursor);
        m.put("last_watch_hit_at", lastWatchHitAt == 0 ? "" : java.time.Instant.ofEpochMilli(lastWatchHitAt).toString());
        return m;
    }

    /** 正常实现：一排 watch 请求。老服务端没有该端点时返回 404；调用方按'停用长轮询、
     *  退回原定时同步'处理，不算错误。 */
    private JsonObject apiWatch(String since) throws Exception {
        return api.watchEntries(since);
    }

    private void watchLoop() {
        while (isWatching()) {
            try {
                JsonObject resp = watchCaller.call(watchCursor);
                if (!isWatching()) break;
                WatchDecision d = decideWatch(resp);
                if (d.doSync) {
                    lastWatchHitAt = System.currentTimeMillis();
                    onWatchChange.run();        // 有变化：立刻跑一轮完整同步（推+拉）
                }
                if (!isWatching()) break;
                if (d.delayMs > 0) sleep(d.delayMs);   // 服务端让等（retry_after）
            } catch (Exception e) {
                // 出错/超时/404（老服务端）：一律退避重试、绝不抛出，绝不打断正常同步路径
                if (!isWatching()) break;
                sleep(WATCH_RETRY_MS);
            }
        }
    }

    /** watch 一次响应的决策结果：要不要同步、以及下一次重挂前要等多长时间（毫秒）。 */
    static class WatchDecision {
        final boolean doSync;
        final long delayMs;

        WatchDecision(boolean doSync, long delayMs) {
            this.doSync = doSync;
            this.delayMs = delayMs;
        }
    }

    /** 纯决策逻辑（不碰网络，可单测）：
     *   - changed=true → 同步；并更新游标，随后立即重挂（delayMs=0）；
     *   - changed=false 且无 retry_after → 普通超时：立即重挂（delayMs=0），
     *     这是长轮询的正常链条，不是热循环；
     *   - changed=false 且带 retry_after → 服务端忙：等它让的秒数再重挂，绝不热循环。 */
    WatchDecision decideWatch(JsonObject resp) {
        boolean changed = resp.has("changed") && !resp.get("changed").isJsonNull()
                && resp.get("changed").getAsBoolean();
        if (resp.has("cursor") && !resp.get("cursor").isJsonNull()) {
            String c = resp.get("cursor").getAsString();
            if (!c.isEmpty()) watchCursor = c;
        } else if (changed && resp.has("server_time") && !resp.get("server_time").isJsonNull()) {
            // changed=true 却没给新游标：用服务端时间兜底当游标，别从上次的位置重新挂
            watchCursor = resp.get("server_time").getAsString();
        }
        if (changed) {
            return new WatchDecision(true, 0);
        }
        if (resp.has("retry_after") && !resp.get("retry_after").isJsonNull()) {
            int secs = resp.get("retry_after").getAsInt();
            return new WatchDecision(false, secs > 0 ? secs * 1000L : 0);
        }
        return new WatchDecision(false, 0);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
