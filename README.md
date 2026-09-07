# WebViewUpdater

一个面向 Android 5.0（API 21）及以上手机和电视的最小 WebView 检测、匹配与下载工具。项目使用 Java，不包含 Kotlin 插件或 Kotlin 源码。

应用不依赖 AndroidX 或第三方运行库，直接使用 Android 5.0 自带的 Material 主题。当前压缩后的可安装 debug APK 小于 100 KiB，并仅包含一个极小的矢量自适应图标；Android TV 另保留启动器要求的纯色 banner。

## 首版功能

- 显示 Android 版本、设备 ABI 和当前应用进程 ABI。
- 识别当前 WebView 实现包、版本、APK 内 ABI，并判断是否为多架构包。
- 自动读取 GitHub Latest Release 中的 `webview-packages.json`。
- 根据包名、Android API、进程 ABI、多架构形态和版本号选择最合适的单 APK。
- 默认依次尝试 `gh-proxy.com` 和 GitHub 直连；也可固定或自定义加速站。
- 支持 HTTP Range 时由应用启动 4 个并发连接分块加速，不支持时自动退回单连接；合并并校验后把 APK 保存到下载目录。
- 新下载完成后尝试打开系统安装器；下载目录中已有同名 APK 时，主按钮直接显示“安装”，点击后校验 SHA-256 并打开安装器。系统安装未完成时尝试打开文件管理器，让用户从下载目录自行安装；仍失败后显示可复制的 `adb shell pm install -r` 命令。
- 手机打开后自动完成检测与匹配，通常只需“下载”和系统“安装”两步。
- 手机固定竖屏显示；Android TV 固定横屏，并提供 Leanback 启动入口、无触屏声明、清晰的遥控器焦点顺序和焦点缩放反馈。

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

仓库中的 Release 配置只列出已核验并上传的独立 APK；示例条目默认 `enabled: false`，避免占位文件名被误用。

## 系统限制

WebView 是系统安全组件。这个应用不能绕过 Android 的校验：APK 的包名和签名必须被当前 ROM 接受，版本降级可能被拒绝，部分系统只允许预配置的 provider。Android 10 以后的一些 WebView 构建还依赖 Trichrome Library 或 split APK，首版不会尝试把这些文件伪装成一个可独立安装包。

下载由应用内四线程下载器执行，完成后写入下载目录。新下载会打开系统安装器；Android 8.0 及以上未授权时，先打开本应用的“安装未知应用”授权页，返回后自动继续。系统安装未完成时，应用会提示并尝试打开文件管理器；文件管理器安装也未成功时，才显示可复制的 ADB 安装命令。
