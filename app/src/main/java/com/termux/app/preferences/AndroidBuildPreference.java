package com.termux.app.preferences;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.termux.R;
import com.termux.app.build.AndroidBuildConfig;
import com.termux.app.build.AndroidBuildManager;
import com.termux.app.build.BuildLogDialog;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileWriter;

/**
 * Settings card for configuring both Local On-Device (Native Termux ARM64)
 * and Cloud Build (GitHub Actions / Remote Builder), including direct buttons
 * to download the local toolchain, generate an Android template, and configure Cloud Build.
 */
public class AndroidBuildPreference extends Preference {

    private final AndroidBuildManager buildManager;
    private TextView tvLocalStatus;
    private TextView tvCloudStatus;
    private TextView tvLocalBadge;
    private TextView tvCloudBadge;
    private TextView tvAdbStatus;
    private TextView tvAdbBadge;

    public AndroidBuildPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_android_build);
        this.buildManager = new AndroidBuildManager(context);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);

        Spinner modeSpinner = (Spinner) holder.findViewById(R.id.spinner_android_build_mode);
        tvLocalStatus = (TextView) holder.findViewById(R.id.tv_local_build_status);
        tvCloudStatus = (TextView) holder.findViewById(R.id.tv_cloud_build_status);
        tvLocalBadge = (TextView) holder.findViewById(R.id.tv_local_build_badge);
        tvCloudBadge = (TextView) holder.findViewById(R.id.tv_cloud_build_badge);
        tvAdbStatus = (TextView) holder.findViewById(R.id.tv_adb_status);
        tvAdbBadge = (TextView) holder.findViewById(R.id.tv_adb_badge);
        Button btnDownloadLocal = (Button) holder.findViewById(R.id.btn_download_local_toolchain);
        Button btnCreateTemplate = (Button) holder.findViewById(R.id.btn_create_android_template);
        Button btnConfigureCloud = (Button) holder.findViewById(R.id.btn_configure_cloud_build);
        Button btnInstallAdb = (Button) holder.findViewById(R.id.btn_install_adb);
        Button btnWirelessAdb = (Button) holder.findViewById(R.id.btn_wireless_adb_connect);

        String[] modeLabels = {
                "Local On-Device (Native Termux ARM64)",
                "Cloud Build (GitHub Actions / Remote)",
                "Auto (Local default, Cloud for Compose/NDK)"
        };
        final String[] modeValues = {
                AndroidBuildConfig.MODE_LOCAL,
                AndroidBuildConfig.MODE_CLOUD,
                AndroidBuildConfig.MODE_AUTO
        };

        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(
                getContext(), android.R.layout.simple_spinner_item, modeLabels);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modeAdapter);

        String currentMode = buildManager.getBuildMode();
        int idx = 0;
        for (int i = 0; i < modeValues.length; i++) {
            if (modeValues[i].equals(currentMode)) {
                idx = i;
                break;
            }
        }
        modeSpinner.setSelection(idx);
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < modeValues.length) {
                    buildManager.setBuildMode(modeValues[position]);
                    syncWorkspaceDocsInBackground();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        refreshStatuses();

        if (btnDownloadLocal != null) {
            btnDownloadLocal.setOnClickListener(v -> {
                Activity activity = findActivity(getContext());
                if (activity != null) {
                    BuildLogDialog.runToolchainDownload(activity, buildManager, this::refreshStatuses);
                } else {
                    Toast.makeText(getContext(), "Open Settings from the app to download.", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnCreateTemplate != null) {
            btnCreateTemplate.setOnClickListener(v -> showCreateTemplateDialog());
        }

        if (btnConfigureCloud != null) {
            btnConfigureCloud.setOnClickListener(v -> showCloudConfigDialog());
        }

        if (btnInstallAdb != null) {
            btnInstallAdb.setOnClickListener(v -> {
                Activity activity = findActivity(getContext());
                if (activity != null) {
                    String cmd = AndroidBuildConfig.buildAdbInstallCommand(
                            TermuxConstants.TERMUX_PREFIX_DIR_PATH);
                    BuildLogDialog.runShellProgressTask(
                            activity,
                            buildManager,
                            "Installing ADB (android-tools)",
                            "Preparing android-tools installation...",
                            "ADB installed for Termux & Ubuntu!",
                            "ADB Ready!",
                            cmd,
                            new File(TermuxConstants.TERMUX_HOME_DIR_PATH),
                            this::refreshStatuses);
                }
            });
        }

        if (btnWirelessAdb != null) {
            btnWirelessAdb.setOnClickListener(v -> showWirelessAdbDialog());
        }
    }

    private void refreshStatuses() {
        new Thread(() -> {
            AndroidBuildManager.ToolchainStatus local = buildManager.checkLocalToolchain();
            String cloud = buildManager.getCloudSummary();
            boolean cloudReady = buildManager.isCloudConfigured();
            boolean adbReady = AndroidBuildConfig.isAdbInstalled(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            syncWorkspaceDocsLocked(local.isReady(), cloud);
            Activity activity = findActivity(getContext());
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    if (tvLocalStatus != null) tvLocalStatus.setText(local.summary());
                    if (tvCloudStatus != null) tvCloudStatus.setText(cloud);
                    if (tvLocalBadge != null) {
                        tvLocalBadge.setText(local.isReady() ? "✓ Ready" : "Missing");
                        tvLocalBadge.setTextColor(local.isReady() ? 0xFF4ADE80 : 0xFFFBBF24);
                        tvLocalBadge.setBackgroundResource(local.isReady()
                                ? R.drawable.bg_badge_installed : R.drawable.bg_badge_missing);
                    }
                    if (tvCloudBadge != null) {
                        tvCloudBadge.setText(cloudReady ? "✓ Connected" : "Optional");
                        tvCloudBadge.setTextColor(cloudReady ? 0xFF4ADE80 : 0xFF9CA3AF);
                        tvCloudBadge.setBackgroundResource(cloudReady
                                ? R.drawable.bg_badge_installed : R.drawable.bg_badge_missing);
                    }
                    if (tvAdbStatus != null) {
                        tvAdbStatus.setText(adbReady
                                ? "ADB installed — tap Wireless Connect to pair Developer Options"
                                : "Not installed — tap Install adb for logcat & wireless debug");
                    }
                    if (tvAdbBadge != null) {
                        tvAdbBadge.setText(adbReady ? "✓ Ready" : "Optional");
                        tvAdbBadge.setTextColor(adbReady ? 0xFF4ADE80 : 0xFF9CA3AF);
                        tvAdbBadge.setBackgroundResource(adbReady
                                ? R.drawable.bg_badge_installed : R.drawable.bg_badge_missing);
                    }
                });
            }
        }, "build-status-check").start();
    }

    private void syncWorkspaceDocsInBackground() {
        new Thread(() -> {
            AndroidBuildManager.ToolchainStatus local = buildManager.checkLocalToolchain();
            syncWorkspaceDocsLocked(local.isReady(), buildManager.getCloudSummary());
        }, "sync-agent-md").start();
    }

    private void syncWorkspaceDocsLocked(boolean localReady, String cloudSummary) {
        try {
            SharedPreferences wsPrefs = getContext().getSharedPreferences("ZeroCodePrefs", Context.MODE_PRIVATE);
            String ws = wsPrefs.getString("workspace_path", TermuxConstants.TERMUX_HOME_DIR_PATH);
            if (ws != null) {
                AndroidBuildConfig.syncWorkspaceAgentDocs(
                        new File(ws),
                        TermuxConstants.TERMUX_PREFIX_DIR_PATH,
                        buildManager.getBuildMode(),
                        localReady,
                        cloudSummary);
            }
        } catch (Exception ignored) {
        }
    }

    private void showCreateTemplateDialog() {
        Activity activity = findActivity(getContext());
        if (activity == null) return;

        SharedPreferences wsPrefs = activity.getSharedPreferences("ZeroCodePrefs", Context.MODE_PRIVATE);
        String savedWs = wsPrefs.getString("workspace_path", TermuxConstants.TERMUX_HOME_DIR_PATH + "/MyAndroidApp");
        File defaultDir = new File(savedWs);

        EditText etPath = new EditText(activity);
        etPath.setText(defaultDir.getAbsolutePath());
        etPath.setPadding(40, 24, 40, 24);
        etPath.setTextColor(0xFFFFFFFF);
        etPath.setHintTextColor(0xFF888888);

        new AlertDialog.Builder(activity)
                .setTitle("Create Pre-Configured Android Template")
                .setMessage("Creates compileSdk 34 + AndroidX + ARM64 aapt2 + Cloud workflow files in:")
                .setView(etPath)
                .setPositiveButton("Create", (d, w) -> {
                    File target = new File(etPath.getText().toString().trim());
                    try {
                        String name = target.getName().isEmpty() ? "MyAndroidApp" : target.getName();
                        String pkg = "com.example." + name.toLowerCase().replaceAll("[^a-z0-9]", "");
                        if (pkg.endsWith(".")) pkg = pkg + "app";
                        AndroidBuildConfig.createPreconfiguredTemplate(
                                target, name, pkg, TermuxConstants.TERMUX_PREFIX_DIR_PATH);
                        wsPrefs.edit().putString("workspace_path", target.getAbsolutePath()).apply();
                        Toast.makeText(activity, "Android template created in " + target.getName(), Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(activity, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showCloudConfigDialog() {
        Activity activity = findActivity(getContext());
        if (activity == null) return;

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_cloud_build_config, null);
        Spinner spProvider = view.findViewById(R.id.spinner_cloud_provider);
        View layoutGithub = view.findViewById(R.id.layout_github_fields);
        View layoutRemote = view.findViewById(R.id.layout_remote_fields);

        EditText etRepo = view.findViewById(R.id.et_gh_repo);
        EditText etToken = view.findViewById(R.id.et_gh_token);
        EditText etWorkflow = view.findViewById(R.id.et_gh_workflow);
        EditText etBranch = view.findViewById(R.id.et_gh_branch);
        Button btnWriteYaml = view.findViewById(R.id.btn_write_gh_workflow);

        EditText etRemoteUrl = view.findViewById(R.id.et_remote_url);
        EditText etRemoteToken = view.findViewById(R.id.et_remote_token);

        String[] providers = {"GitHub Actions", "Custom Remote Builder"};
        final String[] providerValues = {
                AndroidBuildConfig.CLOUD_PROVIDER_GITHUB,
                AndroidBuildConfig.CLOUD_PROVIDER_REMOTE
        };
        ArrayAdapter<String> pAdapter = new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_item, providers);
        pAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spProvider.setAdapter(pAdapter);

        boolean isRemote = AndroidBuildConfig.CLOUD_PROVIDER_REMOTE.equals(buildManager.getCloudProvider());
        spProvider.setSelection(isRemote ? 1 : 0);
        layoutGithub.setVisibility(isRemote ? View.GONE : View.VISIBLE);
        layoutRemote.setVisibility(isRemote ? View.VISIBLE : View.GONE);

        spProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                boolean remote = pos == 1;
                layoutGithub.setVisibility(remote ? View.GONE : View.VISIBLE);
                layoutRemote.setVisibility(remote ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        etRepo.setText(buildManager.getGitHubRepo());
        etToken.setText(buildManager.getGitHubToken());
        etWorkflow.setText(buildManager.getGitHubWorkflow());
        etBranch.setText(buildManager.getGitHubBranch());
        etRemoteUrl.setText(buildManager.getRemoteBuilderUrl());
        etRemoteToken.setText(buildManager.getRemoteBuilderToken());

        btnWriteYaml.setOnClickListener(v -> {
            SharedPreferences wsPrefs = activity.getSharedPreferences("ZeroCodePrefs", Context.MODE_PRIVATE);
            String ws = wsPrefs.getString("workspace_path", null);
            if (ws == null || !new File(ws).isDirectory()) {
                Toast.makeText(activity, "Open a workspace folder first.", Toast.LENGTH_SHORT).show();
                return;
            }
            String wfName = etWorkflow.getText().toString().trim();
            if (wfName.isEmpty()) wfName = "android-build.yml";
            File wfFile = new File(ws, ".github/workflows/" + wfName);
            File parent = wfFile.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileWriter fw = new FileWriter(wfFile)) {
                fw.write(AndroidBuildConfig.buildGitHubWorkflowYaml());
                Toast.makeText(activity, "Saved .github/workflows/" + wfName, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(activity, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        new AlertDialog.Builder(activity)
                .setTitle("Configure Cloud Build")
                .setView(view)
                .setPositiveButton("Save", (d, w) -> {
                    int pos = spProvider.getSelectedItemPosition();
                    buildManager.setCloudProvider(providerValues[pos == 1 ? 1 : 0]);
                    buildManager.setGitHubRepo(etRepo.getText().toString());
                    buildManager.setGitHubToken(etToken.getText().toString());
                    buildManager.setGitHubWorkflow(etWorkflow.getText().toString());
                    buildManager.setGitHubBranch(etBranch.getText().toString());
                    buildManager.setRemoteBuilderUrl(etRemoteUrl.getText().toString());
                    buildManager.setRemoteBuilderToken(etRemoteToken.getText().toString());
                    refreshStatuses();
                    Toast.makeText(activity, "Cloud Build configuration saved.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showWirelessAdbDialog() {
        Activity activity = findActivity(getContext());
        if (activity == null) return;

        android.widget.LinearLayout box = new android.widget.LinearLayout(activity);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 12);

        EditText etPairPort = new EditText(activity);
        etPairPort.setHint("Pairing Port (optional, e.g. 37123)");
        etPairPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etPairPort.setTextColor(0xFFFFFFFF);
        etPairPort.setHintTextColor(0xFF888888);
        box.addView(etPairPort);

        EditText etPairCode = new EditText(activity);
        etPairCode.setHint("6-digit Pairing Code (optional)");
        etPairCode.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etPairCode.setTextColor(0xFFFFFFFF);
        etPairCode.setHintTextColor(0xFF888888);
        box.addView(etPairCode);

        EditText etConnectPort = new EditText(activity);
        etConnectPort.setHint("Wireless Debugging Connect Port (e.g. 41567)");
        etConnectPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etConnectPort.setTextColor(0xFFFFFFFF);
        etConnectPort.setHintTextColor(0xFF888888);
        box.addView(etConnectPort);

        new AlertDialog.Builder(activity)
                .setTitle("Wireless ADB Pair & Connect")
                .setMessage("Split-screen Android Settings -> Developer Options -> Wireless Debugging to copy ports/code.")
                .setView(box)
                .setPositiveButton("Connect", (d, w) -> {
                    String cmd = AndroidBuildConfig.buildAdbPairAndConnectCommand(
                            TermuxConstants.TERMUX_PREFIX_DIR_PATH,
                            etPairPort.getText().toString(),
                            etPairCode.getText().toString(),
                            etConnectPort.getText().toString());
                    BuildLogDialog.runShellProgressTask(
                            activity,
                            buildManager,
                            "Connecting Wireless ADB",
                            "Starting adb pair/connect...",
                            "Wireless ADB check finished!",
                            "Wireless ADB Ready!",
                            cmd,
                            new File(TermuxConstants.TERMUX_HOME_DIR_PATH),
                            this::refreshStatuses);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static Activity findActivity(Context context) {
        Context c = context;
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}
