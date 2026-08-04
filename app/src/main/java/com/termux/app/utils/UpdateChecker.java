package com.termux.app.utils;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import com.termux.BuildConfig;

public class UpdateChecker {
    private static final String TAG = "UpdateChecker";
    private static final String API_URL = "https://api.github.com/repos/er-sunny-me/mobile-code/releases/latest";

    public static void checkForUpdates(final Context context) {
        new Thread(() -> {
            try {
                URL url = new URL(API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String inputLine;
                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }
                    in.close();

                    JSONObject jsonResponse = new JSONObject(response.toString());
                    String latestVersion = jsonResponse.getString("tag_name").replace("v", "");
                    String currentVersion = BuildConfig.VERSION_NAME.replace("v", "");

                    if (isVersionNewer(currentVersion, latestVersion)) {
                        String downloadUrl = null;
                        JSONArray assets = jsonResponse.getJSONArray("assets");
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.getJSONObject(i);
                            String assetName = asset.getString("name");
                            if (assetName.endsWith(".apk")) {
                                downloadUrl = asset.getString("browser_download_url");
                                break;
                            }
                        }

                        if (downloadUrl != null) {
                            final String finalDownloadUrl = downloadUrl;
                            new Handler(Looper.getMainLooper()).post(() -> showUpdateDialog(context, latestVersion, finalDownloadUrl));
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to check for updates", e);
            }
        }).start();
    }

    private static boolean isVersionNewer(String currentVer, String latestVer) {
        try {
            String[] currentParts = currentVer.split("\\.");
            String[] latestParts = latestVer.split("\\.");
            int length = Math.max(currentParts.length, latestParts.length);
            for (int i = 0; i < length; i++) {
                int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                int latestPart = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;
                if (latestPart > currentPart) return true;
                if (latestPart < currentPart) return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Version parse error", e);
            // Fallback to string comparison if parsing fails
            return latestVer.compareTo(currentVer) > 0;
        }
        return false;
    }

    private static void showUpdateDialog(final Context context, String latestVersion, final String downloadUrl) {
        new AlertDialog.Builder(context)
                .setTitle("Update Available")
                .setMessage("A new version (" + latestVersion + ") is available. Do you want to download and install it?")
                .setPositiveButton("Update Now", (dialog, which) -> startDownload(context, downloadUrl, latestVersion))
                .setNegativeButton("Later", null)
                .setCancelable(false)
                .show();
    }

    private static void startDownload(Context context, String downloadUrl, String version) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
            request.setTitle("Mobile Code Update");
            request.setDescription("Downloading version " + version);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MobileCode-" + version + ".apk");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setMimeType("application/vnd.android.package-archive");

            DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager != null) {
                manager.enqueue(request);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start download", e);
        }
    }
}
