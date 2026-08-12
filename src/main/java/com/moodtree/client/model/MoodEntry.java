/* 心情记录（客户端模型）。与服务端 /api/v1/ 的字段一一对应：
 * uuid 客户端生成、updated_at 最新者赢、deleted 为墓碑软删。
 * dirty 是本地字段：1 表示还没同步到服务器。 */
package com.moodtree.client.model;

import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public class MoodEntry {
    public String uuid;
    public LocalDate date;
    public OffsetDateTime at;          // 记录的精确时刻
    public String mood;                // happy/sad/... 与服务器 MOOD_KEYS 一致
    public String note = "";
    public boolean deleted;            // 墓碑
    public OffsetDateTime updatedAt;   // LWW 比较依据
    public boolean dirty;              // 本地待上传
    public int intensityLevel;         // 1-4（略微/有点/相当/十分），0=未设置
    public int intensityPercent;       // 0-100

    /** 新建一条本地记录（离线也可用） */
    public static MoodEntry create(LocalDate date, String mood, String note) {
        return create(date, mood, note, 2, 50);
    }

    /** 新建一条本地记录，含情绪强度 */
    public static MoodEntry create(LocalDate date, String mood, String note,
                                   int intensityLevel, int intensityPercent) {
        MoodEntry e = new MoodEntry();
        e.uuid = UUID.randomUUID().toString();
        e.date = date;
        e.at = OffsetDateTime.now();
        e.mood = mood;
        e.note = note == null ? "" : note.trim();
        e.updatedAt = OffsetDateTime.now();
        e.dirty = true;
        e.intensityLevel = intensityLevel;
        e.intensityPercent = intensityPercent;
        return e;
    }

    /** 本地修改内容（更新/删除墓碑前调用，刷新胜利时间戳并标记待传） */
    public void touchLocal() {
        this.updatedAt = OffsetDateTime.now();
        this.dirty = true;
    }

    /** 序列化成 sync/push 接受的样子 */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid);
        o.addProperty("date", date.toString());
        if (at != null) o.addProperty("at", at.toString());
        o.addProperty("mood", mood);
        o.addProperty("note", note);
        o.addProperty("deleted", deleted);
        o.addProperty("updated_at", updatedAt.toString());
        o.addProperty("intensity_level", intensityLevel);
        o.addProperty("intensity_percent", intensityPercent);
        return o;
    }

    /** 从 sync/pull 的条目还原 */
    public static MoodEntry fromJson(JsonObject o) {
        MoodEntry e = new MoodEntry();
        e.uuid = o.get("uuid").getAsString();
        e.date = LocalDate.parse(o.get("date").getAsString());
        e.at = o.has("at") && !o.get("at").isJsonNull()
                ? OffsetDateTime.parse(o.get("at").getAsString()) : null;
        e.mood = o.get("mood").getAsString();
        e.note = o.has("note") && !o.get("note").isJsonNull() ? o.get("note").getAsString() : "";
        e.deleted = o.has("deleted") && o.get("deleted").getAsBoolean();
        e.updatedAt = OffsetDateTime.parse(o.get("updated_at").getAsString());
        e.dirty = false;
        e.intensityLevel = o.has("intensity_level") ? o.get("intensity_level").getAsInt() : 0;
        e.intensityPercent = o.has("intensity_percent") ? o.get("intensity_percent").getAsInt() : 0;
        return e;
    }
}
