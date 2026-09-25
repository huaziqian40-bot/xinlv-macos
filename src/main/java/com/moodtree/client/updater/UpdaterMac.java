/* 心履 macOS 应用内自动更新（未签名，自助替换）。
 *
 * 流程：
 *   1. GET /api/v1/update/check?product=xinlv&platform=mac 拿到新版本 .zip（内含 .app）与 sha256
 *   2. 下载 → SHA256 校验 → 用 ditto 解压到 ~/.moodtree/.update-staging/
 *   3. 写一个脱离主进程的 bash 脚本：
 *        等本进程退出 → 旧 .app 移进废纸篓 → 新 .app 就位 →
 *        xattr 清 com.apple.quarantine → codesign ad-hoc 重签 → open 重启
 *   4. 主进程退出，脚本接管
 *
 * **用户只需要在新版本首次启动时右键 →「打开」一次**（未签名应用的 Gatekeeper 限制），
 * 不需要自己下载。自动替换准备失败时才降级为打开官网下载页。
 *
 * 注：dmg 无法直接用于自动替换（要挂载），所以服务端为 macOS 提供的是 .app 的 zip。
 */
package com.moodtree.client.updater;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class UpdaterMac {

    /** 与 pom.xml <version> 保持同步 */
    public static final String APP_VERSION = "1.1.7";

    private static final String CHECK_URL =
            "https://phix.ing/api/v1/update/check?product=xinlv&platform=mac";
    private static final String DOWNLOAD_PAGE = "https://phix.ing/download/";
    private static final String USER_AGENT = "XinLv-macOS-" + APP_VERSION;

    private UpdaterMac() { }

    /** 后台检查；有新版则自动下载并准备替换。 */
    public static void checkAsync() {
        Thread t = new Thread(() -> {
            try {
                Entry e = check();
                if (e == null) return;
                if (stage(e)) {
                    // 替换脚本已接管：给它一点时间写盘，然后退出当前实例
                    Thread.sleep(1500);
                    System.exit(0);
                } else {
                    fallback();
                }
            } catch (Throwable ignored) { }
        }, "xinlv-mac-auto-updater");
        t.setDaemon(true);
        t.start();
    }

    private static final class Entry {
        String version;
        String url;
        String sha256;
    }

    private static Entry check() throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(CHECK_URL))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", USER_AGENT)
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;
        String body = resp.body();
        Entry e = new Entry();
        e.version = jsonString(body, "latest_version");
        e.url = jsonString(body, "url");
        e.sha256 = jsonString(body, "sha256");
        if (e.version.isEmpty() || e.version.equals(APP_VERSION)) return null;
        if (!e.url.endsWith(".zip") || e.sha256.isEmpty()) return null;
        return e;
    }

    /** 下载 → 校验 → 解压 → 写替换脚本并启动。成功返回 true（调用方应退出）。 */
    private static boolean stage(Entry e) {
        try {
            Path appPath = currentAppBundle();
            if (appPath == null) return false;   // 开发模式（非 .app）不自动替换

            Path stagingBase = Path.of(System.getProperty("user.home"), ".moodtree", ".update-staging");
            Path zip = stagingBase.resolve("update.zip");
            deleteRecursively(stagingBase);
            Files.createDirectories(stagingBase);

            download(e.url, zip);
            if (!sha256(zip).equalsIgnoreCase(e.sha256)) {
                deleteRecursively(stagingBase);
                return false;                     // 坏包：丢弃
            }
            // macOS 自带 ditto：解 zip 保留权限位与符号链接（Java 解压会丢）
            int rc = new ProcessBuilder("/usr/bin/ditto", "-x", "-k", zip.toString(), stagingBase.toString())
                    .inheritIO().start().waitFor();
            if (rc != 0) {
                deleteRecursively(stagingBase);
                return false;
            }
            Files.deleteIfExists(zip);

            Path newApp = findAppBundle(stagingBase);
            if (newApp == null) {
                deleteRecursively(stagingBase);
                return false;
            }
            return writeAndLaunchSwapScript(appPath, newApp, stagingBase, e.version);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前 .app 路径：优先 jpackage 注入的 jpackage.app-path，回退从类加载位置推导。 */
    private static Path currentAppBundle() {
        String injected = System.getProperty("jpackage.app-path");
        if (injected != null && !injected.isEmpty()) {
            Path p = Path.of(injected).toAbsolutePath();
            if (isApp(p)) return p;
        }
        try {
            Path p = Path.of(UpdaterMac.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath();
            for (int i = 0; i < 8 && p != null; i++) {
                if (p.getFileName() != null && p.getFileName().toString().endsWith(".app")) return p;
                p = p.getParent();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static boolean isApp(Path p) {
        return p != null && p.getFileName() != null && p.getFileName().toString().endsWith(".app");
    }

    private static Path findAppBundle(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root, 4)) {
            List<Path> apps = new ArrayList<>();
            walk.filter(UpdaterMac::isApp).forEach(apps::add);
            return apps.isEmpty() ? null : apps.get(0);
        }
    }

    private static boolean writeAndLaunchSwapScript(Path appPath, Path newApp, Path staging, String version) {
        try {
            Path script = staging.resolve("swap.sh");
            Path trashDir = Path.of(System.getProperty("user.home"), ".Trash");
            Path trashTarget = trashDir.resolve(appPath.getFileName() + ".old-" + System.currentTimeMillis());
            String body = "#!/bin/bash\n"
                    + "# 心履 macOS 自动更新替换脚本（生成的）\n"
                    + "set -u\n"
                    + "TARGET=" + shq(appPath.toString()) + "\n"
                    + "NEW=" + shq(newApp.toString()) + "\n"
                    + "STAGING=" + shq(staging.toString()) + "\n"
                    + "TRASH=" + shq(trashTarget.toString()) + "\n"
                    + "PID=" + ProcessHandle.current().pid() + "\n"
                    + "\n"
                    + "for i in $(seq 1 60); do\n"
                    + "  if ! kill -0 \"$PID\" 2>/dev/null; then break; fi\n"
                    + "  sleep 1\n"
                    + "done\n"
                    + "sleep 1\n"
                    + "\n"
                    + "if [ -d \"$TARGET\" ]; then\n"
                    + "  mkdir -p " + shq(trashDir.toString()) + " 2>/dev/null || true\n"
                    + "  mv \"$TARGET\" \"$TRASH\" 2>/dev/null || rm -rf \"$TARGET\"\n"
                    + "fi\n"
                    + "\n"
                    + "/usr/bin/ditto \"$NEW\" \"$TARGET\" || exit 1\n"
                    + "/usr/bin/xattr -dr com.apple.quarantine \"$TARGET\" 2>/dev/null || true\n"
                    + "/usr/bin/codesign --sign - --deep --force \"$TARGET\" 2>/dev/null || true\n"
                    + "/usr/bin/open \"$TARGET\" 2>/dev/null || true\n"
                    + "rm -rf \"$STAGING\" 2>/dev/null || true\n";
            Files.writeString(script, body, StandardCharsets.UTF_8);
            script.toFile().setExecutable(true);

            ProcessBuilder pb = new ProcessBuilder("/bin/bash", script.toString());
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();

            notifyUser("心履正在更新",
                    "退出后将自动替换为 v" + version + "；下次打开请右键 →「打开」一次。");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 降级：自动替换准备失败 → 提示并打开下载页。 */
    private static void fallback() {
        notifyUser("发现新版本 心履", "请前往官网下载更新。");
        try {
            new ProcessBuilder("open", DOWNLOAD_PAGE).start();
        } catch (Throwable ignored) { }
    }

    private static void download(String url, Path out) throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(900))
                .header("User-Agent", USER_AGENT)
                .GET().build();
        http.send(req, HttpResponse.BodyHandlers.ofFile(out));
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static void notifyUser(String title, String body) {
        try {
            ProcessBuilder pb = new ProcessBuilder("osascript", "-e",
                    "display notification \"" + body.replace("\"", "'") + "\" with title \""
                            + title.replace("\"", "'") + "\"");
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
        } catch (Throwable ignored) { }
    }

    private static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
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
}
