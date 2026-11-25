package com.termux.app.build;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.termux.app.RepoCompatInstaller;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Manages On-Device (Native Termux ARM64: OpenJDK + aapt2 + Gradle) and
 * Cloud Build (GitHub Actions / Remote Builder) setup, execution, and APK installation.
 */
public class AndroidBuildManager {

    private static final String PREFS_NAME = "android_build_prefs";
    private static final String KEY_BUILD_MODE = "build_mode";
    private static final String KEY_CLOUD_PROVIDER = "cloud_provider";
    private static final String KEY_GH_REPO = "gh_repo";
    private static final String KEY_GH_TOKEN = "gh_token";
    private static final String KEY_GH_WORKFLOW = "gh_workflow";
    private static final String KEY_GH_BRANCH = "gh_branch";
    private static final String KEY_REMOTE_URL = "remote_builder_url";
    private static final String KEY_REMOTE_TOKEN = "remote_builder_token";

    public interface BuildListener {
        void onLog(String line);
        void onSuccess(File apkFile);
        void onError(String message);
    }

    public static class ToolchainStatus {
        public final boolean hasJava;
        public final boolean hasAapt2;
        public final boolean hasGradle;
        public final boolean hasSdkDir;

        public ToolchainStatus(boolean hasJava, boolean hasAapt2, boolean hasGradle, boolean hasSdkDir) {
            this.hasJava = hasJava;
            this.hasAapt2 = hasAapt2;
            this.hasGradle = hasGradle;
            this.hasSdkDir = hasSdkDir;
        }

        public boolean isReady() {
            return hasJava && hasAapt2 && hasGradle && hasSdkDir;
        }

        public String summary() {
            if (isReady()) {
                return "Ready (OpenJDK 17 • ARM64 aapt2 • Gradle • SDK)";
            }
            List<String> missing = new ArrayList<>();
            if (!hasJava) missing.add("JDK");
            if (!hasAapt2) missing.add("aapt2");
            if (!hasGradle) missing.add("Gradle");
            if (!hasSdkDir) missing.add("SDK");
            return "Missing: " + String.join(", ", missing) + " — tap Download to set up";
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Process activeProcess;
    private volatile boolean cancelled;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build();

    public AndroidBuildManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    // Preferences
    // ------------------------------------------------------------------

    public String getBuildMode() {
        return prefs.getString(KEY_BUILD_MODE, AndroidBuildConfig.MODE_LOCAL);
    }

    public void setBuildMode(String mode) {
        prefs.edit().putString(KEY_BUILD_MODE, mode).apply();
    }

    public String getCloudProvider() {
        return prefs.getString(KEY_CLOUD_PROVIDER, AndroidBuildConfig.CLOUD_PROVIDER_GITHUB);
    }

    public void setCloudProvider(String provider) {
        prefs.edit().putString(KEY_CLOUD_PROVIDER, provider).apply();
    }

    public String getGitHubRepo() {
        return prefs.getString(KEY_GH_REPO, "");
    }

    public void setGitHubRepo(String repo) {
        prefs.edit().putString(KEY_GH_REPO, repo == null ? "" : repo.trim()).apply();
    }

    public String getGitHubToken() {
        return prefs.getString(KEY_GH_TOKEN, "");
    }

    public void setGitHubToken(String token) {
        prefs.edit().putString(KEY_GH_TOKEN, token == null ? "" : token.trim()).apply();
    }

    public String getGitHubWorkflow() {
        String wf = prefs.getString(KEY_GH_WORKFLOW, "android-build.yml");
        return wf.isEmpty() ? "android-build.yml" : wf;
    }

    public void setGitHubWorkflow(String workflow) {
        prefs.edit().putString(KEY_GH_WORKFLOW, workflow == null ? "android-build.yml" : workflow.trim()).apply();
    }

    public String getGitHubBranch() {
        String br = prefs.getString(KEY_GH_BRANCH, "main");
        return br.isEmpty() ? "main" : br;
    }

    public void setGitHubBranch(String branch) {
        prefs.edit().putString(KEY_GH_BRANCH, branch == null ? "main" : branch.trim()).apply();
    }

    public String getRemoteBuilderUrl() {
        return prefs.getString(KEY_REMOTE_URL, "");
    }

    public void setRemoteBuilderUrl(String url) {
        prefs.edit().putString(KEY_REMOTE_URL, url == null ? "" : url.trim()).apply();
    }

    public String getRemoteBuilderToken() {
        return prefs.getString(KEY_REMOTE_TOKEN, "");
    }

    public void setRemoteBuilderToken(String token) {
        prefs.edit().putString(KEY_REMOTE_TOKEN, token == null ? "" : token.trim()).apply();
    }

    public boolean isCloudConfigured() {
        if (AndroidBuildConfig.CLOUD_PROVIDER_REMOTE.equals(getCloudProvider())) {
            return !getRemoteBuilderUrl().isEmpty();
        }
        return !getGitHubRepo().isEmpty() && !getGitHubToken().isEmpty();
    }

    public String getCloudSummary() {
        if (AndroidBuildConfig.CLOUD_PROVIDER_REMOTE.equals(getCloudProvider())) {
            String url = getRemoteBuilderUrl();
            return url.isEmpty() ? "Remote Builder not configured" : "Remote: " + url;
        }
        String repo = getGitHubRepo();
        if (repo.isEmpty() || getGitHubToken().isEmpty()) {
            return "GitHub Actions not configured — tap Configure";
        }
        return "GitHub: " + repo + " (" + getGitHubBranch() + ")";
    }

    // ------------------------------------------------------------------
    // Toolchain detection & setup (Native Termux ARM64)
    // ------------------------------------------------------------------

    public ToolchainStatus checkLocalToolchain() {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        boolean hasJava = new File(prefix + "/bin/java").exists()
                || new File(prefix + "/lib/jvm/java-17-openjdk/bin/java").exists()
                || new File(prefix + "/lib/jvm/java-21-openjdk/bin/java").exists();
        boolean hasAapt2 = new File(prefix + "/bin/aapt2").exists();
        boolean hasGradle = new File(prefix + "/bin/gradle").exists();
        boolean hasSdk = new File(prefix + "/opt/android-sdk/build-tools").isDirectory();
        return new ToolchainStatus(hasJava, hasAapt2, hasGradle, hasSdk);
    }

    public String getLocalToolchainInstallCommand() {
        RepoCompatInstaller.ensureInstalled();
        return AndroidBuildConfig.buildLocalToolchainInstallCommand(
                TermuxConstants.TERMUX_PREFIX_DIR_PATH,
                TermuxConstants.TERMUX_HOME_DIR_PATH);
    }

    public void downloadLocalToolchainAsync(BuildListener listener) {
        runTermuxCommandAsync(
                new File(TermuxConstants.TERMUX_HOME_DIR_PATH),
                getLocalToolchainInstallCommand(),
                "Local toolchain setup failed. Check network/storage.",
                listener);
    }

    /**
     * Execute a Termux shell command asynchronously (used by Local ARM64 setup and Playwright setup).
     */
    public void runTermuxCommandAsync(File cwd, String command, String errorPrefix, BuildListener listener) {
        cancelled = false;
        new Thread(() -> {
            int exit = runNativeTermuxCommand(cwd, command, listener);
            if (cancelled) return;
            if (exit == 0) {
                main.post(() -> listener.onSuccess(null));
            } else {
                main.post(() -> listener.onError(errorPrefix + " (exit " + exit + ")"));
            }
        }, "termux-task").start();
    }

    public void cancel() {
        cancelled = true;
        Process p = activeProcess;
        if (p != null) {
            try {
                p.destroy();
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // One-tap Build & Install
    // ------------------------------------------------------------------

    public void buildProjectAsync(File workspace, String modeOverride, BuildListener listener) {
        cancelled = false;
        try {
            if (DebugCrashReporterConfig.injectDebugCrashReporter(workspace)) {
                postLog(listener, "Auto-injected debug crash & logcat reporter (XyzCrashReporterProvider).");
            }
        } catch (Exception e) {
            postLog(listener, "Note: crash reporter injection skipped (" + e.getMessage() + ")");
        }
        String effectiveMode = modeOverride != null ? modeOverride
                : AndroidBuildConfig.resolveEffectiveMode(
                        getBuildMode(),
                        isCloudConfigured(),
                        AndroidBuildConfig.isHeavyAndroidProject(workspace));

        if (AndroidBuildConfig.MODE_CLOUD.equals(effectiveMode)) {
            if (!isCloudConfigured()) {
                main.post(() -> listener.onError("Cloud Build is not configured. Open Settings -> Configure Cloud Build."));
                return;
            }
            if (AndroidBuildConfig.CLOUD_PROVIDER_REMOTE.equals(getCloudProvider())) {
                buildViaRemoteServerAsync(workspace, listener);
            } else {
                buildViaGitHubActionsAsync(workspace, listener);
            }
            return;
        }

        buildLocallyAsync(workspace, listener);
    }

    private void buildLocallyAsync(File workspace, BuildListener listener) {
        new Thread(() -> {
            ToolchainStatus status = checkLocalToolchain();
            if (!status.isReady()) {
                postLog(listener, "Local toolchain incomplete (" + status.summary() + "). Installing missing components first...");
                int setupExit = runNativeTermuxCommand(
                        new File(TermuxConstants.TERMUX_HOME_DIR_PATH),
                        getLocalToolchainInstallCommand(),
                        listener);
                if (cancelled) return;
                if (setupExit != 0) {
                    main.post(() -> listener.onError("Toolchain install failed (exit " + setupExit + ")."));
                    return;
                }
            }

            postLog(listener, "=== Starting On-Device ARM64 Gradle Build ===");
            String buildCmd = AndroidBuildConfig.buildLocalGradleCommand(
                    workspace,
                    TermuxConstants.TERMUX_PREFIX_DIR_PATH,
                    TermuxConstants.TERMUX_HOME_DIR_PATH);
            int exit = runNativeTermuxCommand(workspace, buildCmd, listener);
            if (cancelled) return;
            if (exit != 0) {
                main.post(() -> listener.onError("Gradle build failed (exit " + exit + ")."));
                return;
            }

            File apk = findNewestDebugApk(workspace);
            if (apk == null) {
                main.post(() -> listener.onError("Build succeeded, but no debug APK was found in app/build/outputs/apk/debug/."));
                return;
            }
            postLog(listener, "APK ready: " + apk.getAbsolutePath());
            main.post(() -> listener.onSuccess(apk));
        }, "local-android-build").start();
    }

    private void buildViaGitHubActionsAsync(File workspace, BuildListener listener) {
        new Thread(() -> {
            try {
                String repo = getGitHubRepo();
                String token = getGitHubToken();
                String workflow = getGitHubWorkflow();
                String branch = getGitHubBranch();

                postLog(listener, "=== Cloud Build via GitHub Actions (" + repo + " @ " + branch + ") ===");

                // Ensure workflow file exists and push latest changes if git repo
                File wfFile = new File(workspace, ".github/workflows/" + workflow);
                if (!wfFile.exists()) {
                    File parent = wfFile.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(wfFile)) {
                        fos.write(AndroidBuildConfig.buildGitHubWorkflowYaml().getBytes(StandardCharsets.UTF_8));
                    }
                    postLog(listener, "Created workflow file: .github/workflows/" + workflow);
                }

                if (new File(workspace, ".git").isDirectory()) {
                    postLog(listener, "Pushing latest workspace changes to GitHub...");
                    runNativeTermuxCommand(workspace,
                            "git add -A && { git diff --cached --quiet || git commit -m 'Xyz.IDE Cloud Build'; } && git push origin '" + branch + "' || true",
                            listener);
                }

                String dispatchUrl = "https://api.github.com/repos/" + repo + "/actions/workflows/" + workflow + "/dispatches";
                JSONObject payload = new JSONObject();
                payload.put("ref", branch);

                Request dispatchReq = new Request.Builder()
                        .url(dispatchUrl)
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github+json")
                        .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
                        .build();
                try (Response resp = http.newCall(dispatchReq).execute()) {
                    if (!resp.isSuccessful()) {
                        String errBody = resp.body() != null ? resp.body().string() : "";
                        throw new Exception("GitHub workflow dispatch failed: HTTP " + resp.code() + " " + errBody);
                    }
                }
                postLog(listener, "Workflow dispatched! Waiting for GitHub runner...");

                long runId = -1;
                String conclusion = null;
                long deadline = System.currentTimeMillis() + 15 * 60 * 1000L;
                while (!cancelled && System.currentTimeMillis() < deadline) {
                    Thread.sleep(6000);
                    String runsUrl = "https://api.github.com/repos/" + repo + "/actions/workflows/" + workflow + "/runs?per_page=5";
                    Request runsReq = new Request.Builder()
                            .url(runsUrl)
                            .header("Authorization", "Bearer " + token)
                            .header("Accept", "application/vnd.github+json")
                            .build();
                    try (Response resp = http.newCall(runsReq).execute()) {
                        if (!resp.isSuccessful() || resp.body() == null) continue;
                        JSONObject root = new JSONObject(resp.body().string());
                        JSONArray runs = root.optJSONArray("workflow_runs");
                        if (runs == null || runs.length() == 0) continue;
                        JSONObject latest = runs.getJSONObject(0);
                        runId = latest.optLong("id", -1);
                        String status = latest.optString("status", "");
                        conclusion = latest.optString("conclusion", "");
                        postLog(listener, "Cloud run #" + runId + ": " + status + (conclusion.isEmpty() ? "" : " (" + conclusion + ")"));
                        if ("completed".equals(status)) break;
                    }
                }

                if (cancelled) return;
                if (runId <= 0 || !"success".equals(conclusion)) {
                    throw new Exception("Cloud build failed or timed out (conclusion=" + conclusion + ").");
                }

                // Fetch artifacts
                String artifactsUrl = "https://api.github.com/repos/" + repo + "/actions/runs/" + runId + "/artifacts";
                Request artReq = new Request.Builder()
                        .url(artifactsUrl)
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github+json")
                        .build();
                String downloadUrl = null;
                try (Response resp = http.newCall(artReq).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        throw new Exception("Could not fetch artifacts: HTTP " + resp.code());
                    }
                    JSONObject root = new JSONObject(resp.body().string());
                    JSONArray artifacts = root.optJSONArray("artifacts");
                    if (artifacts != null && artifacts.length() > 0) {
                        downloadUrl = artifacts.getJSONObject(0).optString("archive_download_url", null);
                    }
                }
                if (downloadUrl == null) {
                    throw new Exception("Build succeeded, but no APK artifact was uploaded by the workflow.");
                }

                postLog(listener, "Downloading built APK artifact...");
                File outDir = new File(workspace, "build/cloud-outputs");
                if (!outDir.exists()) outDir.mkdirs();
                File zipFile = new File(outDir, "artifact.zip");

                Request dlReq = new Request.Builder()
                        .url(downloadUrl)
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github+json")
                        .build();
                try (Response resp = http.newCall(dlReq).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        throw new Exception("Artifact download failed: HTTP " + resp.code());
                    }
                    try (InputStream in = resp.body().byteStream();
                         FileOutputStream out = new FileOutputStream(zipFile)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                }

                File apk = extractFirstApkFromZip(zipFile, outDir);
                if (apk == null) {
                    throw new Exception("No .apk found inside downloaded GitHub artifact.");
                }
                postLog(listener, "Cloud APK downloaded: " + apk.getAbsolutePath());
                main.post(() -> listener.onSuccess(apk));
            } catch (Exception e) {
                if (!cancelled) {
                    main.post(() -> listener.onError("Cloud Build failed: " + e.getMessage()));
                }
            }
        }, "github-cloud-build").start();
    }

    private void buildViaRemoteServerAsync(File workspace, BuildListener listener) {
        new Thread(() -> {
            try {
                String url = getRemoteBuilderUrl();
                String token = getRemoteBuilderToken();
                postLog(listener, "=== Cloud Build via Remote Builder (" + url + ") ===");

                File outDir = new File(workspace, "build/cloud-outputs");
                if (!outDir.exists()) outDir.mkdirs();
                File srcZip = new File(outDir, "project-src.zip");
                zipWorkspace(workspace, srcZip);
                postLog(listener, "Uploaded project bundle (" + (srcZip.length() / 1024) + " KB)...");

                MultipartBody.Builder bodyBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("project", srcZip.getName(),
                                RequestBody.create(srcZip, MediaType.parse("application/zip")));

                Request.Builder req = new Request.Builder()
                        .url(url)
                        .post(bodyBuilder.build());
                if (!token.isEmpty()) {
                    req.header("Authorization", "Bearer " + token);
                }

                File outApk = new File(outDir, "cloud-app-debug.apk");
                try (Response resp = http.newCall(req.build()).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        String msg = resp.body() != null ? resp.body().string() : "";
                        throw new Exception("Remote builder returned HTTP " + resp.code() + " " + msg);
                    }
                    try (InputStream in = resp.body().byteStream();
                         FileOutputStream out = new FileOutputStream(outApk)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                }
                postLog(listener, "Cloud APK received: " + outApk.getAbsolutePath());
                main.post(() -> listener.onSuccess(outApk));
            } catch (Exception e) {
                if (!cancelled) {
                    main.post(() -> listener.onError("Remote Cloud Build failed: " + e.getMessage()));
                }
            }
        }, "remote-cloud-build").start();
    }

    // ------------------------------------------------------------------
    // APK Installation
    // ------------------------------------------------------------------

    public static void installApk(Context context, File apkFile) {
        if (context == null || apkFile == null || !apkFile.isFile()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !context.getPackageManager().canRequestPackageInstalls()) {
            Intent permIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
            permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(permIntent);
            return;
        }
        Uri apkUri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".apkprovider",
                apkFile);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private int runNativeTermuxCommand(File cwd, String command, BuildListener listener) {
        String bash = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(bash).exists()) {
            postLog(listener, "ERROR: Termux bootstrap bash not found. Open Terminal once first.");
            return 127;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(bash, "-lc", command);
            if (cwd != null && cwd.isDirectory()) {
                pb.directory(cwd);
            }
            pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                    + ":" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/applets");
            pb.environment().put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/lib");
            pb.environment().put("TMPDIR", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/tmp");
            pb.environment().put("TERM", "xterm-256color");
            pb.environment().put("LANG", "en_US.UTF-8");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            activeProcess = p;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (cancelled) break;
                    postLog(listener, line);
                }
            }
            return p.waitFor();
        } catch (Exception e) {
            postLog(listener, "Process error: " + e.getMessage());
            return 1;
        } finally {
            activeProcess = null;
        }
    }

    private void postLog(BuildListener listener, String line) {
        if (listener != null) {
            main.post(() -> listener.onLog(line));
        }
    }

    private static File findNewestDebugApk(File workspace) {
        File[] candidateDirs = {
                new File(workspace, "app/build/outputs/apk/debug"),
                new File(workspace, "build/outputs/apk/debug"),
                new File(workspace, "android/app/build/outputs/apk/debug"),
                new File(workspace, "build/app/outputs/flutter-apk")
        };
        File best = null;
        for (File dir : candidateDirs) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".apk")) {
                    if (f.getName().contains("universal")) return f;
                    if (best == null) best = f;
                }
            }
        }
        return best;
    }

    private static File extractFirstApkFromZip(File zipFile, File destDir) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".apk")) {
                    String name = new File(entry.getName()).getName();
                    File out = new File(destDir, name);
                    try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(out))) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = zis.read(buf)) != -1) bos.write(buf, 0, n);
                    }
                    return out;
                }
            }
        }
        return null;
    }

    private static void zipWorkspace(File root, File destZip) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(destZip)))) {
            zipDirRecursive(root, root, zos);
        }
    }

    private static void zipDirRecursive(File root, File current, ZipOutputStream zos) throws Exception {
        File[] files = current.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (name.equals(".git") || name.equals(".gradle") || name.equals("build")
                    || name.equals("node_modules") || name.equals(".idea")) {
                continue;
            }
            String rel = root.toURI().relativize(f.toURI()).getPath();
            if (f.isDirectory()) {
                zipDirRecursive(root, f, zos);
            } else {
                zos.putNextEntry(new ZipEntry(rel));
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) != -1) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
        }
    }
}
