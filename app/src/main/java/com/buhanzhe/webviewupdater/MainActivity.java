package com.buhanzhe.webviewupdater;

import android.Manifest;
import android.app.UiModeManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity implements ApkDownloadController.Listener {
    private static final int STORAGE_PERMISSION_REQUEST = 41;
    private static final int FILE_PICKER_REQUEST = 42;
    private static final String INSTALL_STATE = "install_state";
    private static final String KEY_PENDING_PACKAGE = "package";
    private static final String KEY_PENDING_VERSION_NAME = "version_name";
    private static final String KEY_PENDING_VERSION_CODE = "version_code";
    private static final String KEY_PENDING_PATH = "path";
    private static final String KEY_PENDING_URI = "uri";
    private static final String KEY_PENDING_PHASE = "phase";
    private static final String PHASE_PERMISSION = "permission";
    private static final String PHASE_INSTALLER = "installer";
    private static final String PHASE_FILE_MANAGER = "file_manager";
    private static final String PHASE_FILE_INSTALLER = "file_installer";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextView deviceInfoText;
    private TextView webViewInfoText;
    private TextView matchTitleText;
    private TextView matchDetailText;
    private ProgressBar progress;
    private Button downloadButton;
    private Button refreshButton;
    private Button settingsButton;

    private DeviceInfo deviceInfo;
    private DownloadSourcePreferences sourcePreferences;
    private ConfigRepository configRepository;
    private ApkDownloadController downloadController;
    private ReleaseConfig.WebViewPackage selectedPackage;
    private boolean currentWebViewIsCurrent;
    private String selectedAssetBaseUrl;
    private String selectedProxyPrefix;
    private int loadGeneration;
    private boolean resumedOnce;
    private boolean downloadAfterPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean television = isTelevision();
        setRequestedOrientation(television
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.activity_main);
        configureSystemBars();

        bindViews();
        sourcePreferences = new DownloadSourcePreferences(this);
        downloadController = new ApkDownloadController(this, this);
        deviceInfo = DeviceDetector.detect(this);
        renderDeviceInfo();
        wireActions();

        if (!downloadController.restore()) {
            refreshConfiguration(false);
        }
        if (television) {
            refreshButton.requestFocus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!resumedOnce) {
            resumedOnce = true;
            return;
        }
        mainHandler.postDelayed(this::checkPendingInstallResult, 800L);
    }

    @Override
    protected void onDestroy() {
        if (configRepository != null) {
            configRepository.close();
        }
        if (downloadController != null) {
            downloadController.close();
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void bindViews() {
        deviceInfoText = findViewById(R.id.deviceInfoText);
        webViewInfoText = findViewById(R.id.webViewInfoText);
        matchTitleText = findViewById(R.id.matchTitleText);
        matchDetailText = findViewById(R.id.matchDetailText);
        progress = findViewById(R.id.progress);
        downloadButton = findViewById(R.id.downloadButton);
        refreshButton = findViewById(R.id.refreshButton);
        settingsButton = findViewById(R.id.settingsButton);
    }

    private void configureSystemBars() {
        if (Build.VERSION.SDK_INT < 23) {
            getWindow().setStatusBarColor(getColorCompat(R.color.primary_dark));
            return;
        }

        getWindow().setStatusBarColor(Color.TRANSPARENT);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            getWindow().getInsetsController().setSystemBarsAppearance(
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        }

        View spacer = findViewById(R.id.statusBarSpacer);
        spacer.setOnApplyWindowInsetsListener((view, insets) -> {
            int inset = insets.getSystemWindowInsetTop();
            android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params.height != inset) {
                params.height = inset;
                view.setLayoutParams(params);
            }
            return insets;
        });
        spacer.requestApplyInsets();
    }

    private void wireActions() {
        refreshButton.setOnClickListener(view -> refreshConfiguration(true));
        settingsButton.setOnClickListener(view -> showSourceDialog());
        downloadButton.setOnClickListener(view -> startDownload());
        addTvFocusEffect(downloadButton);
        addTvFocusEffect(refreshButton);
        addTvFocusEffect(settingsButton);
    }

    private void renderDeviceInfo() {
        String deviceText = getString(R.string.android_version) + "："
                + deviceInfo.androidRelease + " (API " + deviceInfo.sdkInt + ")\n"
                + getString(R.string.device_abis) + "："
                + joinOrUnknown(deviceInfo.supportedAbis) + "\n"
                + getString(R.string.process_abi) + "："
                + emptyAsUnknown(deviceInfo.processAbi);
        deviceInfoText.setText(deviceText);

        PackageInfo provider = deviceInfo.webViewPackage;
        if (provider == null) {
            webViewInfoText.setText(R.string.provider_missing);
            return;
        }
        String webViewText = getString(R.string.webview_provider) + "："
                + provider.packageName + "\n"
                + getString(R.string.webview_version) + "："
                + emptyAsUnknown(provider.versionName) + " (" + deviceInfo.webViewVersionCode() + ")\n"
                + getString(R.string.webview_arch) + "："
                + joinOrUnknown(deviceInfo.webViewAbis) + "\n"
                + getString(R.string.webview_multi_arch) + "："
                + getString(deviceInfo.webViewMultiArch ? R.string.yes : R.string.no);
        webViewInfoText.setText(webViewText);
    }

    private void refreshConfiguration(boolean userInitiated) {
        final int generation = ++loadGeneration;
        selectedPackage = null;
        currentWebViewIsCurrent = false;
        downloadButton.setText(R.string.download);
        downloadButton.setEnabled(false);
        refreshButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        matchTitleText.setText(R.string.checking);
        matchDetailText.setText("");

        if (configRepository != null) {
            configRepository.close();
        }
        configRepository = new ConfigRepository(sourcePreferences);
        configRepository.load(new ConfigRepository.Callback() {
            @Override
            public void onLoaded(ConfigRepository.ResolvedConfig result) {
                if (generation != loadGeneration || isFinishing()) {
                    return;
                }
                progress.setVisibility(View.GONE);
                refreshButton.setEnabled(true);
                selectedAssetBaseUrl = result.config.assetBaseUrl;
                selectedProxyPrefix = result.proxyPrefix;
                selectedPackage = result.config.findBestMatch(deviceInfo);
                currentWebViewIsCurrent = result.config
                        .isDeviceAtLeastRecommendedVersion(deviceInfo);
                renderMatch(result.sourceName, userInitiated);
            }

            @Override
            public void onError(Exception error) {
                if (generation != loadGeneration || isFinishing()) {
                    return;
                }
                progress.setVisibility(View.GONE);
                refreshButton.setEnabled(true);
                matchTitleText.setText(getString(
                        R.string.config_error,
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
                matchTitleText.setTextColor(getColorCompat(R.color.warning));
            }
        });
    }

    private void renderMatch(String sourceName, boolean userInitiated) {
        if (currentWebViewIsCurrent) {
            selectedPackage = null;
            matchTitleText.setText(R.string.webview_already_latest);
            matchTitleText.setTextColor(getColorCompat(R.color.success));
            matchDetailText.setText(getString(R.string.config_source, sourceName));
            downloadButton.setEnabled(false);
            return;
        }

        if (selectedPackage == null) {
            matchTitleText.setText(R.string.no_match);
            matchTitleText.setTextColor(getColorCompat(R.color.warning));
            matchDetailText.setText(getString(R.string.config_source, sourceName));
            return;
        }

        matchTitleText.setText(getString(
                R.string.matched_version,
                selectedPackage.versionName,
                selectedPackage.channel));
        matchTitleText.setTextColor(getColorCompat(R.color.success));
        String sdkRange = selectedPackage.maxSdk == Integer.MAX_VALUE
                ? selectedPackage.minSdk + "+"
                : selectedPackage.minSdk + "–" + selectedPackage.maxSdk;
        boolean alreadyDownloaded = downloadController.hasExisting(selectedPackage);
        String detail = getString(
                R.string.matched_detail,
                selectedPackage.packageName,
                selectedPackage.abis.isEmpty() ? "universal" : TextUtils.join(", ", selectedPackage.abis),
                sdkRange,
                selectedPackage.fileName());
        if (alreadyDownloaded) {
            detail += "\n" + getString(R.string.apk_ready_to_install);
        }
        matchDetailText.setText(detail);
        downloadButton.setText(alreadyDownloaded ? R.string.install : R.string.download);
        downloadButton.setEnabled(true);
        if (isTelevision() || userInitiated) {
            downloadButton.requestFocus();
        }
    }

    private void startDownload() {
        if (selectedPackage == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            downloadAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION_REQUEST);
            return;
        }
        startDownloadWithPermission();
    }

    private void startDownloadWithPermission() {
        if (selectedPackage == null) {
            return;
        }
        try {
            downloadController.start(selectedDownloadUrl(), selectedPackage);
            setActionsEnabled(false);
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String selectedDownloadUrl() {
        String rawUrl = selectedPackage.resolveUrl(
                selectedAssetBaseUrl == null ? "" : selectedAssetBaseUrl);
        return DownloadSourcePreferences.applyProxy(rawUrl, selectedProxyPrefix);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != STORAGE_PERMISSION_REQUEST || !downloadAfterPermission) {
            return;
        }
        downloadAfterPermission = false;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startDownloadWithPermission();
        } else {
            Toast.makeText(this, R.string.storage_permission_required, Toast.LENGTH_LONG).show();
        }
    }

    private void showInstallCommand(ApkDownloadController.DownloadRecord record, int message) {
        String command = "adb shell pm install -r \"" + record.file.getAbsolutePath() + "\"";
        EditText commandText = new EditText(this);
        commandText.setText(command);
        commandText.setTextIsSelectable(true);
        commandText.setKeyListener(null);
        commandText.setSingleLine(false);
        commandText.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout content = dialogContent();
        TextView help = new TextView(this);
        help.setText(message);
        help.setTextColor(getColorCompat(R.color.text_secondary));
        content.addView(help, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams commandParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        commandParams.topMargin = dp(10);
        content.addView(commandText, commandParams);

        new AlertDialog.Builder(this)
                .setTitle(R.string.install_command_title)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.copy_command, (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(
                                getString(R.string.install_command_title), command));
                        Toast.makeText(this, R.string.install_command_copied,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void showSourceDialog() {
        final String[] modes = {
                DownloadSourcePreferences.MODE_AUTO,
                DownloadSourcePreferences.MODE_PROXY_COM,
                DownloadSourcePreferences.MODE_DIRECT,
                DownloadSourcePreferences.MODE_CUSTOM
        };
        final String[] labels = {
                getString(R.string.proxy_auto),
                getString(R.string.proxy_one),
                getString(R.string.proxy_direct),
                getString(R.string.proxy_custom)
        };
        int checked = 0;
        String current = sourcePreferences.getMode();
        for (int index = 0; index < modes.length; index++) {
            if (modes[index].equals(current)) {
                checked = index;
                break;
            }
        }

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.download_settings)
                .setSingleChoiceItems(labels, checked, (sourceDialog, selected) -> {
                    if (DownloadSourcePreferences.MODE_CUSTOM.equals(modes[selected])) {
                        sourceDialog.dismiss();
                        showCustomProxyDialog();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    int selected = dialog.getListView().getCheckedItemPosition();
                    if (selected < 0) {
                        return;
                    }
                    String mode = modes[selected];
                    dialog.dismiss();
                    if (DownloadSourcePreferences.MODE_CUSTOM.equals(mode)) {
                        showCustomProxyDialog();
                    } else {
                        sourcePreferences.save(mode, sourcePreferences.getCustomProxy());
                        sourceChanged();
                    }
                }));
        dialog.show();
    }

    private void showCustomProxyDialog() {
        LinearLayout content = dialogContent();
        TextView help = new TextView(this);
        help.setText(R.string.custom_proxy_help);
        help.setTextColor(getColorCompat(R.color.text_secondary));
        content.addView(help, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.custom_proxy_hint);
        input.setText(sourcePreferences.getCustomProxy());
        input.setMinHeight(dp(52));
        input.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = dp(8);
        content.addView(input, inputParams);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.proxy_custom)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String value = input.getText() == null ? "" : input.getText().toString().trim();
                    if (!DownloadSourcePreferences.isValidCustomProxy(value)) {
                        input.setError(getString(R.string.invalid_custom_proxy));
                        input.requestFocus();
                        return;
                    }
                    sourcePreferences.save(DownloadSourcePreferences.MODE_CUSTOM, value);
                    dialog.dismiss();
                    sourceChanged();
                }));
        dialog.show();
    }

    private LinearLayout dialogContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), 0);
        return content;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void sourceChanged() {
        refreshConfiguration(true);
    }

    @Override
    public void onStarted(String fileName) {
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        matchTitleText.setText(getString(R.string.download_started, fileName));
    }

    @Override
    public void onProgress(int percent) {
        progress.setVisibility(View.VISIBLE);
        if (percent >= 0) {
            progress.setIndeterminate(false);
            progress.setProgress(percent);
            matchTitleText.setText(getString(R.string.download_progress, percent));
        } else {
            progress.setIndeterminate(true);
        }
    }

    @Override
    public void onVerifying(boolean existing) {
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        matchTitleText.setText(existing
                ? R.string.checking_existing_apk : R.string.verifying);
    }

    @Override
    public void onReady(ApkDownloadController.DownloadRecord record, boolean existing) {
        progress.setVisibility(View.GONE);
        setActionsEnabled(true);
        attemptDirectInstall(record);
    }

    @Override
    public void onValidationFailed(String detail, boolean existing) {
        progress.setVisibility(View.GONE);
        matchTitleText.setText(existing
                ? R.string.existing_apk_invalid : R.string.checksum_failed);
        matchTitleText.setTextColor(getColorCompat(R.color.warning));
        matchDetailText.setText(detail == null ? "" : detail);
        setActionsEnabled(true);
    }

    @Override
    public void onFailed(int reason, String detail) {
        progress.setVisibility(View.GONE);
        String message = getString(R.string.download_failed, reason);
        matchTitleText.setText(detail == null ? message : message + "\n" + detail);
        matchTitleText.setTextColor(getColorCompat(R.color.warning));
        setActionsEnabled(true);
    }

    private void attemptDirectInstall(ApkDownloadController.DownloadRecord record) {
        savePendingInstall(record);
        if (record.contentUri == null) {
            showFileManagerPrompt(record);
            return;
        }
        if (Build.VERSION.SDK_INT >= 26
                && !getPackageManager().canRequestPackageInstalls()) {
            requestInstallPermission(record);
            return;
        }
        startPackageInstaller(record, PHASE_INSTALLER);
    }

    private void savePendingInstall(ApkDownloadController.DownloadRecord record) {
        getSharedPreferences(INSTALL_STATE, MODE_PRIVATE).edit()
                .putString(KEY_PENDING_PACKAGE, record.packageName)
                .putString(KEY_PENDING_VERSION_NAME, record.versionName)
                .putLong(KEY_PENDING_VERSION_CODE, record.versionCode)
                .putString(KEY_PENDING_PATH, record.file.getAbsolutePath())
                .putString(KEY_PENDING_URI,
                        record.contentUri == null ? "" : record.contentUri.toString())
                .apply();
    }

    private void requestInstallPermission(ApkDownloadController.DownloadRecord record) {
        getSharedPreferences(INSTALL_STATE, MODE_PRIVATE).edit()
                .putString(KEY_PENDING_PHASE, PHASE_PERMISSION)
                .apply();
        Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(settings);
        } catch (ActivityNotFoundException | SecurityException error) {
            showFileManagerPrompt(record);
        }
    }

    private void startPackageInstaller(ApkDownloadController.DownloadRecord record,
                                       String phase) {
        getSharedPreferences(INSTALL_STATE, MODE_PRIVATE).edit()
                .putString(KEY_PENDING_PHASE, phase)
                .apply();
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(record.contentUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(install);
        } catch (ActivityNotFoundException | SecurityException error) {
            if (PHASE_FILE_INSTALLER.equals(phase)) {
                clearPendingInstall();
                showInstallCommand(record, R.string.direct_install_unavailable);
            } else {
                showFileManagerPrompt(record);
            }
        }
    }

    private void showFileManagerPrompt(ApkDownloadController.DownloadRecord record) {
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.file_manager_install_title)
                .setMessage(getString(R.string.file_manager_install_message, record.fileName))
                .setNegativeButton(R.string.cancel, (ignored, which) -> clearPendingInstall())
                .setPositiveButton(R.string.open_file_manager, (ignored, which) ->
                        openFileManager(record))
                .create();
        dialog.setOnCancelListener(ignored -> clearPendingInstall());
        dialog.show();
    }

    private void openFileManager(ApkDownloadController.DownloadRecord record) {
        savePendingInstall(record);
        getSharedPreferences(INSTALL_STATE, MODE_PRIVATE).edit()
                .putString(KEY_PENDING_PHASE, PHASE_FILE_MANAGER)
                .apply();

        Intent directory = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(
                        Uri.parse("content://com.android.externalstorage.documents/"
                                + "document/primary%3ADownload"),
                        "vnd.android.document/directory")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            String[] documentProviders = {
                    "com.google.android.documentsui",
                    "com.android.documentsui"
            };
            for (String packageName : documentProviders) {
                directory.setPackage(packageName);
                if (directory.resolveActivity(getPackageManager()) != null) {
                    showFileManagerHint(record);
                    startActivity(directory);
                    return;
                }
            }

            String[] fileManagers = {
                    "com.android.fileexplorer",
                    "com.google.android.apps.nbu.files",
                    "com.sec.android.app.myfiles",
                    "com.huawei.hidisk",
                    "com.asus.filemanager"
            };
            for (String packageName : fileManagers) {
                Intent launcher = getPackageManager().getLaunchIntentForPackage(packageName);
                if (launcher != null) {
                    showFileManagerHint(record);
                    startActivity(launcher);
                    return;
                }
            }

            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/vnd.android.package-archive");
            if (picker.resolveActivity(getPackageManager()) != null) {
                showFileManagerHint(record);
                startActivityForResult(picker, FILE_PICKER_REQUEST);
                return;
            }
        } catch (ActivityNotFoundException | SecurityException error) {
            // The ADB command below is the final fallback.
        }
        clearPendingInstall();
        showInstallCommand(record, R.string.file_manager_unavailable);
    }

    private void showFileManagerHint(ApkDownloadController.DownloadRecord record) {
        Toast.makeText(this,
                getString(R.string.file_manager_install_hint, record.fileName),
                Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_PICKER_REQUEST) {
            return;
        }
        ApkDownloadController.DownloadRecord record = recordFromPending();
        Uri selected = data == null ? null : data.getData();
        if (resultCode != RESULT_OK || selected == null || record == null) {
            clearPendingInstall();
            if (record != null) {
                showInstallCommand(record, R.string.file_manager_not_completed);
            }
            return;
        }
        ApkDownloadController.DownloadRecord selectedRecord =
                new ApkDownloadController.DownloadRecord(
                        record.file,
                        record.fileName,
                        record.sha256,
                        record.packageName,
                        record.versionName,
                        record.versionCode,
                        selected);
        getSharedPreferences(INSTALL_STATE, MODE_PRIVATE).edit()
                .putString(KEY_PENDING_URI, selected.toString())
                .apply();
        startPackageInstaller(selectedRecord, PHASE_FILE_INSTALLER);
    }

    private void checkPendingInstallResult() {
        android.content.SharedPreferences pending = getSharedPreferences(
                INSTALL_STATE, MODE_PRIVATE);
        String packageName = pending.getString(KEY_PENDING_PACKAGE, "");
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        String expectedVersionName = pending.getString(KEY_PENDING_VERSION_NAME, "");
        long expectedVersionCode = pending.getLong(KEY_PENDING_VERSION_CODE, 0L);
        String phase = pending.getString(KEY_PENDING_PHASE, "");

        ApkDownloadController.DownloadRecord record = recordFromPending();
        if (record == null) {
            clearPendingInstall();
            return;
        }

        if (PHASE_PERMISSION.equals(phase)) {
            if (Build.VERSION.SDK_INT >= 26
                    && getPackageManager().canRequestPackageInstalls()) {
                startPackageInstaller(record, PHASE_INSTALLER);
            } else {
                showFileManagerPrompt(record);
            }
            return;
        }

        boolean installed = false;
        try {
            //noinspection deprecation
            PackageInfo current = getPackageManager().getPackageInfo(packageName, 0);
            boolean versionNameMatches = !TextUtils.isEmpty(expectedVersionName)
                    && ReleaseConfig.compareVersionNames(
                    current.versionName == null ? "" : current.versionName,
                    expectedVersionName) >= 0;
            boolean versionCodeMatches = expectedVersionCode > 0L
                    && packageVersionCode(current) >= expectedVersionCode;
            installed = versionNameMatches || versionCodeMatches;
        } catch (PackageManager.NameNotFoundException ignored) {
            // The command fallback below remains available.
        }
        clearPendingInstall();
        if (installed) {
            deviceInfo = DeviceDetector.detect(this);
            renderDeviceInfo();
            refreshConfiguration(false);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.install_success_title)
                    .setMessage(getString(R.string.install_success_message, expectedVersionName))
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }

        if (PHASE_FILE_MANAGER.equals(phase) || PHASE_FILE_INSTALLER.equals(phase)) {
            showInstallCommand(record, R.string.file_manager_not_completed);
        } else {
            showFileManagerPrompt(record);
        }
    }

    private ApkDownloadController.DownloadRecord recordFromPending() {
        android.content.SharedPreferences pending = getSharedPreferences(
                INSTALL_STATE, MODE_PRIVATE);
        String packageName = pending.getString(KEY_PENDING_PACKAGE, "");
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }
        String path = pending.getString(KEY_PENDING_PATH, "");
        String uri = pending.getString(KEY_PENDING_URI, "");
        return new ApkDownloadController.DownloadRecord(
                new java.io.File(path == null ? "" : path),
                path == null ? "" : new java.io.File(path).getName(),
                "",
                packageName,
                pending.getString(KEY_PENDING_VERSION_NAME, ""),
                pending.getLong(KEY_PENDING_VERSION_CODE, 0L),
                TextUtils.isEmpty(uri) ? null : Uri.parse(uri));
    }

    private void clearPendingInstall() {
        getSharedPreferences(INSTALL_STATE, MODE_PRIVATE).edit().clear().apply();
    }

    private void setActionsEnabled(boolean enabled) {
        downloadButton.setEnabled(enabled && selectedPackage != null);
        refreshButton.setEnabled(enabled);
        settingsButton.setEnabled(enabled);
    }

    private static long packageVersionCode(PackageInfo packageInfo) {
        if (Build.VERSION.SDK_INT >= 28) {
            return packageInfo.getLongVersionCode();
        }
        //noinspection deprecation
        return packageInfo.versionCode;
    }

    private String joinOrUnknown(java.util.List<String> values) {
        return values == null || values.isEmpty()
                ? getString(R.string.unknown)
                : TextUtils.join(", ", values);
    }

    private String emptyAsUnknown(String value) {
        return value == null || value.trim().isEmpty() ? getString(R.string.unknown) : value;
    }

    private boolean isTelevision() {
        UiModeManager manager = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        return manager != null
                && manager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private void addTvFocusEffect(View view) {
        view.setOnFocusChangeListener((target, focused) -> target.animate()
                .scaleX(focused ? 1.025f : 1f)
                .scaleY(focused ? 1.025f : 1f)
                .translationZ(focused ? 8f : 0f)
                .setDuration(120L)
                .start());
    }

    private int getColorCompat(int colorResource) {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            return getColor(colorResource);
        }
        //noinspection deprecation
        return getResources().getColor(colorResource);
    }
}
