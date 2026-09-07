package com.buhanzhe.webviewupdater;

import android.app.UiModeManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public final class MainActivity extends Activity
        implements ApkDownloadController.Listener {
    private static final String INSTALL_STATE = "install_state";
    private static final String KEY_PENDING_APK = "pending_apk";

    private TextView deviceInfoText;
    private TextView webViewInfoText;
    private TextView matchTitleText;
    private TextView matchDetailText;
    private TextView proxyStatusText;
    private ProgressBar progress;
    private Button downloadButton;
    private Button refreshButton;
    private Button settingsButton;

    private DeviceInfo deviceInfo;
    private DownloadSourcePreferences sourcePreferences;
    private ConfigRepository configRepository;
    private ApkDownloadController downloadController;
    private ReleaseConfig.WebViewPackage selectedPackage;
    private String selectedAssetBaseUrl;
    private String selectedProxyPrefix;
    private int loadGeneration;
    private boolean resumedOnce;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        sourcePreferences = new DownloadSourcePreferences(this);
        downloadController = new ApkDownloadController(this, this);
        deviceInfo = DeviceDetector.detect(this);
        renderDeviceInfo();
        updateProxyStatus();
        wireActions();

        boolean restoredDownload = downloadController.restore();
        if (!restoredDownload) {
            refreshConfiguration(false);
        }
        if (isTelevision()) {
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
        tryPendingInstall();
    }

    @Override
    protected void onDestroy() {
        if (configRepository != null) {
            configRepository.close();
        }
        if (downloadController != null) {
            downloadController.close();
        }
        super.onDestroy();
    }

    private void bindViews() {
        deviceInfoText = findViewById(R.id.deviceInfoText);
        webViewInfoText = findViewById(R.id.webViewInfoText);
        matchTitleText = findViewById(R.id.matchTitleText);
        matchDetailText = findViewById(R.id.matchDetailText);
        proxyStatusText = findViewById(R.id.proxyStatusText);
        progress = findViewById(R.id.progress);
        downloadButton = findViewById(R.id.downloadButton);
        refreshButton = findViewById(R.id.refreshButton);
        settingsButton = findViewById(R.id.settingsButton);
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
        matchDetailText.setText(getString(
                R.string.matched_detail,
                selectedPackage.packageName,
                selectedPackage.abis.isEmpty() ? "universal" : TextUtils.join(", ", selectedPackage.abis),
                sdkRange,
                selectedPackage.fileName()));
        downloadButton.setEnabled(true);
        if (isTelevision() || userInitiated) {
            downloadButton.requestFocus();
        }
    }

    private void startDownload() {
        if (selectedPackage == null) {
            return;
        }
        String rawUrl = selectedPackage.resolveUrl(selectedAssetBaseUrl == null ? "" : selectedAssetBaseUrl);
        String url = DownloadSourcePreferences.applyProxy(rawUrl, selectedProxyPrefix);
        try {
            downloadController.start(url, selectedPackage);
            downloadButton.setEnabled(false);
            refreshButton.setEnabled(false);
            settingsButton.setEnabled(false);
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
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
                .setSingleChoiceItems(labels, checked, null)
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
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.custom_proxy_hint);
        input.setText(sourcePreferences.getCustomProxy());
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding / 2, padding, 0);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.proxy_custom)
                .setView(input)
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

    private void sourceChanged() {
        updateProxyStatus();
        refreshConfiguration(true);
    }

    private void updateProxyStatus() {
        proxyStatusText.setText(getString(
                R.string.proxy_status,
                sourcePreferences.displayName(this)));
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
    public void onVerifying() {
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        matchTitleText.setText(R.string.verifying);
    }

    @Override
    public void onReadyToInstall(File apk) {
        progress.setVisibility(View.GONE);
        getSharedPreferences(INSTALL_STATE, MODE_PRIVATE)
                .edit().putString(KEY_PENDING_APK, apk.getAbsolutePath()).apply();
        attemptInstall(apk);
        setActionsEnabled(true);
    }

    @Override
    public void onChecksumFailed(String detail) {
        progress.setVisibility(View.GONE);
        matchTitleText.setText(R.string.checksum_failed);
        matchTitleText.setTextColor(getColorCompat(R.color.warning));
        setActionsEnabled(true);
    }

    @Override
    public void onFailed(int reason, String detail) {
        progress.setVisibility(View.GONE);
        String message = detail == null
                ? getString(R.string.download_failed, reason)
                : getString(R.string.download_failed, reason) + "\n" + detail;
        matchTitleText.setText(message);
        matchTitleText.setTextColor(getColorCompat(R.color.warning));
        setActionsEnabled(true);
    }

    private void tryPendingInstall() {
        String path = getSharedPreferences(INSTALL_STATE, MODE_PRIVATE)
                .getString(KEY_PENDING_APK, "");
        if (path != null && !path.isEmpty()) {
            File file = new File(path);
            if (file.isFile()) {
                attemptInstall(file);
            } else {
                clearPendingInstall();
            }
        }
    }

    private void attemptInstall(File apk) {
        ApkInstaller.Result result = ApkInstaller.install(this, apk);
        if (result == ApkInstaller.Result.PERMISSION_REQUIRED) {
            Toast.makeText(this, R.string.install_permission, Toast.LENGTH_LONG).show();
        } else {
            clearPendingInstall();
            if (result == ApkInstaller.Result.NO_INSTALLER) {
                Toast.makeText(this, R.string.installer_missing, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void clearPendingInstall() {
        getSharedPreferences(INSTALL_STATE, MODE_PRIVATE).edit().clear().apply();
    }

    private void setActionsEnabled(boolean enabled) {
        downloadButton.setEnabled(enabled && selectedPackage != null);
        refreshButton.setEnabled(enabled);
        settingsButton.setEnabled(enabled);
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
