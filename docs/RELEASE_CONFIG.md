# Release 配置说明

应用固定读取以下 Release 资源：

`https://github.com/buhanzhe/WebViewUpdater/releases/latest/download/webview-packages.json`

建议把 `release/webview-packages.json` 和它引用的 APK 上传到同一个、标记为 Latest 的 GitHub Release。`assetBaseUrl` 与每项的 `asset` 会组合成最终下载地址；也可以给单项填写完整的 `url`，此时忽略 `assetBaseUrl`。

## 字段

根对象：

- `schemaVersion`：当前只能为 `1`。
- `assetBaseUrl`：Release 资源的公共 URL 前缀。
- `packages`：可供匹配的 WebView APK 列表。

包对象：

- `id`：配置内唯一标识。
- `packageName`：必须与设备当前 WebView provider 包名相同；可用 `*` 作为低优先级兜底，但通常不建议。
- `versionName`、`versionCode`：APK 版本。版本号相同时优先较大的 `versionCode`。
- `channel`：显示用渠道名，例如 `stable`。
- `minSdk`、`maxSdk`：允许的 Android API 范围，Android 5.0 是 API 21。
- `abis`：APK 包含或支持的 ABI；空数组或 `universal` 表示通用包。
- `multiArch`：该 APK 是否为多架构包。
- `asset`：相对于 `assetBaseUrl` 的 Release 文件名。
- `url`：可选的完整下载 URL，优先级高于 `asset`。
- `sha256`：可选但强烈建议填写。填写后应用会在安装前校验。
- `enabled`：设为 `false` 可暂时下线该包。

## 匹配顺序

应用会先排除 SDK 或 ABI 不兼容的项，再按以下顺序选择：

1. 当前 WebView provider 的相同包名；
2. 当前应用进程 ABI；
3. 当前安装包的单架构/多架构形态；
4. 更高的 `versionCode`。

配置中不要把不同签名、不同包名的 APK 当作可互换版本。Chrome/WebView 的现代版本还可能依赖 Trichrome Library 或 split APK；首个最小版本只处理单个、可独立安装的 APK。

可从 [`release/webview-packages.example.json`](../release/webview-packages.example.json) 复制条目。替换占位值、填写 SHA-256，再把 `enabled` 改为 `true`。

