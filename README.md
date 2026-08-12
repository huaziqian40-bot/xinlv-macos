# 心履 macOS 客户端

心履（XinLv）心情记录应用的 macOS 桌面客户端。功能与 Windows 端完全一致：
记心情、日历视图、心情推荐、AI 树洞、我的（连胜/徽章/主题），支持离线使用与云端同步。

## 系统要求

- **macOS 12+**（Monterey 或更新版本）
- 同时支持 **Intel** 和 **Apple Silicon（M 系列）** Mac
  - Intel 构建：直接运行
  - M 系列：自动通过 Rosetta 2 运行（首次打开会提示安装 Rosetta，点同意即可）

## 安装

1. 下载 `心履-x.x.x.dmg`
2. 双击打开 DMG
3. 将 **心履.app** 拖入 Applications 文件夹
4. 首次打开：在「访达 → 应用程序」中找到心履，**右键 → 打开**（因为未签名，系统会提示"已损坏/无法验证开发者"——这是正常的，选择"打开"即可）
5. 之后就可以正常双击打开了

> 未签名说明：我们未购买 Apple 开发者账号（$99/年），因此应用未签名、未公证。
> 这是完全合法的，只是首次打开时需要右键 → 打开。数据也在你自己的电脑上，不会受影响。

## 数据存储

- 记录数据存在：`~/Library/Application Support/com.moodtree.app/moodtree.db`
- 配置文件：`~/Library/Application Support/com.moodtree.app/config.properties`
- 卸载时删除这两个文件即可清除全部本地数据

## 离线使用

- 记心情、日历、推荐（有缓存时）完全离线可用
- 联网后自动同步到云端，多设备互通
- 游客模式：不登录也能用，数据只在本机，登录后自动上云

## 开发者：在本机构建

前置：JDK 17+、Maven 3.9+。

```bash
# 编译
mvn package

# 直接运行
mvn javafx:run

# 打 DMG 安装包
./build.sh
```

构建产物在 `target/dist/心履-x.x.x.dmg`。

### 跨架构构建说明

- 在 **Intel Mac** 上执行 `./build.sh` → 得到 x86_64 版本，M 系列 Mac 通过 Rosetta 2 运行
- 若想一次构建同时支持两种架构（Universal Binary），需要一台 M 系列 Mac 作为构建机，或使用 CI（如 GitHub Actions 的 cross-build），当前方案为单架构 + Rosetta，最简单且够用。

## 服务器

默认服务器地址：`http://sc1.dpfrp.top:12345`。可在「我的 → 设置 → 服务器地址」修改。

## 隐私与安全

- 所有数据通过账号绑定，同步到自建服务器
- 聊天记录、心情记录均加密传输（http 明文，个人自用服务器场景）
- 数据可随时在「我的」页查看，删除记录会同步删除云端数据
