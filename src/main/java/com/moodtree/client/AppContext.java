/* 全局应用上下文：配置/API/数据库/同步引擎的持有者，负责登录页与主界面切换、主题应用。 */
package com.moodtree.client;

import com.moodtree.client.api.ApiClient;
import com.moodtree.client.db.LocalDb;
import com.moodtree.client.model.MoodMeta;
import com.moodtree.client.sync.SyncEngine;
import com.moodtree.client.ui.LoginView;
import com.moodtree.client.ui.MainShell;
import com.moodtree.client.ui.Theme;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class AppContext {
    public final Config config = new Config();
    public final ApiClient api = new ApiClient(config);
    public final LocalDb db;
    public final SyncEngine sync;

    private final Stage stage;
    private String lastViewKey = "calendar";   // 主题切换重建界面后回到原页面

    public AppContext(Stage stage) throws Exception {
        this.stage = stage;
        this.db = new LocalDb(config.dbPath());
        this.sync = new SyncEngine(config, api, db);
        // 上次缓存的心情定义（离线兜底是代码里的默认值）
        String cached = db.kvGet("moods_cache");
        if (cached != null) MoodMeta.overrideFromCatalogJson(cached);
        // 启动时应用保存的主题（预设 + 3 色自定义）
        Theme.apply(config.themeId(), config.themeBg(), config.themeCard(), config.accent());
    }

    public boolean loggedIn() { return !config.token().isEmpty(); }

    /** 可以直接进主界面：已登录，或用户选了游客模式 */
    public boolean canEnterMain() { return loggedIn() || config.guestMode(); }

    public void showLogin() {
        stage.setScene(new Scene(new LoginView(this), 480, 560));
        stage.centerOnScreen();
    }

    public void showMain() {
        MainShell shell = new MainShell(this);
        stage.setScene(new Scene(shell, 1080, 700));
        stage.centerOnScreen();
        shell.onShown();   // 触发进入后的首次同步
    }

    /** 游客身份进入（数据只存本机，登录后自动上云） */
    public void enterGuest() {
        config.setGuestMode(true);
        config.save();
        showMain();
    }

    /** 记住当前页面，主题切换重建后跳回来 */
    public void setLastViewKey(String key) { this.lastViewKey = key; }
    public String lastViewKey() { return lastViewKey; }

    /** 换主题：保存配置并重建主界面（登录页下次打开自动生效） */
    public void applyTheme(String themeId, String accentHex) {
        config.setThemeId(themeId);
        config.setAccent(accentHex);
        config.save();
        Theme.apply(themeId, accentHex);
        if (canEnterMain()) showMain();
    }

    /** 换主题（3 色自定义）：保存并重建 */
    public void applyTheme(String themeId, String bgHex, String cardHex, String accentHex) {
        config.setThemeId(themeId);
        config.setThemeBg(bgHex);
        config.setThemeCard(cardHex);
        config.setAccent(accentHex);
        config.save();
        Theme.apply(themeId, bgHex, cardHex, accentHex);
        if (canEnterMain()) showMain();
    }

    /** 退出登录：通知服务端注销令牌（失败也继续），清空本地凭据与游客标记 */
    public void logout() {
        try { api.logout(); } catch (Exception ignored) { }
        config.setToken("");
        config.setUsername("");
        config.setGuestMode(false);
        config.save();
        showLogin();
    }

    public void close() {
        db.close();
    }
}
