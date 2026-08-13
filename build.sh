#!/bin/bash
# 心履 macOS 构建脚本
# 用法：./build.sh [--skip-maven]
#
# 前置条件：
#   1. macOS 14+ (Sonoma 或更新)
#   2. JDK 17+（推荐从 https://adoptium.net 下载）
#   3. Maven 3.9+
#   4. 确认 JAVA_HOME 正确设置
#
# 输出：target/dist/心履-x.x.x.dmg
#
# 跨平台说明：
#   本脚本在 Intel Mac 上构建 → x86_64 二进制
#   Apple Silicon Mac 可通过 Rosetta 2 运行，无需额外配置
#   （单架构 DMG 在 M 系列 Mac 上自动触发 Rosetta 转译）

set -euo pipefail

# ---- 颜色输出 ----
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}===== 心履 macOS 构建脚本 =====${NC}"

# 检查 JDK
if [ -z "${JAVA_HOME:-}" ]; then
    echo -e "${RED}错误：JAVA_HOME 未设置${NC}"
    echo "请设置 JAVA_HOME 指向 JDK 17+ 安装目录"
    echo '例如：export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home'
    exit 1
fi
echo -e "${YELLOW}JAVA_HOME:${NC} $JAVA_HOME"

# 检查 Maven
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}错误：mvn 未找到，请安装 Maven 3.9+${NC}"
    exit 1
fi
echo -e "${YELLOW}Maven:${NC} $(mvn --version 2>&1 | head -1)"

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

# ---- 步骤 1：Maven 构建 ----
if [ "${1:-}" != "--skip-maven" ]; then
    echo -e "\n${YELLOW}[1/3] Maven 编译 + 打包...${NC}"
    mvn clean package -q
    echo -e "${GREEN}✓ 编译成功${NC}"
else
    echo -e "\n${YELLOW}[1/3] 跳过 Maven（已有 jar）${NC}"
fi

# ---- 步骤 2：生成 .icns 图标（如果不存在） ----
# 注意：logo.icns 不应提交到 git——每次构建从 logo.png 重新生成，
# 避免 PNG 换过但 icns 还是旧的（图标不更新）。若想强制重建：rm src/main/resources/logo.icns
echo -e "\n${YELLOW}[2/3] 生成图标（始终从 logo.png 重建，保证透明通道 & 最新 logo）...${NC}"
ICON="src/main/resources/logo.icns"
if [ -f "src/main/resources/logo.png" ]; then
    ICONSET="build/logo.iconset"
    rm -rf "$ICONSET"
    mkdir -p "$ICONSET"
    sips -z 16 16 src/main/resources/logo.png --out "$ICONSET/icon_16x16.png" > /dev/null 2>&1
    sips -z 32 32 src/main/resources/logo.png --out "$ICONSET/icon_16x16@2x.png" > /dev/null 2>&1
    sips -z 32 32 src/main/resources/logo.png --out "$ICONSET/icon_32x32.png" > /dev/null 2>&1
    sips -z 64 64 src/main/resources/logo.png --out "$ICONSET/icon_32x32@2x.png" > /dev/null 2>&1
    sips -z 128 128 src/main/resources/logo.png --out "$ICONSET/icon_128x128.png" > /dev/null 2>&1
    sips -z 256 256 src/main/resources/logo.png --out "$ICONSET/icon_128x128@2x.png" > /dev/null 2>&1
    sips -z 256 256 src/main/resources/logo.png --out "$ICONSET/icon_256x256.png" > /dev/null 2>&1
    sips -z 512 512 src/main/resources/logo.png --out "$ICONSET/icon_256x256@2x.png" > /dev/null 2>&1
    sips -z 512 512 src/main/resources/logo.png --out "$ICONSET/icon_512x512.png" > /dev/null 2>&1
    iconutil -c icns "$ICONSET" -o "$ICON"
    rm -rf "$ICONSET"
    echo -e "${GREEN}✓ logo.icns 已从 logo.png 重建（保留透明通道）${NC}"
else
    echo -e "${YELLOW}⚠ logo.png 未找到，跳过图标（Dock 将使用默认图标）${NC}"
fi

# ---- 步骤 3：jpackage 打包 DMG ----
echo -e "\n${YELLOW}[3/3] jpackage 打包 DMG...${NC}"

# 确认 jpackage 可用
if ! command -v jpackage &> /dev/null; then
    echo -e "${RED}错误：jpackage 未找到（JDK 14+ 包含 jpackage 工具）${NC}"
    exit 1
fi

APP_VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || echo "1.0.0")
# 主 jar 名跟随版本号，避免硬编码导致 jpackage 找不到
MAIN_JAR="moodtree-client-${APP_VERSION}.jar"

rm -rf target/dist

# 存 jpackage 资源目录（如 Info.plist 片段）
RESOURCE_DIR="build/jpackage-resources"
mkdir -p "$RESOURCE_DIR"

# 设置 Mac 菜单栏相关
# 默认的 CFBundleIdentifier 会从 --module 推导，也可手动指定
# 注意：不签名时，用户首次启动需右键 → 打开

# 构建只含实际模块的 lib 目录（与 Windows build_simple.bat 策略一致）：
# 只复制带平台后缀的 javafx jar（-mac.jar），不复制空壳的 javafx-*.jar（无 module-info.class，
# 被 jlink 当作自动模块后报错 "自动模块不能用于 jlink"）。也只复制应用 jar 和运行时依赖。
rm -rf target/pkg-lib
mkdir -p target/pkg-lib
cp target/moodtree-client-*.jar target/pkg-lib/
cp target/lib/javafx-*-mac.jar target/pkg-lib/ 2>/dev/null
cp target/lib/gson-*.jar target/pkg-lib/
cp target/lib/sqlite-jdbc-*.jar target/pkg-lib/
cp target/lib/slf4j-*.jar target/pkg-lib/
cp target/lib/error_prone_annotations-*.jar target/pkg-lib/ 2>/dev/null

# 用 --module 模式打包，jpackage 自动裁剪 JVM 运行时为真实依赖模块，
# 避免捆绑完整 JDK（modules 文件 112MB）。裁剪后 DMG 从 ~115MB 降到 ~60-70MB。
jpackage \
    --type dmg \
    --name "心履" \
    --app-version "$APP_VERSION" \
    --vendor "心履" \
    --module-path target/pkg-lib \
    --module com.moodtree.client/com.moodtree.client.Main \
    --icon "$ICON" \
    --dest target/dist \
    --mac-package-identifier com.moodtree.app \
    --mac-package-name "心履" \
    --resource-dir "$RESOURCE_DIR" \
    --java-options "-Xmx512m" \
    --java-options "-Dfile.encoding=UTF-8"

echo -e "\n${GREEN}✓ 构建完成！${NC}"
echo -e "DMG 文件：${GREEN}$(ls target/dist/*.dmg)${NC}"
echo -e "\n首个 DMG 文件大小："
ls -lh target/dist/*.dmg

echo -e "\n${YELLOW}使用说明：${NC}"
echo "  1. 双击 DMG 安装"
echo "  2. 将 心履.app 拖入 Applications 文件夹"
echo "  3. 首次打开：右键 → 打开（因未签名）"
echo "  4. 之后可直接双击打开"