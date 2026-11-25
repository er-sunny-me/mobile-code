package com.termux.app.build;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileWriter;

/**
 * Receives debug crash & logcat payloads broadcast from child apps built in Xyz.IDE,
 * persists {@code CRASH_REPORT.md} inside the active workspace for AI agents,
 * and offers a one-tap notification to fix the crash in Terminal.
 */
public class CrashReportReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "xyzide_crash_reports";
    private static final int NOTIFICATION_ID = 44091;

    public static final String PREF_PENDING_CRASH_PKG = "pending_crash_pkg";
    public static final String PREF_PENDING_CRASH_ERR = "pending_crash_err";
    public static final String PREF_PENDING_CRASH_WS = "pending_crash_ws";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!DebugCrashReporterConfig.ACTION_REPORT_CRASH.equals(intent.getAction())) return;

        String pkg = intent.getStringExtra("package_name");
        String exc = intent.getStringExtra("exception_class");
        String msg = intent.getStringExtra("error_message");
        String trace = intent.getStringExtra("stacktrace");
        String logcat = intent.getStringExtra("logcat");
        String device = intent.getStringExtra("device");

        long now = System.currentTimeMillis();
        String markdown = DebugCrashReporterConfig.formatCrashReportMarkdown(
                pkg, exc, msg, trace, logcat, device, now);

        SharedPreferences prefs = context.getSharedPreferences("ZeroCodePrefs", Context.MODE_PRIVATE);
        String wsPath = prefs.getString("workspace_path", TermuxConstants.TERMUX_HOME_DIR_PATH);
        if (wsPath == null || wsPath.trim().isEmpty()) {
            wsPath = TermuxConstants.TERMUX_HOME_DIR_PATH;
        }

        File targetDir = new File(wsPath);
        if (!targetDir.isDirectory()) {
            targetDir = context.getFilesDir();
        }
        File reportFile = new File(targetDir, DebugCrashReporterConfig.REPORT_FILE_NAME);
        try (FileWriter fw = new FileWriter(reportFile)) {
            fw.write(markdown);
        } catch (Exception ignored) {
            File fallback = new File(context.getFilesDir(), DebugCrashReporterConfig.REPORT_FILE_NAME);
            try (FileWriter fw2 = new FileWriter(fallback)) {
                fw2.write(markdown);
            } catch (Exception ignored2) {
            }
        }

        String shortErr = (exc != null ? exc : "Crash")
                + (msg != null && !msg.isEmpty() ? ": " + msg : "");
        prefs.edit()
                .putString(PREF_PENDING_CRASH_PKG, pkg != null ? pkg : "unknown")
                .putString(PREF_PENDING_CRASH_ERR, shortErr)
                .putString(PREF_PENDING_CRASH_WS, targetDir.getAbsolutePath())
                .apply();

        showCrashNotification(context, pkg, shortErr, targetDir.getAbsolutePath());
    }

    private void showCrashNotification(Context context, String pkg, String summary, String workspace) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "App Crash Reports",
                        NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Captures crashes & logcat from debug apps built in Xyz.IDE");
                nm.createNotificationChannel(channel);
            }

            Intent fixIntent = new Intent(context, TermuxActivity.class);
            fixIntent.setAction(DebugCrashReporterConfig.ACTION_FIX_CRASH);
            fixIntent.putExtra("crash_package", pkg);
            fixIntent.putExtra("workspace", workspace);
            fixIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(context, NOTIFICATION_ID, fixIntent, flags);

            String title = "Crash captured: " + (pkg != null ? pkg : "Debug App");
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_terminal)
                    .setContentTitle(title)
                    .setContentText("Saved CRASH_REPORT.md — Tap to send to AI Agent")
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText(summary + "\n\nSaved to CRASH_REPORT.md. Tap to open Terminal & send to AI agent."))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pi);

            nm.notify(NOTIFICATION_ID, builder.build());
        } catch (Throwable ignored) {
        }
    }
}
