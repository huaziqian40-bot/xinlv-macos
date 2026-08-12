/* 客户端本地配置：服务器地址、登录令牌、设备名。
 * 存在 ~/Library/Application Support/com.moodtree.app/config.properties
 * 遵循 macOS 数据目录规范。 */
package com.moodtree.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class Config {

    /** 默认服务器（Cloudflare Tunnel 域名），用户可在设置里改 */
    public static final String DEFAULT_SERVER = "https://xin-lv.com";

    private final Path dir;
    private final Path file;
    private final Properties props = new Properties();

    public Config() {
        // 测试可用环境变量 MOODTREE_HOME 指定数据目录，避免污染真实用户数据
        String override = System.getenv("MOODTREE_HOME");
        dir = override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"),
                          "Library", "Application Support", "com.moodtree.app");
        file = dir.resolve("config.properties");
        try {
            Files.createDirectories(dir);
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
            }
        } catch (IOException ignored) {
            // 读不到就当全新配置，不挡启动
        }
    }

    public String serverBase() {
        String s = props.getProperty("serverBase", DEFAULT_SERVER).trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public void setServerBase(String s) { props.setProperty("serverBase", s.trim()); }

    public String token() { return props.getProperty("token", ""); }
    public void setToken(String t) { props.setProperty("token", t); }

    public String username() { return props.getProperty("username", ""); }
    public void setUsername(String u) { props.setProperty("username", u); }

    /** 游客模式：不登录也能用，数据只在本机；登录后游客期间的记录会随同步上云 */
    public boolean guestMode() { return "1".equals(props.getProperty("guestMode", "")); }
    public void setGuestMode(boolean g) { props.setProperty("guestMode", g ? "1" : ""); }

    /** 主题预设 id（warm/night/mint/sakura）与自定义强调色（空 = 用主题默认） */
    public String themeId() { return props.getProperty("themeId", "warm"); }
    public void setThemeId(String id) { props.setProperty("themeId", id); }

    public String accent() { return props.getProperty("accent", ""); }
    public void setAccent(String hex) { props.setProperty("accent", hex == null ? "" : hex); }

    /** 自定义背景色（空 = 用主题预设） */
    public String themeBg() { return props.getProperty("themeBg", ""); }
    public void setThemeBg(String hex) { props.setProperty("themeBg", hex == null ? "" : hex); }

    /** 自定义卡片色（空 = 用主题预设） */
    public String themeCard() { return props.getProperty("themeCard", ""); }
    public void setThemeCard(String hex) { props.setProperty("themeCard", hex == null ? "" : hex); }

    /** 设备备注：登录时上报给服务端，方便用户在多台设备间区分令牌 */
    public String device() {
        String d = props.getProperty("device", "").trim();
        if (!d.isEmpty()) return d;
        try {
            d = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            d = "mac";
        }
        return d + " (macOS)";
    }

    public Path dataDir() { return dir; }
    public Path dbPath() { return dir.resolve("moodtree.db"); }

    public void save() {
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "moodtree client config");
        } catch (IOException ignored) {
        }
    }
}