# 闪渡 FluxDrop

> **开发中**：当前是用于协作开发和调试的原型，尚未发布稳定版本。

无需数据线、无需外网流量、可同时发给多台设备的跨端高速互传应用（安卓 ↔ 安卓、安卓 ↔ 电脑）。

## 打开与运行

1. Android Studio → `File → Open` → 选择克隆后的项目目录
2. 等待 Gradle Sync 完成（第一次会下载 Gradle 8.9 和依赖，已配置腾讯/阿里云镜像）
   - 通过 Android Studio 设置本机 Android SDK 路径；`local.properties` 由各开发者本地维护，不提交到仓库
   - 需要 SDK 里装有 **Android 15 (API 35)**；没有的话 Studio 会提示一键安装
3. 手机开启「开发者选项 → USB 调试」，连上电脑，点 ▶ Run
   - 也可 `Build → Build APK(s)`，APK 在 `app/build/outputs/apk/debug/`

最低支持 Android 8.0（免流量热点需要）。

## 三大功能

| 步骤 | 功能 | 实现 |
|---|---|---|
| 第一步 | 单点传输 | 手机内嵌微型 HTTP 服务（端口 47820），流式收发，以流式方式处理文件；雷达页右上角 🔗 可输入对方 IP 直连 |
| 第二步 | 雷达 + 一对多 | UDP 广播（端口 47821）每 2 秒心跳，设备自动出现在雷达上；可同时选中多台，每台一个协程并发发送 |
| 第三步 | 免流量热点 | `startLocalOnlyHotspot` 开本地专用热点（不共享流量）并生成二维码；对方「扫码加入」自动连接（系统相机也能扫） |

### 和电脑互传
电脑**不用装任何软件**：电脑和手机在同一 Wi-Fi（或连上手机热点），浏览器打开「电脑」页显示的地址（如 `http://192.168.1.23:47820`）。
- 电脑 → 手机：把文件拖进网页
- 手机 → 电脑：网页打开后，电脑会出现在手机雷达上，点选它发送，电脑浏览器自动下载

## 代码结构

```
app/src/main/java/com/fluxdrop/app/
├─ server/MiniHttpServer.kt      微型 HTTP 服务（第一步）
├─ discovery/RadarDiscovery.kt   UDP 广播雷达（第二步）
├─ transfer/TransferEngine.kt    并发发送、测速、任务管理（第二步）
├─ transfer/ApiRoutes.kt         接收、浏览器轮询、下载接口
├─ transfer/FileStore.kt         保存到 Download/FluxDrop
├─ hotspot/HotspotManager.kt     免流量热点 + 扫码入网（第三步）
├─ service/TransferService.kt    前台服务，后台持续收发
└─ ui/                           Jetpack Compose 界面（雷达动画、极光背景、渐变进度条…）
app/src/main/assets/web/index.html   电脑浏览器网页版
```

## 常见问题
- **雷达扫不到**：确认两台设备在同一 Wi-Fi；部分路由器开启了「AP 隔离」会屏蔽设备互访，可改用热点模式或手动输入 IP
- **开热点失败**：先关闭系统自带的个人热点；Android 12 及以下需要打开定位开关
- **收到的文件在哪**：手机存储 `Download/FluxDrop`


## 协作开发

- 开发环境：Android Studio、JDK 17、Android SDK 35，Gradle 配置为 8.9。
- 每个功能或修复使用独立分支，通过 Pull Request 合并；Issues 用于记录问题和待办。
- 修改传输、发现或热点逻辑时，在 PR 中说明测试设备、系统版本、网络条件及实际结果。
- 本机路径、签名文件、密钥和个人接收的文件不提交到仓库。

### 当前已知限制

- 仓库目前只有 Gradle Wrapper 配置文件，尚缺 `gradlew`、`gradlew.bat` 和 `gradle-wrapper.jar`。命令行构建前，需使用本机 Gradle 8.9 在项目根目录执行 `gradle wrapper --gradle-version 8.9` 补齐。
- `release` 构建当前也使用调试签名，供开发测试；正式发行前需要配置发布签名。
- `tools/preview_web.js` 使用合成接口数据预览网页，需要 `playwright-core` 和 Chromium 浏览器路径；它不验证手机实际收发。
- Android 版本兼容、多设备并发和真实网络传输仍需持续验证。
