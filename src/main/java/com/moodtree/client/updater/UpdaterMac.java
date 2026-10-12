/* 心履 macOS 应用内更新（未签名，自助替换，卡片确认制）。
 *
 * 流程：
 *   1. 启动时后台 GET /api/v1/update/check?product=xinlv&platform=mac，仅拉清单判断是否有新版
 *   2. 有新版则弹「确认卡片」：用户点「更新」才进入下方下载/替换流程，「取消/跳过」不做任何下载
 *   3. 下载 → SHA256 校验 → 用 ditto 解压到 ~/.moodtree/.update-staging/
 *   4. 写一个脱离主进程的 bash 脚本：
 *        等本进程退出 → 旧 .app 移进废纸篓 → 新 .app 就位 →
 *        xattr 清 com.apple.quarantine → codesign ad-hoc 重签 → open 重启
 *   5. 主进程退出，脚本接管
 *
 * **用户只需要在新版本首次启动时右键 →「打开」一次**（未签名应用的 Gatekeeper 限制），
 * 不需要自己下载。自动替换准备失败时才降级为打开官网下载页。
 *
 * 注：dmg 无法直接用于自动替换（要挂载），所以服务端为 macOS 提供的是 .app 的 zip。
 */
package com.moodtree.client.updater;

import com.moodtree.client.Config;
import javafx.application.Platform;

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
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

public class UpdaterMac {

    /** 与 pom.xml <version> 保持同步 */
    public static final String APP_VERSION = "1.1.10";

    private static final String CHECK_URL =
            "https://phix.ing/api/v1/update/check?product=xinlv&platform=mac";
    private static final String DOWNLOAD_PAGE = "https://phix.ing/download/";
    private static final String USER_AGENT = "XinLv-macOS-" + APP_VERSION;

    private UpdaterMac() { }

    /** 后台检查；有新版则弹「确认卡片」，不自动下载。用户点「更新」才下载并准备替换。 */
    public static void checkAsync() {
        if (!CARD_IN_FLIGHT.compareAndSet(false, true)) return; // 已有卡片在弹（如手动检查），别再叠一个
        Thread t = new Thread(() -> {
            try {
                Entry e = check();
                if (e == null) return;   // 只检查，绝不自动下载
                // 跳到 FX 线程弹卡片
                FutureTask<UpdateCard.Choice> card = new FutureTask<>(() ->
                        UpdateCard.showAndWait(e.version, APP_VERSION, e.releaseNotes));
                Platform.runLater(card);
                UpdateCard.Choice c = card.get();
                if (c == UpdateCard.Choice.SKIP) {
                    Config cfg = new Config();
                    cfg.setSkippedUpdateVersion(e.version);
                    cfg.save();
                    return;
                }
                if (c != UpdateCard.Choice.UPDATE) return;   // 取消：不动
                if (stage(e)) {
                    // 替换脚本已接管：给它一点时间写盘，然后退出当前实例
                    Thread.sleep(1500);
                    System.exit(0);
                } else {
                    fallback();
                }
            } catch (Throwable ignored) { }
            finally { CARD_IN_FLIGHT.set(false); }
        }, "xinlv-mac-updater-check");
        t.setDaemon(true);
        t.start();
    }

    private static final class Entry {
        String version;
        String url;
        String sha256;
        String releaseNotes;
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
        e.releaseNotes = jsonString(body, "release_notes");
        if (e.version.isEmpty() || e.version.equals(APP_VERSION)) return null;
        // 用户曾「跳过本版本」：该版本不再提示（更高的新版本仍会提示）
        if (e.version.equals(new Config().skippedUpdateVersion())) return null;
        if (!e.url.endsWith(".zip") || e.sha256.isEmpty()) return null;
        return e;
    }

    // ---------- 「关于软件」手动检查更新 ----------
    //
    // 启动检查把"没有新版本"和"检查失败"都压成 null，静默处理没问题；
    // 但手动检查必须把这两种**相反**的状态分开，否则断网会误显示成
    // 「已是最新版本」（用户最讨厌的经典 bug）。
    // 所以手动检查返回三态：已是最新 / 发现新版本 / 检查失败。

    /** 手动检查更新的三种结果。 */
    public enum UpdateCheckResult { UP_TO_DATE, NEW_VERSION, FAILED }

    // 防止「启动检查」和「手动检查」同时弹卡（叠出两个对话框）
    private static final AtomicBoolean CARD_IN_FLIGHT = new AtomicBoolean(false);

    // 手动检查暂存的待更新信息（供下方卡片/替换流程用，免得把 check() 的静默语义搬过来）
    private static volatile Entry manualEntry;

    /**
     * 纯分类逻辑：根据已拉取的清单字段判断检查结果。
     * 不碰网络（测试也走这里，用桩数据驱动）：
     *   - 拿不到版本号、或缺 .zip 更新包必备字段 → FAILED（坏响应，绝不当"已是最新"）；
     *   - 版本相同、或用户已「跳过本版本」→ UP_TO_DATE；
     *   - 其余 → NEW_VERSION。
     */
    public static UpdateCheckResult classify(String latest, String url, String sha) {
        return classify(latest, url, sha, new Config());
    }

    /** 可注入 Config 的纯分类（带 Config 版本给测试用，避免读真实用户配置）。 */
    public static UpdateCheckResult classify(String latest, String url, String sha, Config cfg) {
        if (latest == null || latest.isEmpty()) return UpdateCheckResult.FAILED;      // 坏响应：没有版本号
        if (url == null || url.isEmpty() || !url.endsWith(".zip")) return UpdateCheckResult.FAILED; // 拿不到 .app 更新包
        if (sha == null || sha.isEmpty()) return UpdateCheckResult.FAILED;
        if (latest.equals(APP_VERSION)) return UpdateCheckResult.UP_TO_DATE;
        if (latest.equals(cfg.skippedUpdateVersion())) return UpdateCheckResult.UP_TO_DATE; // 用户已跳过
        return UpdateCheckResult.NEW_VERSION;
    }

    /** 手动「检查更新」：任何一步不对（断网/超时/非 200/坏响应）都判 FAILED，绝不吞成"已是最新"。 */
    public static UpdateCheckResult manualCheck() {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(6))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(CHECK_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", USER_AGENT)
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return UpdateCheckResult.FAILED;
            String body = resp.body();
            String latest = jsonString(body, "latest_version");
            String url = jsonString(body, "url");
            String sha = jsonString(body, "sha256");
            UpdateCheckResult r = classify(latest, url, sha);
            // 有新版本时暂存下载信息，供下方既有「确认卡片」流程使用
            if (r == UpdateCheckResult.NEW_VERSION) {
                Entry e = new Entry();
                e.version = latest;
                e.url = url;
                e.sha256 = sha;
                e.releaseNotes = jsonString(body, "release_notes");
                manualEntry = e;
            }
            return r;
        } catch (Exception e) {
            return UpdateCheckResult.FAILED; // 断网/超时：明确报「检查失败」，不是"已是最新"
        }
    }

    /** 手动检查暂存的新版本号（用于拼「发现新版本 vX」；无则空串）。 */
    public static String newVersion() {
        Entry e = manualEntry;
        return e == null || e.version == null ? "" : e.version;
    }

    /** 检查结果 → 界面提示文案（纯映射，测试直接测）。 */
    public static String messageFor(UpdateCheckResult r, String newVersion) {
        return switch (r) {
            case UP_TO_DATE -> "已是最新版本";
            case NEW_VERSION -> "发现新版本 v" + (newVersion == null ? "" : newVersion);
            case FAILED -> "检查失败，请稍后再试";
        };
    }

    /**
     * 手动检查发现新版本后，走既有「确认卡片」流程（不重复拉取，直接弹卡）。
     * 在后台线程（Bg）调用；与启动卡片用 CARD_IN_FLIGHT 互斥，绝不叠两个对话框。
     */
    public static void runManualUpdateFlow() {
        if (!CARD_IN_FLIGHT.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                Entry e = manualEntry;
                if (e == null) return;   // 没有待更新信息
                FutureTask<UpdateCard.Choice> card = new FutureTask<>(() ->
                        UpdateCard.showAndWait(e.version, APP_VERSION, e.releaseNotes));
                Platform.runLater(card);
                UpdateCard.Choice c = card.get();
                if (c == UpdateCard.Choice.SKIP) {
                    Config cfg = new Config();
                    cfg.setSkippedUpdateVersion(e.version);
                    cfg.save();
                } else if (c == UpdateCard.Choice.UPDATE) {
                    if (stage(e)) {
                        Thread.sleep(1500);
                        System.exit(0);
                    } else {
                        fallback();
                    }
                }
            } catch (Throwable ignored) { }
            finally { CARD_IN_FLIGHT.set(false); }
        }, "xinlv-mac-updater-manual").start();
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

    /**
     * 简单 JSON 字段提取（项目未引入 JSON 库时用最小实现；值必须是字符串）。
     * 正确处理反斜杠转义（引号、反斜杠、换行、Unicode 十六进制序列），
     * 避免被「更新内容里的引号」提前截断。
     */
    private static String jsonString(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return "";
        i = json.indexOf(':', i + needle.length());
        if (i < 0) return "";
        i++;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return "";
        i++; // 跳过开头的引号
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++;
                if (i >= json.length()) break;
                char esc = json.charAt(i);
                switch (esc) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'u': {
                        if (i + 4 < json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (Exception ignored) { /* 非法转义序列保持原样 */ }
                        }
                        break;
                    }
                    default: sb.append(esc); break;
                }
                i++;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
