package com.termux.app.preferences;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.termux.R;
import com.termux.app.browser.BrowserActivity;
import com.termux.app.browser.BrowserAutomationConfig;
import com.termux.app.build.AndroidBuildConfig;
import com.termux.app.build.AndroidBuildManager;
import com.termux.app.build.BuildLogDialog;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;

/**
 * Settings card for managing the Playwright (AI automation in Ubuntu PRoot)
 * + Android WebView (in-app browsing) hybrid setup.
 */
public class BrowserPreference extends Preference {

    private TextView tvStatus;
    private TextView tvBadge;

    public BrowserPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_browser);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);

        tvStatus = (TextView) holder.findViewById(R.id.tv_playwright_status);
        tvBadge = (TextView) holder.findViewById(R.id.tv_playwright_badge);
        Button btnInstall = (Button) holder.findViewById(R.id.btn_install_playwright);
        Button btnOpenWebView = (Button) holder.findViewById(R.id.btn_open_webview_browser);

        refreshStatus();

        if (btnInstall != null) {
            btnInstall.setOnClickListener(v -> {
                Activity activity = findActivity(getContext());
                if (activity != null) {
                    AndroidBuildManager manager = new AndroidBuildManager(activity);
                    String cmd = BrowserAutomationConfig.buildPlaywrightInstallCommand(
                            TermuxConstants.TERMUX_PREFIX_DIR_PATH);
                    BuildLogDialog.runPlaywrightSetup(
                            activity,
                            manager,
                            cmd,
                            new File(TermuxConstants.TERMUX_HOME_DIR_PATH),
                            () -> {
                                refreshStatus();
                                syncDocs(manager);
                            });
                } else {
                    Toast.makeText(getContext(), "Open Settings from the app to install.", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnOpenWebView != null) {
            btnOpenWebView.setOnClickListener(v -> {
                Intent intent = new Intent(getContext(), BrowserActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            });
        }
    }

    private void refreshStatus() {
        new Thread(() -> {
            boolean ready = BrowserAutomationConfig.isPlaywrightReady(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            Activity activity = findActivity(getContext());
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    if (tvStatus != null) {
                        tvStatus.setText(ready
                                ? "Ready inside Ubuntu PRoot (xyz-playwright + --no-sandbox Chromium)"
                                : "Not installed yet — tap Install Playwright for AI browser control");
                    }
                    if (tvBadge != null) {
                        tvBadge.setText(ready ? "✓ Ready" : "Not set");
                        tvBadge.setTextColor(ready ? 0xFF4ADE80 : 0xFFFBBF24);
                        tvBadge.setBackgroundResource(ready
                                ? R.drawable.bg_badge_installed : R.drawable.bg_badge_missing);
                    }
                });
            }
        }, "playwright-status-check").start();
    }

    private void syncDocs(AndroidBuildManager manager) {
        new Thread(() -> {
            try {
                SharedPreferences wsPrefs = getContext().getSharedPreferences("ZeroCodePrefs", Context.MODE_PRIVATE);
                String ws = wsPrefs.getString("workspace_path", TermuxConstants.TERMUX_HOME_DIR_PATH);
                if (ws != null) {
                    AndroidBuildConfig.syncWorkspaceAgentDocs(
                            new File(ws),
                            TermuxConstants.TERMUX_PREFIX_DIR_PATH,
                            manager.getBuildMode(),
                            manager.checkLocalToolchain().isReady(),
                            manager.getCloudSummary());
                }
            } catch (Exception ignored) {
            }
        }, "sync-browser-docs").start();
    }

    private static Activity findActivity(Context context) {
        Context cur = context;
        while (cur instanceof ContextWrapper) {
            if (cur instanceof Activity) return (Activity) cur;
            cur = ((ContextWrapper) cur).getBaseContext();
        }
        return null;
    }
}
