package com.buhanzhe.webviewupdater;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ApkDownloadController {
    private static final String STATE_PREFS = "download_state";
    private static final String KEY_ID = "id";
    private static final String KEY_PATH = "path";
    private static final String KEY_SHA256 = "sha256";
    private static final String KEY_NAME = "name";
    private static final String KEY_PACKAGE_NAME = "package_name";
    private static final String KEY_VERSION_CODE = "version_code";

    private final Context context;
    private final DownloadManager downloadManager;
    private final SharedPreferences state;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Listener listener;
    private boolean closed;
    private boolean verifying;

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            poll();
        }
    };

    public ApkDownloadController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        this.state = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
    }

    public boolean restore() {
        long id = state.getLong(KEY_ID, -1L);
        String path = state.getString(KEY_PATH, "");
        if (id < 0L || path == null || path.isEmpty()) {
            return false;
        }
        schedulePoll(0L);
        return true;
    }

    public void start(String url, ReleaseConfig.WebViewPackage selectedPackage) throws IOException {
        if (downloadManager == null) {
            throw new IOException("DownloadManager is unavailable");
        }
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw new IOException("invalid download URL");
        }

        File base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) {
            base = new File(context.getCacheDir(), "downloads");
        }
        if (!base.isDirectory() && !base.mkdirs()) {
            throw new IOException("cannot create download directory");
        }

        String uniqueName = System.currentTimeMillis() + "-" + selectedPackage.fileName();
        File destination = new File(base, uniqueName);
        DownloadManager.Request request = new DownloadManager.Request(uri)
                .setTitle(selectedPackage.fileName())
                .setDescription(selectedPackage.versionName)
                .setMimeType("application/vnd.android.package-archive")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(destination));

        long id = downloadManager.enqueue(request);
        state.edit()
                .putLong(KEY_ID, id)
                .putString(KEY_PATH, destination.getAbsolutePath())
                .putString(KEY_SHA256, selectedPackage.sha256)
                .putString(KEY_NAME, selectedPackage.fileName())
                .putString(KEY_PACKAGE_NAME, selectedPackage.packageName)
                .putLong(KEY_VERSION_CODE, selectedPackage.versionCode)
                .apply();
        listener.onStarted(selectedPackage.fileName());
        schedulePoll(500L);
    }

    public void close() {
        closed = true;
        handler.removeCallbacks(pollTask);
        executor.shutdownNow();
    }

    private void schedulePoll(long delayMillis) {
        handler.removeCallbacks(pollTask);
        if (!closed) {
            handler.postDelayed(pollTask, delayMillis);
        }
    }

    private void poll() {
        if (closed || verifying) {
            return;
        }
        long id = state.getLong(KEY_ID, -1L);
        if (id < 0L) {
            return;
        }

        DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
        try (Cursor cursor = downloadManager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                clearState();
                listener.onFailed(-1, "download record was not found");
                return;
            }

            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                verifyDownloadedFile();
                return;
            }
            if (status == DownloadManager.STATUS_FAILED) {
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                clearState();
                listener.onFailed(reason, null);
                return;
            }

            long downloaded = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            int percent = total > 0 ? (int) Math.min(100L, downloaded * 100L / total) : -1;
            listener.onProgress(percent);
            schedulePoll(800L);
        } catch (RuntimeException error) {
            listener.onFailed(-1, error.getMessage());
            schedulePoll(2_000L);
        }
    }

    private void verifyDownloadedFile() {
        verifying = true;
        listener.onVerifying();
        String path = state.getString(KEY_PATH, "");
        String expected = state.getString(KEY_SHA256, "");
        String expectedPackageName = state.getString(KEY_PACKAGE_NAME, "");
        long expectedVersionCode = state.getLong(KEY_VERSION_CODE, 0L);
        executor.execute(() -> {
            File apk = new File(path == null ? "" : path);
            boolean valid = apk.isFile();
            String error = null;
            if (valid && expected != null && !expected.isEmpty()) {
                try {
                    valid = expected.equalsIgnoreCase(sha256(apk));
                } catch (Exception exception) {
                    valid = false;
                    error = exception.getMessage();
                }
            }
            if (valid) {
                //noinspection deprecation
                PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(
                        apk.getAbsolutePath(), PackageManager.GET_META_DATA);
                if (archive == null) {
                    valid = false;
                    error = "the file is not a readable APK";
                } else if (expectedPackageName != null
                        && !expectedPackageName.isEmpty()
                        && !"*".equals(expectedPackageName)
                        && !expectedPackageName.equals(archive.packageName)) {
                    valid = false;
                    error = "APK package name does not match the Release configuration";
                } else if (expectedVersionCode > 0L
                        && expectedVersionCode != getVersionCode(archive)) {
                    valid = false;
                    error = "APK version code does not match the Release configuration";
                }
            }
            boolean finalValid = valid;
            String finalError = error;
            handler.post(() -> {
                verifying = false;
                clearState();
                if (closed) {
                    return;
                }
                if (finalValid) {
                    listener.onReadyToInstall(apk);
                } else {
                    // A mismatched file must never be offered to the package installer.
                    //noinspection ResultOfMethodCallIgnored
                    apk.delete();
                    listener.onChecksumFailed(finalError);
                }
            });
        });
    }

    private void clearState() {
        state.edit().clear().apply();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static long getVersionCode(PackageInfo packageInfo) {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            return packageInfo.getLongVersionCode();
        }
        //noinspection deprecation
        return packageInfo.versionCode;
    }

    public interface Listener {
        void onStarted(String fileName);

        void onProgress(int percent);

        void onVerifying();

        void onReadyToInstall(File apk);

        void onChecksumFailed(String detail);

        void onFailed(int reason, String detail);
    }
}
