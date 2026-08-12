/* 本地 SQLite 数据库：离线存储心情记录、键值对（令牌之外的会话状态）、推荐目录缓存。
 * 同步规则与服务端对齐：uuid 主键去重、updated_at 最新者赢、deleted 墓碑不真删。
 * 单连接够用（JavaFX 应用单用户），所有方法加 synchronized 防 UI 线程与同步线程打架。 */
package com.moodtree.client.db;

import com.moodtree.client.model.MoodEntry;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class LocalDb implements AutoCloseable {

    private final Connection conn;

    public LocalDb(Path dbFile) throws SQLException {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS mood_entry(
                          uuid TEXT PRIMARY KEY,
                          date TEXT NOT NULL,
                          at TEXT,
                          mood TEXT NOT NULL,
                          note TEXT DEFAULT '',
                          deleted INTEGER DEFAULT 0,
                          updated_at TEXT NOT NULL,
                          dirty INTEGER DEFAULT 0,
                          intensity_level INTEGER DEFAULT 0,
                          intensity_percent INTEGER DEFAULT 0
                        )""");
                st.execute("CREATE INDEX IF NOT EXISTS idx_entry_date ON mood_entry(date)");
                // 迁移：旧库没有 intensity 列，ALTER TABLE 加回来（SQLite 不支持 IF NOT EXISTS）
                try { st.execute("ALTER TABLE mood_entry ADD COLUMN intensity_level INTEGER DEFAULT 0"); } catch (SQLException ignored) { }
                try { st.execute("ALTER TABLE mood_entry ADD COLUMN intensity_percent INTEGER DEFAULT 0"); } catch (SQLException ignored) { }
                st.execute("""
                        CREATE TABLE IF NOT EXISTS kv(
                          key TEXT PRIMARY KEY,
                          value TEXT
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS catalog(
                          kind TEXT NOT NULL,
                          id INTEGER NOT NULL,
                          payload TEXT NOT NULL,
                          PRIMARY KEY(kind, id)
                        )""");
            }
        } catch (SQLException e) {
            throw new SQLException("本地数据库打不开：" + dbFile + "（" + e.getMessage() + "）", e);
        }
    }

    // ---------- 心情记录 ----------

    /** 本地新建/修改/删除后落库（dirty=1 等下次同步上传） */
    public synchronized void saveLocal(MoodEntry e) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO mood_entry(uuid,date,at,mood,note,deleted,updated_at,dirty,intensity_level,intensity_percent)
                VALUES(?,?,?,?,?,?,?,1,?,?)
                ON CONFLICT(uuid) DO UPDATE SET
                  date=excluded.date, at=excluded.at, mood=excluded.mood, note=excluded.note,
                  deleted=excluded.deleted, updated_at=excluded.updated_at, dirty=1,
                  intensity_level=excluded.intensity_level, intensity_percent=excluded.intensity_percent
                """)) {
            fill(ps, e);
            ps.executeUpdate();
        }
    }

    /** 服务端拉下来的记录入库。本地有更新的（含未上传的脏数据）则跳过，保持本地优先 */
    public synchronized boolean saveFromServer(MoodEntry e) throws SQLException {
        MoodEntry local = get(e.uuid);
        if (local != null && !local.updatedAt.isBefore(e.updatedAt)) {
            return false;   // 本地更新或相同，不覆盖（本地脏数据等推送时由服务端 LWW 裁决）
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO mood_entry(uuid,date,at,mood,note,deleted,updated_at,dirty,intensity_level,intensity_percent)
                VALUES(?,?,?,?,?,?,?,0,?,?)
                ON CONFLICT(uuid) DO UPDATE SET
                  date=excluded.date, at=excluded.at, mood=excluded.mood, note=excluded.note,
                  deleted=excluded.deleted, updated_at=excluded.updated_at, dirty=0,
                  intensity_level=excluded.intensity_level, intensity_percent=excluded.intensity_percent
                """)) {
            fill(ps, e);
            ps.executeUpdate();
        }
        return true;
    }

    private void fill(PreparedStatement ps, MoodEntry e) throws SQLException {
        ps.setString(1, e.uuid);
        ps.setString(2, e.date.toString());
        ps.setString(3, e.at == null ? null : e.at.toString());
        ps.setString(4, e.mood);
        ps.setString(5, e.note);
        ps.setInt(6, e.deleted ? 1 : 0);
        ps.setString(7, e.updatedAt.toString());
        ps.setInt(8, e.intensityLevel);
        ps.setInt(9, e.intensityPercent);
    }

    private MoodEntry row(ResultSet rs) throws SQLException {
        MoodEntry e = new MoodEntry();
        e.uuid = rs.getString("uuid");
        e.date = LocalDate.parse(rs.getString("date"));
        String at = rs.getString("at");
        e.at = at == null ? null : OffsetDateTime.parse(at);
        e.mood = rs.getString("mood");
        e.note = rs.getString("note");
        e.deleted = rs.getInt("deleted") == 1;
        e.updatedAt = OffsetDateTime.parse(rs.getString("updated_at"));
        e.dirty = rs.getInt("dirty") == 1;
        e.intensityLevel = rs.getInt("intensity_level");
        e.intensityPercent = rs.getInt("intensity_percent");
        return e;
    }

    public synchronized MoodEntry get(String uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM mood_entry WHERE uuid=?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? row(rs) : null;
            }
        }
    }

    /** 某天的记录（不含墓碑），按时刻升序 */
    public synchronized List<MoodEntry> listForDate(LocalDate date) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM mood_entry WHERE date=? AND deleted=0 ORDER BY at")) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<MoodEntry> list = new ArrayList<>();
                while (rs.next()) list.add(row(rs));
                return list;
            }
        }
    }

    /** 某月的记录（不含墓碑），日历渲染用 */
    public synchronized List<MoodEntry> listForMonth(String yearMonth) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM mood_entry WHERE date LIKE ? AND deleted=0 ORDER BY date, at")) {
            ps.setString(1, yearMonth + "-%");
            try (ResultSet rs = ps.executeQuery()) {
                List<MoodEntry> list = new ArrayList<>();
                while (rs.next()) list.add(row(rs));
                return list;
            }
        }
    }

    /** 待上传的脏记录 */
    public synchronized List<MoodEntry> listDirty() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM mood_entry WHERE dirty=1")) {
            List<MoodEntry> list = new ArrayList<>();
            while (rs.next()) list.add(row(rs));
            return list;
        }
    }

    /** 上传成功后去掉脏标记 */
    public synchronized void markClean(Set<String> uuids) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE mood_entry SET dirty=0 WHERE uuid=?")) {
            for (String u : uuids) {
                ps.setString(1, u);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public synchronized int countAlive() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM mood_entry WHERE deleted=0")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** 有记录的日期（去重，新→旧），游客模式本地算连胜用 */
    public synchronized List<LocalDate> listDistinctDates() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT DISTINCT date FROM mood_entry WHERE deleted=0 ORDER BY date DESC")) {
            List<LocalDate> list = new ArrayList<>();
            while (rs.next()) list.add(LocalDate.parse(rs.getString(1)));
            return list;
        }
    }

    /** 获取最新一条心情记录（非删除），用于情绪视觉叠色 */
    public synchronized MoodEntry getLatest() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT * FROM mood_entry WHERE deleted=0 ORDER BY date DESC, at DESC LIMIT 1")) {
            return rs.next() ? row(rs) : null;
        }
    }

    // ---------- 键值对（last_sync 等） ----------

    public synchronized String kvGet(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM kv WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public synchronized void kvSet(String key, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO kv(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    // ---------- 推荐目录缓存 ----------

    public synchronized void catalogPut(String kind, int id, String payloadJson) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO catalog(kind,id,payload) VALUES(?,?,?) " +
                "ON CONFLICT(kind,id) DO UPDATE SET payload=excluded.payload")) {
            ps.setString(1, kind);
            ps.setInt(2, id);
            ps.setString(3, payloadJson);
            ps.executeUpdate();
        }
    }

    /** 清空某类目录后整体重写（catalog 是全量覆盖式缓存） */
    public synchronized void catalogClear(String kind) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM catalog WHERE kind=?")) {
            ps.setString(1, kind);
            ps.executeUpdate();
        }
    }

    public synchronized List<String> catalogAll(String kind) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT payload FROM catalog WHERE kind=?")) {
            ps.setString(1, kind);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> list = new ArrayList<>();
                while (rs.next()) list.add(rs.getString(1));
                return list;
            }
        }
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}
