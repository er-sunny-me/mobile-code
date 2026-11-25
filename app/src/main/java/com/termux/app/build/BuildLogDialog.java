package com.termux.app.build;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * Clean 1-100% progress dialog for toolchain downloads and APK builds.
 * Verbose logs stay hidden during normal execution and open automatically
 * if an error occurs.
 */
public final class BuildLogDialog {

    private BuildLogDialog() {
    }

    private static final class DialogViews {
        final LinearLayout root;
        final TextView tvStage;
        final TextView tvPercent;
        final ProgressBar progressBar;
        final ScrollView logScroll;
        final TextView tvLogs;
        final StringBuilder fullLog = new StringBuilder();
        int currentPercent = 1;

        DialogViews(Activity activity, String initialStage) {
            root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(0xFF1E1E1E);
            root.setPadding(48, 36, 48, 24);

            LinearLayout headerRow = new LinearLayout(activity);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);

            tvStage = new TextView(activity);
            tvStage.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tvStage.setText(initialStage);
            tvStage.setTextColor(0xFFE0E0E0);
            tvStage.setTextSize(13f);
            headerRow.addView(tvStage);

            tvPercent = new TextView(activity);
            tvPercent.setText("1%");
            tvPercent.setTextColor(0xFF007ACC);
            tvPercent.setTextSize(13f);
            tvPercent.setTypeface(Typeface.DEFAULT_BOLD);
            headerRow.addView(tvPercent);
            root.addView(headerRow);

            progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            pbParams.topMargin = 20;
            pbParams.bottomMargin = 16;
            progressBar.setLayoutParams(pbParams);
            progressBar.setMax(100);
            progressBar.setProgress(1);
            progressBar.setProgressTintList(ColorStateList.valueOf(0xFF007ACC));
            progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF333333));
            root.addView(progressBar);

            logScroll = new ScrollView(activity);
            LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 420);
            scrollParams.topMargin = 12;
            logScroll.setLayoutParams(scrollParams);
            logScroll.setBackgroundColor(0xFF121212);
            logScroll.setVisibility(View.GONE);

            tvLogs = new TextView(activity);
            tvLogs.setPadding(24, 20, 24, 20);
            tvLogs.setTextColor(0xFFF48771);
            tvLogs.setTypeface(Typeface.MONOSPACE);
            tvLogs.setTextSize(11f);
            logScroll.addView(tvLogs);
            root.addView(logScroll);
        }

        void handleLine(String line) {
            if (line == null) return;
            if (fullLog.length() > 80_000) {
                fullLog.delete(0, fullLog.length() - 60_000);
            }
            fullLog.append(line).append('\n');

            AndroidBuildConfig.ProgressUpdate upd =
                    AndroidBuildConfig.parseProgressLine(line, currentPercent);
            currentPercent = upd.percent;
            progressBar.setProgress(currentPercent);
            tvPercent.setText(currentPercent + "%");
            if (upd.stage != null && !upd.stage.isEmpty()) {
                tvStage.setText(upd.stage);
            }
        }

        void showComplete(String message) {
            currentPercent = 100;
            progressBar.setProgress(100);
            tvPercent.setText("100%");
            tvPercent.setTextColor(0xFF4EC9B0);
            progressBar.setProgressTintList(ColorStateList.valueOf(0xFF4EC9B0));
            tvStage.setText(message);
        }

        void showErrorLogs(String errorMessage) {
            tvPercent.setTextColor(0xFFF44747);
            progressBar.setProgressTintList(ColorStateList.valueOf(0xFFF44747));
            tvStage.setText("Failed: " + errorMessage);
            StringBuilder out = new StringBuilder();
            if (fullLog.length() > 0) {
                out.append(fullLog);
                out.append('\n');
            }
            out.append("ERROR: ").append(errorMessage);
            tvLogs.setText(out.toString());
            logScroll.setVisibility(View.VISIBLE);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    public static void runToolchainDownload(Activity activity, AndroidBuildManager manager, Runnable onComplete) {
        runProgressTask(
                activity,
                manager,
                "Downloading Build Packages",
                "Preparing ARM64 toolchain download...",
                "Local ARM64 toolchain ready!",
                "Local Android Build Toolchain Ready!",
                onComplete,
                manager::downloadLocalToolchainAsync);
    }

    /**
     * Run Playwright + headless Chromium installation with 1-100% progress bar and error-only logs.
     */
    public static void runPlaywrightSetup(Activity activity, AndroidBuildManager manager,
                                          String command, File workDir, Runnable onComplete) {
        runShellProgressTask(
                activity,
                manager,
                "Setting up Playwright (AI Browser)",
                "Preparing Ubuntu Playwright + Chromium setup...",
                "Playwright + Headless Chromium ready!",
                "Playwright AI Browser Ready!",
                command,
                workDir,
                onComplete);
    }

    /**
     * Generic 1-100% progress dialog for shell setup tasks (Playwright, ADB, Wireless Connect).
     */
    public static void runShellProgressTask(Activity activity, AndroidBuildManager manager,
                                            String title, String initialStage,
                                            String completeStage, String toastMessage,
                                            String command, File workDir, Runnable onComplete) {
        runProgressTask(
                activity,
                manager,
                title,
                initialStage,
                completeStage,
                toastMessage,
                onComplete,
                listener -> manager.runTermuxCommandAsync(
                        workDir, command, title + " failed", listener));
    }

    private interface TaskStarter {
        void start(AndroidBuildManager.BuildListener listener);
    }

    private static void runProgressTask(Activity activity, AndroidBuildManager manager,
                                        String title, String initialStage,
                                        String completeStage, String toastMessage,
                                        Runnable onComplete, TaskStarter starter) {
        if (activity == null || activity.isFinishing()) return;

        final DialogViews ui = new DialogViews(activity, initialStage);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(ui.root)
                .setNegativeButton("Cancel", (d, w) -> manager.cancel())
                .setCancelable(false)
                .create();
        dialog.show();

        starter.start(new AndroidBuildManager.BuildListener() {
            @Override
            public void onLog(String line) {
                ui.handleLine(line);
            }

            @Override
            public void onSuccess(File apkFile) {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                ui.showComplete(completeStage);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("Done");
                dialog.setCancelable(true);
                if (onComplete != null) onComplete.run();
                Toast.makeText(activity, toastMessage, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(String message) {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                ui.showErrorLogs(message);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("Close");
                dialog.setCancelable(true);
                if (onComplete != null) onComplete.run();
            }
        });
    }

    public static void runProjectBuild(Activity activity, AndroidBuildManager manager,
                                       File workspace, String modeOverride) {
        if (activity == null || activity.isFinishing() || workspace == null) return;

        final DialogViews ui = new DialogViews(activity, "Building " + workspace.getName() + "...");

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Build & Install APK")
                .setView(ui.root)
                .setPositiveButton("Install APK", null)
                .setNegativeButton("Cancel", (d, w) -> manager.cancel())
                .setCancelable(false)
                .create();
        dialog.show();

        final View installBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (installBtn != null) installBtn.setVisibility(View.GONE);

        manager.buildProjectAsync(workspace, modeOverride, new AndroidBuildManager.BuildListener() {
            @Override
            public void onLog(String line) {
                ui.handleLine(line);
            }

            @Override
            public void onSuccess(File apkFile) {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                ui.showComplete("APK ready: " + apkFile.getName());
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("Close");
                dialog.setCancelable(true);
                if (installBtn != null) {
                    installBtn.setVisibility(View.VISIBLE);
                    installBtn.setOnClickListener(v -> AndroidBuildManager.installApk(activity, apkFile));
                }
                AndroidBuildManager.installApk(activity, apkFile);
            }

            @Override
            public void onError(String message) {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                ui.showErrorLogs(message);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("Close");
                dialog.setCancelable(true);
            }
        });
    }
}
