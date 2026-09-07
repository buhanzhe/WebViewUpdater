package com.buhanzhe.webviewupdater;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/** Minimal, read-only provider for APKs downloaded into this app's private directories. */
public final class ApkFileProvider extends ContentProvider {
    private static final String EXTERNAL = "external";
    private static final String CACHE = "cache";

    public static Uri getUriForFile(Context context, File file) {
        try {
            File canonical = file.getCanonicalFile();
            File external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            String root;
            if (external != null && isInside(external, canonical)) {
                root = EXTERNAL;
            } else {
                File cache = new File(context.getCacheDir(), "downloads");
                if (!isInside(cache, canonical)) {
                    throw new IllegalArgumentException("APK is outside the app download directory");
                }
                root = CACHE;
            }
            return new Uri.Builder()
                    .scheme("content")
                    .authority(context.getPackageName() + ".files")
                    .appendPath(root)
                    .appendPath(canonical.getName())
                    .build();
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot resolve APK path", error);
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri,
                        String[] projection,
                        String selection,
                        String[] selectionArgs,
                        String sortOrder) {
        File file;
        try {
            file = resolve(uri);
        } catch (FileNotFoundException error) {
            return null;
        }
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row.add(file.getName());
            } else if (OpenableColumns.SIZE.equals(column)) {
                row.add(file.length());
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Provider is read-only");
        }
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Provider is read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        List<String> segments = uri.getPathSegments();
        if (context == null || segments.size() != 2) {
            throw new FileNotFoundException("Invalid APK URI");
        }
        File base;
        if (EXTERNAL.equals(segments.get(0))) {
            base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        } else if (CACHE.equals(segments.get(0))) {
            base = new File(context.getCacheDir(), "downloads");
        } else {
            throw new FileNotFoundException("Unknown APK root");
        }
        if (base == null) {
            throw new FileNotFoundException("APK root is unavailable");
        }
        try {
            File file = new File(base, segments.get(1)).getCanonicalFile();
            if (!isInside(base, file) || !file.isFile()) {
                throw new FileNotFoundException("APK does not exist");
            }
            return file;
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
    }

    private static boolean isInside(File base, File child) throws IOException {
        String basePath = base.getCanonicalPath() + File.separator;
        return child.getCanonicalPath().startsWith(basePath);
    }
}
