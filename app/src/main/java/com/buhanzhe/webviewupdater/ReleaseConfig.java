package com.buhanzhe.webviewupdater;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ReleaseConfig {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public final int schemaVersion;
    public final String assetBaseUrl;
    public final List<LatestVersion> latestVersions;
    public final List<WebViewPackage> packages;

    private ReleaseConfig(int schemaVersion,
                          String assetBaseUrl,
                          List<LatestVersion> latestVersions,
                          List<WebViewPackage> packages) {
        this.schemaVersion = schemaVersion;
        this.assetBaseUrl = assetBaseUrl;
        this.latestVersions = Collections.unmodifiableList(latestVersions);
        this.packages = Collections.unmodifiableList(packages);
    }

    public static ReleaseConfig parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        int schema = root.getInt("schemaVersion");
        if (schema != SUPPORTED_SCHEMA_VERSION) {
            throw new JSONException("unsupported schemaVersion: " + schema);
        }
        String baseUrl = root.optString("assetBaseUrl", "").trim();
        List<LatestVersion> latestVersions = new ArrayList<>();
        JSONArray latestVersionArray = root.optJSONArray("latestVersions");
        if (latestVersionArray != null) {
            for (int index = 0; index < latestVersionArray.length(); index++) {
                latestVersions.add(LatestVersion.parse(latestVersionArray.getJSONObject(index)));
            }
        }
        JSONArray packageArray = root.getJSONArray("packages");
        List<WebViewPackage> result = new ArrayList<>();
        for (int index = 0; index < packageArray.length(); index++) {
            result.add(WebViewPackage.parse(packageArray.getJSONObject(index)));
        }
        if (baseUrl.isEmpty()) {
            for (WebViewPackage item : result) {
                if (item.url.isEmpty()) {
                    throw new JSONException("assetBaseUrl is required when an item uses asset");
                }
            }
        }
        return new ReleaseConfig(schema, baseUrl, latestVersions, result);
    }

    public WebViewPackage findBestMatch(DeviceInfo deviceInfo) {
        WebViewPackage best = null;
        int bestScore = Integer.MIN_VALUE;
        for (WebViewPackage candidate : packages) {
            int score = candidate.matchScore(deviceInfo);
            if (score < 0) {
                continue;
            }
            if (best == null || score > bestScore
                    || (score == bestScore && candidate.versionCode > best.versionCode)) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    public boolean isDeviceAtLeastRecommendedVersion(DeviceInfo deviceInfo) {
        String currentPackage = deviceInfo.webViewPackageName();
        String currentVersion = deviceInfo.webViewVersionName();
        if (currentPackage.isEmpty() || currentVersion.isEmpty()) {
            return false;
        }

        for (LatestVersion latestVersion : latestVersions) {
            if (latestVersion.matches(deviceInfo, currentPackage)) {
                return compareVersionNames(currentVersion, latestVersion.versionName) >= 0;
            }
        }

        WebViewPackage compatiblePackage = findBestMatch(deviceInfo);
        if (compatiblePackage != null) {
            // APK variant codes differ by ABI. The configured version name is the
            // comparable WebView release number across all of those variants.
            return compareVersionNames(currentVersion, compatiblePackage.versionName) >= 0;
        }

        // A newer Android version may intentionally have no downloadable APK in
        // this catalog. It is still current when its provider is newer than every
        // release we offer.
        String newestConfiguredVersion = "";
        for (WebViewPackage candidate : packages) {
            if (!candidate.enabled || !candidate.packageName.equals(currentPackage)) {
                continue;
            }
            int versionComparison = newestConfiguredVersion.isEmpty()
                    ? 1
                    : compareVersionNames(candidate.versionName, newestConfiguredVersion);
            if (versionComparison > 0) {
                newestConfiguredVersion = candidate.versionName;
            }
        }
        if (newestConfiguredVersion.isEmpty()) {
            return false;
        }
        return compareVersionNames(currentVersion, newestConfiguredVersion) >= 0;
    }

    static int compareVersionNames(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int count = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < count; index++) {
            long leftPart = parseVersionPart(leftParts, index);
            long rightPart = parseVersionPart(rightParts, index);
            if (leftPart != rightPart) {
                return leftPart < rightPart ? -1 : 1;
            }
        }
        return 0;
    }

    private static long parseVersionPart(String[] parts, int index) {
        if (index >= parts.length) {
            return 0L;
        }
        String digits = parts[index].replaceAll("[^0-9].*$", "");
        if (digits.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException ignored) {
            return Long.MAX_VALUE;
        }
    }

    public static final class LatestVersion {
        public final int minSdk;
        public final int maxSdk;
        public final String packageName;
        public final String versionName;

        private LatestVersion(int minSdk,
                              int maxSdk,
                              String packageName,
                              String versionName) {
            this.minSdk = minSdk;
            this.maxSdk = maxSdk;
            this.packageName = packageName;
            this.versionName = versionName;
        }

        static LatestVersion parse(JSONObject value) throws JSONException {
            int minSdk = value.getInt("minSdk");
            int maxSdk = value.getInt("maxSdk");
            String packageName = value.getString("packageName").trim();
            String versionName = value.getString("versionName").trim();
            if (minSdk < 1 || maxSdk < minSdk
                    || packageName.isEmpty() || versionName.isEmpty()) {
                throw new JSONException("invalid latestVersions entry");
            }
            return new LatestVersion(minSdk, maxSdk, packageName, versionName);
        }

        boolean matches(DeviceInfo deviceInfo, String currentPackage) {
            return deviceInfo.sdkInt >= minSdk
                    && deviceInfo.sdkInt <= maxSdk
                    && packageName.equals(currentPackage);
        }
    }

    public static final class WebViewPackage {
        public final String id;
        public final String packageName;
        public final String versionName;
        public final long versionCode;
        public final String channel;
        public final int minSdk;
        public final int maxSdk;
        public final List<String> abis;
        public final boolean multiArch;
        public final String asset;
        public final String url;
        public final String sha256;
        public final boolean enabled;

        private WebViewPackage(String id,
                               String packageName,
                               String versionName,
                               long versionCode,
                               String channel,
                               int minSdk,
                               int maxSdk,
                               List<String> abis,
                               boolean multiArch,
                               String asset,
                               String url,
                               String sha256,
                               boolean enabled) {
            this.id = id;
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.channel = channel;
            this.minSdk = minSdk;
            this.maxSdk = maxSdk;
            this.abis = Collections.unmodifiableList(abis);
            this.multiArch = multiArch;
            this.asset = asset;
            this.url = url;
            this.sha256 = sha256;
            this.enabled = enabled;
        }

        static WebViewPackage parse(JSONObject value) throws JSONException {
            List<String> abis = new ArrayList<>();
            JSONArray abiArray = value.optJSONArray("abis");
            if (abiArray != null) {
                for (int index = 0; index < abiArray.length(); index++) {
                    abis.add(abiArray.getString(index));
                }
            }

            String id = value.getString("id").trim();
            String packageName = value.getString("packageName").trim();
            String versionName = value.getString("versionName").trim();
            String asset = value.optString("asset", "").trim();
            String url = value.optString("url", "").trim();
            if (id.isEmpty() || packageName.isEmpty() || versionName.isEmpty()) {
                throw new JSONException("id, packageName and versionName must not be empty");
            }
            if (asset.isEmpty() && url.isEmpty()) {
                throw new JSONException("package " + id + " needs asset or url");
            }

            int minSdk = value.optInt("minSdk", 21);
            int maxSdk = value.optInt("maxSdk", Integer.MAX_VALUE);
            String sha256 = value.optString("sha256", "").trim().toLowerCase(Locale.ROOT);
            if (minSdk < 1 || maxSdk < minSdk) {
                throw new JSONException("invalid SDK range for package " + id);
            }
            if (!sha256.isEmpty() && !sha256.matches("[0-9a-f]{64}")) {
                throw new JSONException("invalid sha256 for package " + id);
            }

            return new WebViewPackage(
                    id,
                    packageName,
                    versionName,
                    value.optLong("versionCode", 0L),
                    value.optString("channel", "stable"),
                    minSdk,
                    maxSdk,
                    abis,
                    value.optBoolean("multiArch", abis.size() > 1),
                    asset,
                    url,
                    sha256,
                    value.optBoolean("enabled", true)
            );
        }

        public String resolveUrl(String assetBaseUrl) {
            if (!url.isEmpty()) {
                return url;
            }
            if (assetBaseUrl.endsWith("/") || asset.startsWith("/")) {
                return assetBaseUrl + asset;
            }
            return assetBaseUrl + "/" + asset;
        }

        public String fileName() {
            String source = asset.isEmpty() ? url : asset;
            int query = source.indexOf('?');
            if (query >= 0) {
                source = source.substring(0, query);
            }
            int slash = source.lastIndexOf('/');
            String name = slash >= 0 ? source.substring(slash + 1) : source;
            name = name.replaceAll("[^A-Za-z0-9._-]", "_");
            return name.toLowerCase(Locale.ROOT).endsWith(".apk") ? name : id + ".apk";
        }

        int matchScore(DeviceInfo device) {
            if (!enabled || device.sdkInt < minSdk || device.sdkInt > maxSdk) {
                return -1;
            }

            int score = 0;
            String currentPackage = device.webViewPackageName();
            if (packageName.equals(currentPackage)) {
                score += 10_000;
            } else if ("*".equals(packageName)) {
                score += 100;
            } else {
                return -1;
            }

            if (abis.isEmpty() || abis.contains("universal")) {
                score += 10;
            } else {
                int processMatch = abis.indexOf(device.processAbi);
                if (processMatch >= 0) {
                    score += 2_000 - processMatch;
                } else {
                    int deviceMatch = firstAbiMatch(device.supportedAbis);
                    if (deviceMatch < 0) {
                        return -1;
                    }
                    score += 500 - deviceMatch;
                }
            }

            if (multiArch == device.webViewMultiArch) {
                score += 200;
            }
            if (versionCode >= device.webViewVersionCode()) {
                score += 20;
            }
            return score;
        }

        private int firstAbiMatch(List<String> deviceAbis) {
            for (int index = 0; index < deviceAbis.size(); index++) {
                if (abis.contains(deviceAbis.get(index))) {
                    return index;
                }
            }
            return -1;
        }
    }
}
