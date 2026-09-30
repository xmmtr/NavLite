# NavLite · 极简导航

![Platform](https://img.shields.io/badge/platform-Android-3DDC84) ![Kotlin](https://img.shields.io/badge/Kotlin-1.8-7F52FF) ![License](https://img.shields.io/badge/license-MIT-blue) ![Version](https://img.shields.io/badge/version-1.4.4-orange)

**English**: NavLite is a minimalist, ad-free Android navigation app built on the AMap Open SDK. No ads, no login, no cloud sync, no social feed — just search, preview the route, and drive. Bring your own AMap API key (paste it in Settings on first launch). UI in Chinese & English.

一个**无广告、无登录、无云端、无信息流**的安卓导航 App。起源于一个朴素的诉求：主流地图 App 里找导航按钮像在广告堆里寻宝。NavLite 只做一件事——**输入目的地，预览路线，开始导航**。

- 基于[高德开放平台](https://lbs.amap.com/)导航/地图/定位/搜索四合一 SDK（10.0.800，锁版）
- **自带 Key 运行时填入**：不打包任何 Key，装好后在设置页粘贴你自己的高德 Key 即可用，一个 APK 所有人通用
- 全部数据（历史记录、家/公司/常用地点、设置）只存本机，零云端零上报

## 功能

- **搜索即联想**：输入目的地实时联想（高德输入提示），选中先预览
- **先预览再导航**：多路线方案卡（时长/距离/红绿灯数/过路费/「时间最短」「距离最短」标签），选中高亮其余弱显，地图上手绘路线+起终点标注，看好了才点「开始导航」
- **途经点**：一条路线最多加 5 个，点击即删、即改即重算
- **一键换返程**：预览页 ⇅ 对调起终点，返程不写历史
- **算路偏好**：智能推荐 / 时间短 / 躲避拥堵 / 不走高速 / 高速优先，设置页与预览页均可切换
- **语音播报**：导航转向播报用系统 TTS（静音开关在设置页）
- **家 / 公司 / 常用地点**：主界面一键直达（带直线距离），全部本地存储
- **历史记录**：默认开、可关闭、可清空，本地 50 条封顶
- **中英双语**：设置页一键切换
- **室内可用性**：高精度定位失败自动降级纯网络定位重试；无网络时进入导航页会先给中文提示
- 通用设置十二项：昼夜模式、车头朝上/北向上、3D 手势、路况线、常亮、车道信息、路口放大、电子眼、指南针、播报详细/简洁、模拟导航等

## 安装（三步）

1. 从 [Releases](../../releases) 下载最新 APK 安装；
2. 去[高德开放平台](https://console.amap.com/)注册（个人认证免费），创建应用 → 添加 Key → 平台选 **Android**，包名填 `com.xmmtr.navlite`，SHA1 填你安装包对应签名的指纹（本仓库 Release 包的 SHA1 见 Release 说明；自己构建则用 `gradlew signingReport` 查）；
3. 打开 App → 设置 → 粘贴 Key → 开始用。

> 高德个人认证配额（路径规划 15 万次/月、定位 150 万次/月）对个人日常使用绰绰有余。Key 只存在你手机本机（SharedPreferences），本仓库与 APK 内不含任何 Key。

## 从源码构建

```bash
git clone https://github.com/xmmtr/NavLite.git
cd NavLite
# 用 Android Studio 打开，或命令行：
gradlew.bat assembleDebug      # Windows
./gradlew assembleDebug        # macOS / Linux
```

- 环境：JDK 11、Android SDK（compileSdk 33）、AGP 7.4.2 / Gradle 7.6.4
- `local.properties` 里配 `sdk.dir`（Android Studio 打开会自动生成）
- 想用自己的签名：复制 `signing.properties.example` 为 `signing.properties` 填入信息（该文件不入库）；没有它也能构建 debug 包（默认调试签名）
- **注意**：自己构建后签名变了，高德 Key 要绑定**你的** SHA1（包名不变仍用 `com.xmmtr.navlite`，或改 `applicationId` 后同步改 Key 配置）

## 已知边界（如实说明）

- **需要联网**：导航引擎初始化与路线计算均需网络；室内 GPS 无信号时走 WiFi/基站网络定位（精度略低，起步够用），完全无网时无法导航（高德官方 App 同理）
- **无离线导航/离线地图**：明确不在当前范围
- 仅驾车导航：公交/步行/骑行/打车等出行方式不在范围内（极简定位）

## 参与贡献

欢迎 Issue 和 PR。本项目有几条不可动摇的原则，提 PR 前请确认方向一致：

1. **极简**：不加广告、不加推荐流、不加社交；新功能先开 Issue 讨论
2. **本地优先**：用户数据不上云、不上报、不收集
3. **Key 零内置**：任何把 Key 写进仓库或 APK 的改动都不会被合并
4. 高德 SDK 锁版 `10.0.800` 四合一包，升级需充分验证

开发路线（部分）：车速自动巡航、途经点预览态内增删、桌面小组件、Wear 适配（欢迎认领）。

## 许可

[MIT](LICENSE) © 2026 xmmtr

本项目基于[高德开放平台 Android SDK](https://lbs.amap.com/api/android-navi-sdk/summary) 开发，使用与分发需遵守[高德开放平台服务条款](https://lbs.amap.com/home/terms/)；本项目与高德软件无关。语音播报依赖系统 TTS 引擎。
