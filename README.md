# WebViewUpdater

一个面向 Android 5.0（API 21）及以上手机和电视的最小 WebView 检测、匹配与下载工具。项目使用 Java，不包含 Kotlin 插件或 Kotlin 源码。

应用不依赖 AndroidX 或第三方运行库，直接使用 Android 5.0 自带的 Material 主题。当前压缩后的可安装 debug APK 小于 50 KiB，并仅包含一个极小的矢量自适应图标；Android TV 另保留启动器要求的纯色 banner。

## 首版功能

- 显示 Android 版本、设备 ABI 和当前应用进程 ABI。
- 识别当前 WebView 实现包、版本、APK 内 ABI，并判断是否为多架构包。
- 自动读取 GitHub Latest Release 中的 `webview-packages.json`。
- 根据包名、Android API、进程 ABI、多架构形态和版本号选择最合适的单 APK。
- 默认依次尝试 `gh-proxy.com` 和 GitHub 直连；也可固定或自定义加速站。
- 使用系统 DownloadManager 下载，可选 SHA-256 校验，随后打开系统安装器。
- 手机打开后自动完成检测与匹配，通常只需“下载”和系统“安装”两步。
- Android TV 提供 Leanback 启动入口、无触屏声明、清晰的遥控器焦点顺序和焦点缩放反馈。

## 构建

环境要求：JDK 17 或更新版本、Android SDK 35。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'D:\android\sdk'
.\gradlew.bat assembleDebug
```

调试 APK 生成在 `app/build/outputs/apk/debug/app-debug.apk`。

## 准备 Release

1. 下载并核对你要提供的 WebView APK。
2. 计算每个 APK 的 SHA-256。
3. 按 [`docs/RELEASE_CONFIG.md`](docs/RELEASE_CONFIG.md) 编辑 `release/webview-packages.json`。
4. 新建 GitHub Release，同时上传该 JSON 和配置引用的所有 APK。
5. 确保该 Release 被 GitHub 标记为 Latest；应用会自动读取它。

仓库默认配置的 `packages` 为空，不会下载任何第三方 APK。示例条目默认 `enabled: false`，避免占位文件名被误用。

## 系统限制

WebView 是系统安全组件。这个应用不能绕过 Android 的校验：APK 的包名和签名必须被当前 ROM 接受，版本降级可能被拒绝，部分系统只允许预配置的 provider。Android 10 以后的一些 WebView 构建还依赖 Trichrome Library 或 split APK，首版不会尝试把这些文件伪装成一个可独立安装包。

Android 8.0 及以上首次安装 APK 时，需要手动授予“安装未知应用”权限。应用会在返回后继续打开安装器。
