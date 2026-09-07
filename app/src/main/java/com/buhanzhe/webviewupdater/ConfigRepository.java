package com.buhanzhe.webviewupdater;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ConfigRepository {
    private static final int MAX_CONFIG_SIZE = 2 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private final DownloadSourcePreferences preferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ConfigRepository(DownloadSourcePreferences preferences) {
        this.preferences = preferences;
    }

    public void load(Callback callback) {
        executor.execute(() -> {
            Exception lastError = null;
            List<DownloadSourcePreferences.Source> sources = preferences.configSources();
            for (DownloadSourcePreferences.Source source : sources) {
                try {
                    String body = downloadText(source.configUrl());
                    ReleaseConfig config = ReleaseConfig.parse(body);
                    ResolvedConfig result = new ResolvedConfig(config, source.name, source.prefix);
                    mainHandler.post(() -> callback.onLoaded(result));
                    return;
                } catch (Exception error) {
                    lastError = error;
                }
            }

            Exception finalError = lastError == null
                    ? new IOException("no download source configured") : lastError;
            mainHandler.post(() -> callback.onError(finalError));
        });
    }

    public void close() {
        executor.shutdownNow();
    }

    private static String downloadText(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "WebViewUpdater-Android/0.1");

        try {
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP " + responseCode + " from " + address);
            }
            int declaredLength = connection.getContentLength();
            if (declaredLength > MAX_CONFIG_SIZE) {
                throw new IOException("configuration is too large");
            }

            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                int total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_CONFIG_SIZE) {
                        throw new IOException("configuration is too large");
                    }
                    output.write(buffer, 0, count);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    public interface Callback {
        void onLoaded(ResolvedConfig result);

        void onError(Exception error);
    }

    public static final class ResolvedConfig {
        public final ReleaseConfig config;
        public final String sourceName;
        public final String proxyPrefix;

        ResolvedConfig(ReleaseConfig config, String sourceName, String proxyPrefix) {
            this.config = config;
            this.sourceName = sourceName;
            this.proxyPrefix = proxyPrefix;
        }
    }
}

