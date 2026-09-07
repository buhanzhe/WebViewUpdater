package com.buhanzhe.webviewupdater;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.File;

public final class ApkInstaller {
    private ApkInstaller() {
    }

    public static Result install(Activity activity, File apk) {
        Uri uri = ApkFileProvider.getUriForFile(activity, apk);
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(install);
            return Result.STARTED;
        } catch (ActivityNotFoundException ignored) {
            return Result.NO_INSTALLER;
        } catch (SecurityException ignored) {
            if (Build.VERSION.SDK_INT >= 26
                    && !activity.getPackageManager().canRequestPackageInstalls()) {
                return requestInstallPermission(activity);
            }
            return Result.NO_INSTALLER;
        }
    }

    private static Result requestInstallPermission(Activity activity) {
        Intent permission = new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName()));
        try {
            activity.startActivity(permission);
            return Result.PERMISSION_REQUIRED;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return Result.NO_INSTALLER;
        }
    }

    public enum Result {
        STARTED,
        PERMISSION_REQUIRED,
        NO_INSTALLER
    }
}
