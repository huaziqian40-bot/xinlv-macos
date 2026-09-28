/* phix 服务器候选地址。
 *
 * 用户 2026-09-28 要求：**不要让用户看到服务器地址**。所以界面上不再有输入框，
 * 由程序自己挑一台能连上的，用的是和 PH Launcher / PH Launcher Lite 同一套规则：
 *
 *   1. 环境变量 `PHIX_LAN_SERVER`（部署者自建的内网实例）
 *   2. 程序目录 / 工作目录下的 `.phix-local.json` 里的 `lanServer`
 *   3. 公网入口 `https://phix.ing`
 *
 * 内网地址**绝不写进源码** —— 那等于把内网拓扑发到公开仓库
 * （PHL 那边就是因为这个专门改过一次）。想指定自己的服务器，用上面两种方式之一。
 *
 * 注意：这里的地址是 **origin**（如 `https://phix.ing`），不含 `/api/v1` 前缀；
 * 调用方自己拼路径（见 ApiClient.phixLogin / phixPing）。
 */
package com.moodtree.client.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class PhixServers {

    /** 公网入口（唯一内置地址）。 */
    public static final String PUBLIC_DEFAULT = "https://phix.ing";

    private PhixServers() { }

    /** 读 `.phix-local.json` 里的 lanServer；找不到/格式错就返回空串。 */
    private static String readLocalOverride() {
        List<Path> dirs = new ArrayList<>();
        try { dirs.add(Path.of("").toAbsolutePath()); } catch (Exception ignored) { }
        try {
            // jpackage app-image：<安装目录>/app/… → 往回找两级就是安装目录
            Path self = Path.of(PhixServers.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath();
            for (int i = 0; i < 4 && self != null; i++) {
                dirs.add(self);
                self = self.getParent();
            }
        } catch (Exception ignored) { }
        for (Path dir : dirs) {
            try {
                Path file = dir.resolve(".phix-local.json");
                if (!Files.isReadable(file)) continue;
                String text = Files.readString(file, StandardCharsets.UTF_8);
                // 只为取一个地址，不做完整 JSON 解析：正则足够且不引入依赖
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"lanServer\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
                if (m.find()) {
                    String v = m.group(1).trim();
                    if (!v.isEmpty()) return v;
                }
            } catch (IOException | RuntimeException ignored) { }
        }
        return "";
    }

    /** 首选地址：内网实例（若配置了）优先，否则公网入口。 */
    public static String defaultServer() {
        String env = System.getenv("PHIX_LAN_SERVER");
        if (env != null && !env.trim().isEmpty()) return env.trim();
        String local = readLocalOverride();
        if (!local.isEmpty()) return local;
        return PUBLIC_DEFAULT;
    }

    /** 探测顺序：内网 → 公网；去重、去空、去掉结尾斜杠。 */
    public static List<String> candidates() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : new String[]{ defaultServer(), PUBLIC_DEFAULT }) {
            if (s == null) continue;
            String v = s.trim();
            while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
            if (!v.isEmpty()) out.add(v);
        }
        return new ArrayList<>(out);
    }
}
