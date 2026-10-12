/* 「关于软件」的跨平台统一入口。
 *
 * MeView 是 Windows / macOS 两份**共用**的界面代码，不能直接依赖某个平台的
 * 更新器类名（Windows 是 Updater，macOS 是 UpdaterMac）。所以这里加一层薄壳：
 * MeView 只认本类的静态方法，具体的版本号与检查逻辑仍全部交给平台各自的
 * Updater —— 单一起源不变，跨平台界面上不出现任何技术细节。
 */
package com.moodtree.client.about;

import com.moodtree.client.updater.UpdaterMac;

/** 关于软件的跨平台壳：MeView 只认这一层，具体逻辑交给平台各自的 Updater。 */
public final class About {

    private About() { }

    /** 当前版本（单一起源：平台 Updater 的 APP_VERSION，非硬编码）。 */
    public static String version() { return UpdaterMac.APP_VERSION; }

    /** 手动检查更新：结果三态，绝不把失败当「已是最新」。 */
    public static UpdaterMac.UpdateCheckResult check() { return UpdaterMac.manualCheck(); }

    /** 手动检查发现的新版本号（无则空串）。 */
    public static String newVersion() { return UpdaterMac.newVersion(); }

    /** 检查结果 → 界面提示文案（纯映射，测试直接测）。 */
    public static String messageFor(UpdaterMac.UpdateCheckResult r, String newVersion) {
        return UpdaterMac.messageFor(r, newVersion);
    }

    /** 是否发现新版本（MeView 据此触发既有更新流程）。 */
    public static boolean isUpdateAvailable(UpdaterMac.UpdateCheckResult r) {
        return r == UpdaterMac.UpdateCheckResult.NEW_VERSION;
    }

    /** 发现新版本后走既有「确认卡片」更新流程（后台线程，不堵界面）。 */
    public static void runUpdateFlow() { UpdaterMac.runManualUpdateFlow(); }
}