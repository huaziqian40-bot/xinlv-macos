/* 心履 macOS 应用内自动更新（未签名，半自动替换）。
 *
 * 启动时后台检查 https://phix.ing/api/v1/update/check?product=xinlv&platform=mac。
 * 有新版本则：
 *   1. 下载 dmg 说明不可行（未签名自动替换需要 .app 包）——因此检查返回的载荷
 *      是 dmg（官网手动下载用）。对未签名 macOS，**不做自动替换**（Gatekeeper
 *      会拦），而是发系统通知提示用户去官网下载页手动更新 —— 这是唯一可靠路径。
 *
 * 说明：macOS 未签名应用无法像 Windows 那样"应用内替换自身"后无感重启：
 *   - 替换后的 .app 会带 quarantine，首次启动被 Gatekeeper 拦
 *   - ad-hoc 签名可以绕过部分校验，但每次构建 cdhash 变化，且需用户放行一次
 *   - 所以务实做法 = 检测到新版 → 通知 → 打开官网下载页
 * 等将来有 Apple Developer ID 签名后再升级为静默替换。
 */
package com.moodtree.client.updater;

import java.awt.Desktop;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class UpdaterMac {

    /** 与 pom.xml <version> 保持同步 */
    public static final String APP_VERSION = "1.1.5";

    private static final String CHECK_URL =
            "https://phix.ing/api/v1/update/check?product=xinlv&platform=mac";
    private static final String DOWNLOAD_URL = "https://phix.ing/download/";

    private UpdaterMac() { }

    /** 后台检查；有新版则发通知并提示打开下载页。 */
    public static void checkAsync() {
        Thread t = new Thread(() -> {
            try {
                if (!hasUpdate()) return;
                notifyUser();
            } catch (Exception ignored) { }
        }, "xinlv-mac-auto-updater");
        t.setDaemon(true);
        t.start();
    }

    private static boolean hasUpdate() throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(CHECK_URL))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "XinLv-macOS-" + APP_VERSION)
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return false;
        String body = resp.body();
        String latest = jsonString(body, "latest_version");
        return !latest.isEmpty() && !latest.equals(APP_VERSION);
    }

    private static String jsonString(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return "";
        i = json.indexOf(':', i + key.length() + 2);
        if (i < 0) return "";
        i = json.indexOf('"', i);
        if (i < 0) return "";
        int j = json.indexOf('"', i + 1);
        if (j < 0) return "";
        return json.substring(i + 1, j);
    }

    private static void notifyUser() {
        // 用 osascript 发系统通知（未签名环境无需额外权限）
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "osascript", "-e",
                    "display notification \"心履有新版本，请前往官网下载更新。\" with title \"心履\"");
            pb.redirectErrorStream(true);
            pb.start();
        } catch (Exception ignored) { }
        // 顺带尝试打开下载页（Java Desktop 在 mac 上可工作）
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(DOWNLOAD_URL));
            }
        } catch (Exception ignored) { }
    }
}
