package com.buhanzhe.webviewupdater;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DownloadSourcePreferences {
    public static final String MODE_AUTO = "auto";
    public static final String MODE_PROXY_COM = "proxy_com";
    public static final String MODE_DIRECT = "direct";
    public static final String MODE_CUSTOM = "custom";

    public static final String PROXY_COM = "https://gh-proxy.com/";
    public static final String CONFIG_URL =
            "https://github.com/buhanzhe/WebViewUpdater/releases/latest/download/webview-packages.json";

    private static final String PREFS = "download_sources";
    private static final String KEY_MODE = "mode";
    private static final String KEY_CUSTOM = "custom";

    private final SharedPreferences preferences;

    public DownloadSourcePreferences(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getMode() {
        return preferences.getString(KEY_MODE, MODE_AUTO);
    }

    public String getCustomProxy() {
        return preferences.getString(KEY_CUSTOM, "");
    }

    public void save(String mode, String customProxy) {
        preferences.edit()
                .putString(KEY_MODE, mode)
                .putString(KEY_CUSTOM, normalizePrefix(customProxy))
                .apply();
    }

    public List<Source> configSources() {
        String mode = getMode();
        if (MODE_PROXY_COM.equals(mode)) {
            return Collections.singletonList(new Source("gh-proxy.com", PROXY_COM));
        }
        if (MODE_DIRECT.equals(mode)) {
            return Collections.singletonList(new Source("GitHub", ""));
        }
        if (MODE_CUSTOM.equals(mode)) {
            return Collections.singletonList(new Source("Custom", normalizePrefix(getCustomProxy())));
        }

        List<Source> automatic = new ArrayList<>();
        automatic.add(new Source("gh-proxy.com", PROXY_COM));
        automatic.add(new Source("GitHub", ""));
        return automatic;
    }

    public String displayName(Context context) {
        String mode = getMode();
        if (MODE_PROXY_COM.equals(mode)) {
            return context.getString(R.string.proxy_one);
        }
        if (MODE_DIRECT.equals(mode)) {
            return context.getString(R.string.proxy_direct);
        }
        if (MODE_CUSTOM.equals(mode)) {
            String custom = getCustomProxy();
            return custom.isEmpty() ? context.getString(R.string.proxy_custom) : custom;
        }
        return context.getString(R.string.proxy_auto);
    }

    public static boolean isValidCustomProxy(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static String applyProxy(String originalUrl, String prefix) {
        if (prefix == null || prefix.isEmpty() || originalUrl == null) {
            return originalUrl;
        }
        // GitHub accelerators are only appropriate for GitHub assets. External URLs,
        // such as a future APKMirror source, are kept intact.
        try {
            URI uri = URI.create(originalUrl);
            String host = uri.getHost();
            if (host == null || !(host.equals("github.com") || host.endsWith(".github.com"))) {
                return originalUrl;
            }
        } catch (IllegalArgumentException ignored) {
            return originalUrl;
        }
        return normalizePrefix(prefix) + originalUrl;
    }

    private static String normalizePrefix(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.endsWith("/")) {
            return trimmed;
        }
        return trimmed + "/";
    }

    public static final class Source {
        public final String name;
        public final String prefix;

        Source(String name, String prefix) {
            this.name = name;
            this.prefix = prefix;
        }

        public String configUrl() {
            return applyProxy(CONFIG_URL, prefix);
        }
    }
}
