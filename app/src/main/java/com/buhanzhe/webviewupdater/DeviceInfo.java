package com.buhanzhe.webviewupdater;

import android.content.pm.PackageInfo;

import java.util.Collections;
import java.util.List;

public final class DeviceInfo {
    public final int sdkInt;
    public final String androidRelease;
    public final List<String> supportedAbis;
    public final String processAbi;
    public final PackageInfo webViewPackage;
    public final List<String> webViewAbis;
    public final boolean webViewMultiArch;

    DeviceInfo(int sdkInt,
               String androidRelease,
               List<String> supportedAbis,
               String processAbi,
               PackageInfo webViewPackage,
               List<String> webViewAbis,
               boolean webViewMultiArch) {
        this.sdkInt = sdkInt;
        this.androidRelease = androidRelease;
        this.supportedAbis = Collections.unmodifiableList(supportedAbis);
        this.processAbi = processAbi;
        this.webViewPackage = webViewPackage;
        this.webViewAbis = Collections.unmodifiableList(webViewAbis);
        this.webViewMultiArch = webViewMultiArch;
    }

    public String webViewPackageName() {
        return webViewPackage == null ? "" : webViewPackage.packageName;
    }

    public String webViewVersionName() {
        if (webViewPackage == null || webViewPackage.versionName == null) {
            return "";
        }
        return webViewPackage.versionName;
    }

    public long webViewVersionCode() {
        if (webViewPackage == null) {
            return 0L;
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            return webViewPackage.getLongVersionCode();
        }
        //noinspection deprecation
        return webViewPackage.versionCode;
    }
}

