# Release 配置说明

应用固定读取以下 Release 资源：

`https://github.com/buhanzhe/WebViewUpdater/releases/latest/download/webview-packages.json`

建议把 `release/webview-packages.json` 和它引用的 APK 上传到同一个、标记为 Latest 的 GitHub Release。`assetBaseUrl` 与每项的 `asset` 会组合成最终下载地址；也可以给单项填写完整的 `url`，此时忽略 `assetBaseUrl`。

## 字段

根对象：

- `schemaVersion`：当前只能为 `1`。
- `assetBaseUrl`：Release 资源的公共 URL 前缀。
- `latestVersions`：按 provider 包名和 Android API 范围配置的最新 `versionName`。设备版本相同或更高时不再推荐重复安装。此字段可选，旧配置会从匹配的 `packages` 推导。
- `packages`：可供匹配的 WebView APK 列表。

包对象：

- `id`：配置内唯一标识。
- `packageName`：必须与设备当前 WebView provider 包名相同；可用 `*` 作为低优先级兜底，但通常不建议。
- `versionName`、`versionCode`：APK 版本。不同 ABI 变体的 `versionCode` 尾数可能不同；是否需要更新以 `latestVersions` 中的 `versionName` 为准。
- `channel`：显示用渠道名，例如 `stable`。
- `minSdk`、`maxSdk`：允许的 Android API 范围，Android 5.0 是 API 21。
- `abis`：APK 包含或支持的 ABI；空数组或 `universal` 表示通用包。
- `multiArch`：该 APK 是否为多架构包。
- `asset`：相对于 `assetBaseUrl` 的 Release 文件名。
- `url`：可选的完整下载 URL，优先级高于 `asset`。
- `sha256`：下载完成时用于校验；若下载目录中已有同名 APK，则必须配置 SHA-256 且校验通过后才会提示安装。
- `enabled`：设为 `false` 可暂时下线该包。

## 匹配顺序

应用会先排除 SDK 或 ABI 不兼容的项，再按以下顺序选择：

1. 当前 WebView provider 的相同包名；
2. 当前应用进程 ABI；
3. 当前安装包的单架构/多架构形态；
4. 更高的 `versionCode`。

如果设备 Android API 高于同一 provider 的整个配置范围，应用会忽略 `maxSdk`，在仍满足 `minSdk`、包名和 ABI 的条目中先选最高 `versionName`，再按上述架构规则选择具体变体。这个兜底只对高于目录上限的 Android 生效，不会在已配置的 SDK 范围内用旧包替代缺失的 ABI 变体。

配置中不要把不同签名、不同包名的 APK 当作可互换版本。Chrome/WebView 的现代版本还可能依赖 Trichrome Library 或 split APK；首个最小版本只处理单个、可独立安装的 APK。

可从 [`release/webview-packages.example.json`](../release/webview-packages.example.json) 复制条目。替换占位值、填写 SHA-256，再把 `enabled` 改为 `true`。
