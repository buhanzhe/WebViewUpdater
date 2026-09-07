package com.buhanzhe.webviewupdater;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ApkDownloadController {
    private static final String STATE_PREFS = "download_state";
    private static final String KEY_URL = "url";
    private static final String KEY_PATH = "path";
    private static final String KEY_TEMP_PATH = "temp_path";
    private static final String KEY_SHA256 = "sha256";
    private static final String KEY_NAME = "name";
    private static final String KEY_PACKAGE_NAME = "package_name";
    private static final String KEY_VERSION_CODE = "version_code";
    private static final String KEY_TOTAL_BYTES = "total_bytes";
    private static final String KEY_SEGMENTS = "segments";

    private static final int PARALLEL_CONNECTIONS = 4;
    private static final long MIN_PARALLEL_SIZE = 4L * 1024L * 1024L;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_REDIRECTS = 8;
    private static final int MAX_SEGMENT_ATTEMPTS = 5;
    private static final int BUFFER_SIZE = 64 * 1024;

    private final Context context;
    private final SharedPreferences state;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor();
    private final ExecutorService workers = Executors.newFixedThreadPool(PARALLEL_CONNECTIONS);
    private final Listener listener;

    private volatile List<Segment> activeSegments = Collections.emptyList();
    private volatile long totalBytes = -1L;
    private volatile boolean closed;
    private volatile boolean downloading;
    private volatile boolean verifying;

    private final Runnable progressTask = new Runnable() {
        @Override
        public void run() {
            if (closed || !downloading) {
                return;
            }
            persistSegments();
            long total = totalBytes;
            long downloaded = downloadedBytes(activeSegments);
            int percent = total > 0L
                    ? (int) Math.min(100L, downloaded * 100L / total)
                    : -1;
            listener.onProgress(percent);
            handler.postDelayed(this, 750L);
        }
    };

    public ApkDownloadController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.state = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
    }

    public boolean restore() {
        String url = state.getString(KEY_URL, "");
        String path = state.getString(KEY_PATH, "");
        String tempPath = state.getString(KEY_TEMP_PATH, "");
        if (isEmpty(url) || isEmpty(path) || isEmpty(tempPath)) {
            clearState();
            return false;
        }

        listener.onStarted(state.getString(KEY_NAME, "APK"));
        File destination = new File(path);
        if (destination.isFile()) {
            verifyDownloadedFile();
        } else {
            beginDownload();
        }
        return true;
    }

    public void start(String url, ReleaseConfig.WebViewPackage selectedPackage) throws IOException {
        if (downloading || verifying) {
            throw new IOException("a download is already running");
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

        String safeName = selectedPackage.fileName().replace('/', '_').replace('\\', '_');
        File destination = new File(base, System.currentTimeMillis() + "-" + safeName);
        File temporary = new File(destination.getAbsolutePath() + ".part");
        state.edit()
                .putString(KEY_URL, url)
                .putString(KEY_PATH, destination.getAbsolutePath())
                .putString(KEY_TEMP_PATH, temporary.getAbsolutePath())
                .putString(KEY_SHA256, selectedPackage.sha256)
                .putString(KEY_NAME, selectedPackage.fileName())
                .putString(KEY_PACKAGE_NAME, selectedPackage.packageName)
                .putLong(KEY_VERSION_CODE, selectedPackage.versionCode)
                .putLong(KEY_TOTAL_BYTES, -1L)
                .remove(KEY_SEGMENTS)
                .apply();
        listener.onStarted(selectedPackage.fileName());
        beginDownload();
    }

    public void close() {
        closed = true;
        persistSegments();
        handler.removeCallbacks(progressTask);
        coordinator.shutdownNow();
        workers.shutdownNow();
    }

    private void beginDownload() {
        downloading = true;
        handler.removeCallbacks(progressTask);
        handler.post(progressTask);
        coordinator.execute(() -> {
            try {
                performDownload();
                downloading = false;
                persistSegments();
                handler.removeCallbacks(progressTask);
                handler.post(() -> {
                    if (!closed) {
                        listener.onProgress(100);
                        verifyDownloadedFile();
                    }
                });
            } catch (Exception error) {
                downloading = false;
                persistSegments();
                handler.removeCallbacks(progressTask);
                if (closed || Thread.currentThread().isInterrupted()) {
                    return;
                }
                deletePartialFiles();
                clearState();
                String detail = error.getMessage();
                handler.post(() -> {
                    if (!closed) {
                        listener.onFailed(-1, detail);
                    }
                });
            }
        });
    }

    private void performDownload() throws Exception {
        String address = state.getString(KEY_URL, "");
        File destination = new File(state.getString(KEY_PATH, ""));
        File temporary = new File(state.getString(KEY_TEMP_PATH, ""));
        Probe probe = probe(address);

        if (probe.rangeSupported && probe.length >= MIN_PARALLEL_SIZE) {
            List<Segment> segments = restoreSegments(probe.length, temporary);
            if (segments == null) {
                segments = createSegments(probe.length);
                prepareFile(temporary, probe.length);
            }
            totalBytes = probe.length;
            activeSegments = segments;
            state.edit().putLong(KEY_TOTAL_BYTES, probe.length).apply();
            persistSegments();
            downloadSegments(address, temporary, segments);
        } else {
            if (temporary.exists() && !temporary.delete()) {
                throw new IOException("cannot reset partial download");
            }
            totalBytes = probe.length;
            Segment segment = new Segment(0L, probe.length > 0L ? probe.length - 1L : Long.MAX_VALUE, 0L);
            activeSegments = Collections.singletonList(segment);
            state.edit().putLong(KEY_TOTAL_BYTES, probe.length).remove(KEY_SEGMENTS).apply();
            downloadSingle(address, temporary, segment, probe.length);
        }

        finishTemporaryFile(temporary, destination);
    }

    private void downloadSegments(String address, File destination, List<Segment> segments)
            throws Exception {
        CompletionService<Void> completion = new ExecutorCompletionService<>(workers);
        List<Future<Void>> futures = new ArrayList<>();
        for (Segment segment : segments) {
            if (segment.current <= segment.end) {
                futures.add(completion.submit(() -> {
                    downloadSegment(address, destination, segment);
                    return null;
                }));
            }
        }

        try {
            for (int index = 0; index < futures.size(); index++) {
                completion.take().get();
            }
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new IOException("parallel download failed", cause);
        } finally {
            for (Future<Void> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
        }
    }

    private void downloadSegment(String address, File destination, Segment segment)
            throws IOException {
        int failures = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        while (segment.current <= segment.end) {
            if (closed || Thread.currentThread().isInterrupted()) {
                throw new IOException("download interrupted");
            }
            HttpURLConnection connection = null;
            try {
                long requestedStart = segment.current;
                connection = open(address, "bytes=" + requestedStart + "-" + segment.end);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_PARTIAL) {
                    throw new IOException("server stopped accepting range requests");
                }
                String contentRange = connection.getHeaderField("Content-Range");
                if (!startsAt(contentRange, requestedStart)) {
                    throw new IOException("server returned an invalid Content-Range");
                }

                try (InputStream input = new BufferedInputStream(connection.getInputStream());
                     RandomAccessFile output = new RandomAccessFile(destination, "rw")) {
                    output.seek(requestedStart);
                    int count;
                    while (segment.current <= segment.end
                            && (count = input.read(buffer, 0, (int) Math.min(
                            buffer.length, segment.end - segment.current + 1L))) != -1) {
                        output.write(buffer, 0, count);
                        segment.current += count;
                        if (closed || Thread.currentThread().isInterrupted()) {
                            throw new IOException("download interrupted");
                        }
                    }
                }
                if (segment.current <= segment.end) {
                    throw new IOException("range response ended early");
                }
            } catch (IOException error) {
                failures++;
                if (closed || Thread.currentThread().isInterrupted()
                        || failures >= MAX_SEGMENT_ATTEMPTS) {
                    throw error;
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }

    private void downloadSingle(String address, File destination, Segment segment, long knownLength)
            throws IOException {
        HttpURLConnection connection = open(address, null);
        try {
            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new IOException("HTTP " + statusCode + " from " + address);
            }
            long responseLength = contentLength(connection);
            if (knownLength <= 0L && responseLength > 0L) {
                totalBytes = responseLength;
                segment.end = responseLength - 1L;
            }
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (closed || Thread.currentThread().isInterrupted()) {
                        throw new IOException("download interrupted");
                    }
                    output.write(buffer, 0, count);
                    segment.current += count;
                }
            }
            if (knownLength > 0L && segment.current != knownLength) {
                throw new IOException("downloaded file size does not match Content-Length");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static Probe probe(String address) throws IOException {
        HttpURLConnection connection = open(address, "bytes=0-0");
        try {
            int statusCode = connection.getResponseCode();
            if (statusCode == HttpURLConnection.HTTP_PARTIAL) {
                long total = totalFromContentRange(connection.getHeaderField("Content-Range"));
                if (total > 0L) {
                    return new Probe(true, total);
                }
            }
            if (statusCode >= 200 && statusCode < 300) {
                return new Probe(false, contentLength(connection));
            }
            throw new IOException("HTTP " + statusCode + " from " + address);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String address, String range) throws IOException {
        URL current = new URL(address);
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "WebViewUpdater-Android/0.1");
            if (range != null) {
                connection.setRequestProperty("Range", range);
            }
            int statusCode = connection.getResponseCode();
            if (statusCode == HttpURLConnection.HTTP_MOVED_PERM
                    || statusCode == HttpURLConnection.HTTP_MOVED_TEMP
                    || statusCode == HttpURLConnection.HTTP_SEE_OTHER
                    || statusCode == 307
                    || statusCode == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (isEmpty(location)) {
                    throw new IOException("redirect response has no Location");
                }
                current = new URL(current, location);
                continue;
            }
            return connection;
        }
        throw new IOException("too many redirects from " + address);
    }

    private List<Segment> restoreSegments(long expectedLength, File temporary) {
        if (!temporary.isFile() || temporary.length() != expectedLength
                || state.getLong(KEY_TOTAL_BYTES, -1L) != expectedLength) {
            return null;
        }
        String encoded = state.getString(KEY_SEGMENTS, "");
        if (isEmpty(encoded)) {
            return null;
        }
        String[] values = encoded.split(";");
        if (values.length != PARALLEL_CONNECTIONS) {
            return null;
        }
        List<Segment> result = new ArrayList<>();
        try {
            long expectedStart = 0L;
            for (String value : values) {
                String[] fields = value.split(",");
                if (fields.length != 3) {
                    return null;
                }
                long start = Long.parseLong(fields[0]);
                long end = Long.parseLong(fields[1]);
                long current = Long.parseLong(fields[2]);
                if (start != expectedStart || end < start || current < start || current > end + 1L) {
                    return null;
                }
                result.add(new Segment(start, end, current));
                expectedStart = end + 1L;
            }
            return expectedStart == expectedLength ? result : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static List<Segment> createSegments(long length) {
        List<Segment> result = new ArrayList<>();
        long chunk = (length + PARALLEL_CONNECTIONS - 1L) / PARALLEL_CONNECTIONS;
        for (int index = 0; index < PARALLEL_CONNECTIONS; index++) {
            long start = index * chunk;
            long end = Math.min(length - 1L, start + chunk - 1L);
            result.add(new Segment(start, end, start));
        }
        return result;
    }

    private void persistSegments() {
        List<Segment> segments = activeSegments;
        if (segments.isEmpty() || totalBytes < MIN_PARALLEL_SIZE) {
            return;
        }
        StringBuilder encoded = new StringBuilder();
        for (Segment segment : segments) {
            if (encoded.length() > 0) {
                encoded.append(';');
            }
            encoded.append(segment.start).append(',')
                    .append(segment.end).append(',')
                    .append(segment.current);
        }
        state.edit()
                .putLong(KEY_TOTAL_BYTES, totalBytes)
                .putString(KEY_SEGMENTS, encoded.toString())
                .apply();
    }

    private static long downloadedBytes(List<Segment> segments) {
        long result = 0L;
        for (Segment segment : segments) {
            result += Math.max(0L, segment.current - segment.start);
        }
        return result;
    }

    private static void prepareFile(File file, long length) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create download directory");
        }
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.setLength(length);
        }
    }

    private static void finishTemporaryFile(File temporary, File destination) throws IOException {
        if (destination.exists() && !destination.delete()) {
            throw new IOException("cannot replace downloaded APK");
        }
        if (temporary.renameTo(destination)) {
            return;
        }
        try (FileInputStream input = new FileInputStream(temporary);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        temporary.delete();
    }

    private void verifyDownloadedFile() {
        verifying = true;
        listener.onVerifying();
        String path = state.getString(KEY_PATH, "");
        String expected = state.getString(KEY_SHA256, "");
        String expectedPackageName = state.getString(KEY_PACKAGE_NAME, "");
        long expectedVersionCode = state.getLong(KEY_VERSION_CODE, 0L);
        coordinator.execute(() -> {
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

    private void deletePartialFiles() {
        String tempPath = state.getString(KEY_TEMP_PATH, "");
        if (!isEmpty(tempPath)) {
            //noinspection ResultOfMethodCallIgnored
            new File(tempPath).delete();
        }
    }

    private void clearState() {
        activeSegments = Collections.emptyList();
        totalBytes = -1L;
        state.edit().clear().apply();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
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

    private static long contentLength(HttpURLConnection connection) {
        String value = connection.getHeaderField("Content-Length");
        if (isEmpty(value)) {
            return -1L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static long totalFromContentRange(String value) {
        if (isEmpty(value)) {
            return -1L;
        }
        int slash = value.lastIndexOf('/');
        if (slash < 0 || slash == value.length() - 1) {
            return -1L;
        }
        try {
            return Long.parseLong(value.substring(slash + 1).trim());
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static boolean startsAt(String contentRange, long expectedStart) {
        if (isEmpty(contentRange)) {
            return false;
        }
        String prefix = "bytes " + expectedStart + "-";
        return contentRange.toLowerCase(Locale.US).startsWith(prefix);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class Probe {
        final boolean rangeSupported;
        final long length;

        Probe(boolean rangeSupported, long length) {
            this.rangeSupported = rangeSupported;
            this.length = length;
        }
    }

    private static final class Segment {
        final long start;
        volatile long end;
        volatile long current;

        Segment(long start, long end, long current) {
            this.start = start;
            this.end = end;
            this.current = current;
        }
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
