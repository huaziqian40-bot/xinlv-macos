/* 心情定义（与服务端 core/models.py 的 MOODS 完全一致，离线时以这里为准；
 * 目录刷新时如服务端有更新会覆盖——见 MoodMeta.loadFromCatalog）。 */
package com.moodtree.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MoodMeta {
    public final String key, label, emoji, color, image;
    public final int valence;

    public MoodMeta(String key, String label, String emoji, String color, int valence, String image) {
        this.key = key;
        this.label = label;
        this.emoji = emoji;
        this.color = color;
        this.valence = valence;
        this.image = image;
    }

    private static final Map<String, MoodMeta> BY_KEY = new LinkedHashMap<>();

    static {
        // 兜底默认值：与服务端 MOODS 逐条对应（image 为表情 PNG 静态路径，完整 URL 由调用方拼 serverBase）
        add(new MoodMeta("happy",    "开心", "😄", "#FFD56B",  1, "images/mood_happy.png"));
        add(new MoodMeta("calm",     "平静", "🙂", "#9BD1C6",  1, "images/mood_calm.png"));
        add(new MoodMeta("excited",  "兴奋", "🤩", "#FF9F68",  1, "images/mood_excited.png"));
        add(new MoodMeta("grateful", "感恩", "🥰", "#F7A6C4",  1, "images/mood_grateful.png"));
        add(new MoodMeta("tired",    "疲惫", "😪", "#A6A6C9", -1, "images/mood_tired.png"));
        add(new MoodMeta("anxious",  "焦虑", "😟", "#7FA6E8", -1, "images/mood_anxious.png"));
        add(new MoodMeta("sad",      "难过", "😢", "#6D8FB8", -1, "images/mood_sad.png"));
        add(new MoodMeta("angry",    "愤怒", "😠", "#E8736B", -1, "images/mood_angry.png"));
        add(new MoodMeta("lonely",   "孤独", "🌧️", "#8E94B8", -1, "images/mood_lonely.png"));
        add(new MoodMeta("numb",     "麻木", "😶", "#B0B0B0",  0, "images/mood_numb.png"));
    }

    private static void add(MoodMeta m) { BY_KEY.put(m.key, m); }

    public static List<MoodMeta> all() { return new ArrayList<>(BY_KEY.values()); }

    public static MoodMeta of(String key) {
        MoodMeta m = BY_KEY.get(key);
        return m != null ? m : new MoodMeta(key, key, "❔", "#B0B0B0", 0, "images/mood_numb.png");
    }

    /** 用 /api/v1/catalog 里的 moods 覆盖本地定义（服务端是权威） */
    public static void overrideFromCatalogJson(String moodsJson) {
        try {
            JsonArray arr = JsonParser.parseString(moodsJson).getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                String key = o.get("key").getAsString();
                String image = o.has("image") && !o.get("image").isJsonNull()
                        ? o.get("image").getAsString() : defaultImage(key);
                BY_KEY.put(key, new MoodMeta(key,
                        o.get("label").getAsString(),
                        o.get("emoji").getAsString(),
                        o.get("color").getAsString(),
                        o.get("valence").getAsInt(),
                        image));
            }
        } catch (Exception ignored) {
            // 缓存坏了就用兜底定义
        }
    }

    /** 目录没带 image 时回退本地默认表路径，避免图片缺失 */
    private static String defaultImage(String key) {
        for (MoodMeta m : BY_KEY.values()) if (m.key.equals(key)) return m.image;
        return "images/mood_numb.png";
    }

    /** 序列化成 JSON 方便存 kv */
    public static String toJson() {
        JsonArray arr = new JsonArray();
        for (MoodMeta m : BY_KEY.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("key", m.key);
            o.addProperty("label", m.label);
            o.addProperty("emoji", m.emoji);
            o.addProperty("color", m.color);
            o.addProperty("valence", m.valence);
            o.addProperty("image", m.image);
            arr.add(o);
        }
        return arr.toString();
    }
}
