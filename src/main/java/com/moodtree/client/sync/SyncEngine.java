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
}
