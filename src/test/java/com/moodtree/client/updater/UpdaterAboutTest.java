/* 「关于软件」手动检查更新（UpdaterMac）的纯逻辑单元测试。
 * 与 SyncEngineWatchTest 同风格：只测纯逻辑，用桩数据驱动，绝不真连服务器。
 * 覆盖本次新增的核心约束：
 *   - 检查失败（坏响应/断网）必须归为 FAILED，**绝不能**被当成「已是最新」；
 *   - macOS 更新包必须是 .zip（拿不到就给 FAILED，不是"已是最新"）；
 *   - 当前版本来自单一起源（APP_VERSION == pom.xml <version>），界面不写死；
 *   - 检查结果 → 用户可见文案的映射；
 *   - 版本相同 / 已跳过 → 已是最新；有更新 → 发现新版本。 */
package com.moodtree.client.updater;

import com.moodtree.client.Config;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterAboutTest {

    /** pom.xml <version> 就是发布时的单一起源，APP_VERSION 必须与它同步。 */
    @Test
    void versionMatchesPomSingleSource() throws IOException {
        Path pom = Path.of("pom.xml").toAbsolutePath();
        assertTrue(Files.exists(pom), "应在项目目录下找到 pom.xml：" + pom);
        String xml = Files.readString(pom);
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        assertTrue(m.find(), "pom.xml 里应有 <version>");
        assertEquals(m.group(1), UpdaterMac.APP_VERSION,
                "UpdaterMac.APP_VERSION 必须与 pom.xml <version> 保持同一来源");
    }

    /** 坏响应（没版本号）→ FAILED，绝不能返回 UP_TO_DATE。 */
    @Test
    void emptyLatestIsFailureNotUpToDate() {
        assertEquals(UpdaterMac.UpdateCheckResult.FAILED, UpdaterMac.classify("", "", ""),
                "清单里没版本号 = 坏响应，必须报「检查失败」，不能当成「已是最新」");
        assertEquals(UpdaterMac.UpdateCheckResult.FAILED, UpdaterMac.classify(null, "url.zip", "sha"));
    }

    /** 有版本号但缺 .app zip / sha → 也是坏响应，归 FAILED；macOS 只认 .zip。 */
    @Test
    void missingPackageFieldsIsFailure() {
        assertEquals(UpdaterMac.UpdateCheckResult.FAILED, UpdaterMac.classify("1.2.0", "", "sha"));
        assertEquals(UpdaterMac.UpdateCheckResult.FAILED, UpdaterMac.classify("1.2.0", "url", "sha"),
                "macOS 更新包必须是 .zip，给普通链接算坏响应");
        assertEquals(UpdaterMac.UpdateCheckResult.FAILED, UpdaterMac.classify("1.2.0", "a.zip", ""));
        assertEquals(UpdaterMac.UpdateCheckResult.FAILED, UpdaterMac.classify("1.2.0", null, null));
    }

    /** 版本相同 → 已是最新。 */
    @Test
    void sameVersionIsUpToDate() {
        assertEquals(UpdaterMac.UpdateCheckResult.UP_TO_DATE,
                UpdaterMac.classify(UpdaterMac.APP_VERSION, "https://phix.ing/x/up.zip", "abc"));
    }

    /** 用户已「跳过本版本」→ 视为已是最新（不再提示，也不该报失败）。 */
    @Test
    void skippedVersionIsUpToDate() {
        Config cfg = new Config();
        cfg.setSkippedUpdateVersion("9.9.9");   // 只改内存，不落盘（读真实配置会受环境影响）
        assertEquals(UpdaterMac.UpdateCheckResult.UP_TO_DATE,
                UpdaterMac.classify("9.9.9", "https://phix.ing/x/up.zip", "abc", cfg),
                "跳过过的版本不再提示，但不能被误判成「检查失败」");
    }

    /** 更大的版本号，且 .app zip / sha 齐全 → 发现新版本。 */
    @Test
    void newerVersionIsNew() {
        assertEquals(UpdaterMac.UpdateCheckResult.NEW_VERSION,
                UpdaterMac.classify("1.2.0", "https://phix.ing/x/up.zip", "0123abcd"));
    }

    /** 检查结果 → 用户可见文案：三态必须各说各话。 */
    @Test
    void messageMappingForEachOutcome() {
        assertEquals("已是最新版本", UpdaterMac.messageFor(UpdaterMac.UpdateCheckResult.UP_TO_DATE, ""));
        assertEquals("发现新版本 v1.2.0", UpdaterMac.messageFor(UpdaterMac.UpdateCheckResult.NEW_VERSION, "1.2.0"));
        assertEquals("检查失败，请稍后再试", UpdaterMac.messageFor(UpdaterMac.UpdateCheckResult.FAILED, ""));
        // 「失败」与「已是最新」这两句绝不相等（防止把二者混为一谈的经典 bug）
        assertNotEquals("已是最新版本", UpdaterMac.messageFor(UpdaterMac.UpdateCheckResult.FAILED, ""));
    }
}
