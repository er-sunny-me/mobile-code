package com.termux.app.utils;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

public class UpdateReceiver extends BroadcastReceiver {
    private static final String TAG = "UpdateReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
            long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (downloadId == -1) return;

            DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (downloadManager == null) return;

            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(downloadId);
            
            try (Cursor cursor = downloadManager.query(query)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    if (statusIndex != -1 && cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL) {
                        Uri downloadUri = downloadManager.getUriForDownloadedFile(downloadId);
                        if (downloadUri != null) {
                            Log.i(TAG, "Update download completed. Launching installer for: " + downloadUri);
                            installApk(context, downloadUri);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse download completion", e);
            }
        }
    }

    private void installApk(Context context, Uri apkUri) {
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(installIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start install intent", e);
        }
    }
}
