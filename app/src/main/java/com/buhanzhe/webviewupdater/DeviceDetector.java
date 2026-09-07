package com.buhanzhe.webviewupdater;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.webkit.WebView;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class DeviceDetector {
    private static final String WEBVIEW_LIBRARY_META_DATA = "com.android.webview.WebViewLibrary";
    private static final List<String> KNOWN_PROVIDERS = Arrays.asList(
            "com.google.android.webview",
            "com.android.webview",
            "com.android.chrome",
            "com.google.android.webview.beta",
            "com.google.android.webview.dev",
            "com.google.android.webview.canary"
    );

    private DeviceDetector() {
    }

    public static DeviceInfo detect(Context context) {
        List<String> supportedAbis = getSupportedAbis();
        PackageInfo provider = findCurrentWebViewPackage(context);
        List<String> packageAbis = provider == null
                ? Collections.<String>emptyList()
                : findPackageAbis(provider.applicationInfo);
        boolean multiArch = provider != null && isMultiArch(provider.applicationInfo, packageAbis);

        return new DeviceInfo(
                Build.VERSION.SDK_INT,
                Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE,
                supportedAbis,
                getProcessAbi(supportedAbis),
                provider,
                packageAbis,
                multiArch
        );
    }

    private static List<String> getSupportedAbis() {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.addAll(Arrays.asList(Build.SUPPORTED_ABIS));
        values.remove("");
        return new ArrayList<>(values);
    }

    private static String getProcessAbi(List<String> supportedAbis) {
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.US);
        if (architecture.contains("aarch64") || architecture.contains("arm64")) {
            return "arm64-v8a";
        }
        if (architecture.contains("arm")) {
            return "armeabi-v7a";
        }
        if (architecture.contains("x86_64") || architecture.contains("amd64")) {
            return "x86_64";
        }
        if (architecture.contains("86")) {
            return "x86";
        }
        return supportedAbis.isEmpty() ? architecture : supportedAbis.get(0);
    }

    @SuppressLint({"PrivateApi", "QueryPermissionsNeeded", "WebViewApiAvailability"})
    private static PackageInfo findCurrentWebViewPackage(Context context) {
        PackageManager packageManager = context.getPackageManager();

        if (Build.VERSION.SDK_INT >= 26) {
            try {
                return WebView.getCurrentWebViewPackage();
            } catch (RuntimeException ignored) {
                // Continue with compatibility fallbacks for vendor ROMs.
            }
        }

        String selectedPackage = null;
        try {
            selectedPackage = Settings.Global.getString(
                    context.getContentResolver(), "webview_provider");
        } catch (RuntimeException ignored) {
            // Some vendor ROMs restrict this global setting.
        }
        PackageInfo fromSetting = getPackageInfo(packageManager, selectedPackage);
        if (fromSetting != null) {
            return fromSetting;
        }

        if (Build.VERSION.SDK_INT <= 25) {
            PackageInfo reflected = findLegacyProvider(packageManager);
            if (reflected != null) {
                return reflected;
            }
        }

        for (String packageName : KNOWN_PROVIDERS) {
            PackageInfo known = getPackageInfo(packageManager, packageName);
            if (isWebViewProvider(known)) {
                return known;
            }
        }

        if (Build.VERSION.SDK_INT <= 25) {
            try {
                //noinspection deprecation
                List<PackageInfo> installed = packageManager.getInstalledPackages(PackageManager.GET_META_DATA);
                for (PackageInfo candidate : installed) {
                    if (isWebViewProvider(candidate)) {
                        return candidate;
                    }
                }
            } catch (RuntimeException ignored) {
                // A partial device report is still more useful than a crash.
            }
        }
        return null;
    }

    @SuppressLint("PrivateApi")
    private static PackageInfo findLegacyProvider(PackageManager packageManager) {
        try {
            Class<?> factory = Class.forName("android.webkit.WebViewFactory");
            Method nameMethod = factory.getDeclaredMethod("getWebViewPackageName");
            nameMethod.setAccessible(true);
            Object value = nameMethod.invoke(null);
            PackageInfo result = getPackageInfo(packageManager, value instanceof String ? (String) value : null);
            if (result != null) {
                return result;
            }
        } catch (Exception ignored) {
            // Method names vary between Android releases and vendor forks.
        }

        try {
            Class<?> factory = Class.forName("android.webkit.WebViewFactory");
            Method loadedMethod = factory.getDeclaredMethod("getLoadedPackageInfo");
            loadedMethod.setAccessible(true);
            Object value = loadedMethod.invoke(null);
            if (value instanceof PackageInfo) {
                return (PackageInfo) value;
            }
        } catch (Exception ignored) {
            // WebView has usually not been loaded yet; null is expected here.
        }
        return null;
    }

    private static PackageInfo getPackageInfo(PackageManager packageManager, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return null;
        }
        try {
            //noinspection deprecation
            return packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isWebViewProvider(PackageInfo packageInfo) {
        if (packageInfo == null || packageInfo.applicationInfo == null
                || !packageInfo.applicationInfo.enabled) {
            return false;
        }
        if (KNOWN_PROVIDERS.contains(packageInfo.packageName)) {
            return true;
        }
        return packageInfo.applicationInfo.metaData != null
                && packageInfo.applicationInfo.metaData.containsKey(WEBVIEW_LIBRARY_META_DATA);
    }

    private static boolean isMultiArch(ApplicationInfo info, List<String> packageAbis) {
        if ((info.flags & ApplicationInfo.FLAG_MULTIARCH) != 0) {
            return true;
        }
        if (packageAbis.size() > 1) {
            return true;
        }
        String secondary = readStringField(info, "secondaryCpuAbi");
        return secondary != null && !secondary.isEmpty();
    }

    private static List<String> findPackageAbis(ApplicationInfo info) {
        LinkedHashSet<String> abis = new LinkedHashSet<>();
        inspectApk(info.sourceDir, abis);
        if (info.splitSourceDirs != null) {
            for (String split : info.splitSourceDirs) {
                inspectApk(split, abis);
            }
        }

        addIfPresent(abis, readStringField(info, "primaryCpuAbi"));
        addIfPresent(abis, readStringField(info, "secondaryCpuAbi"));
        return new ArrayList<>(abis);
    }

    private static void inspectApk(String path, Set<String> abis) {
        if (path == null || !new File(path).isFile()) {
            return;
        }
        try (ZipFile zipFile = new ZipFile(path)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith("lib/")) {
                    continue;
                }
                int separator = name.indexOf('/', 4);
                if (separator > 4) {
                    addIfPresent(abis, name.substring(4, separator));
                }
            }
        } catch (Exception ignored) {
            // System APK access can be restricted on customized devices.
        }
    }

    private static String readStringField(ApplicationInfo info, String name) {
        try {
            Field field = ApplicationInfo.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(info);
            return value instanceof String ? (String) value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void addIfPresent(Set<String> values, String value) {
        if (value != null && !value.trim().isEmpty()) {
            values.add(value.trim());
        }
    }
}
