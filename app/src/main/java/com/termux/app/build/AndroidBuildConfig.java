package com.termux.app.build;

import com.termux.app.browser.BrowserAutomationConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Pure, unit-testable helpers for On-Device ARM64 Android builds and
 * Cloud Build (GitHub Actions / Remote Builder) configuration & templates.
 */
public final class AndroidBuildConfig {

    public static final String MODE_LOCAL = "local";
    public static final String MODE_CLOUD = "cloud";
    public static final String MODE_AUTO = "auto";

    public static final String CLOUD_PROVIDER_GITHUB = "github";
    public static final String CLOUD_PROVIDER_REMOTE = "remote";

    public static final int DEFAULT_COMPILE_SDK = 34;
    public static final String DEFAULT_BUILD_TOOLS = "34.0.0";

    private static final String CONTEXT_START = "<!-- XYZIDE_ANDROID_BUILD_START -->";
    private static final String CONTEXT_END = "<!-- XYZIDE_ANDROID_BUILD_END -->";

    private AndroidBuildConfig() {
    }

    /**
     * True if the workspace is a React Native project ({@code package.json} + {@code android/}).
     */
    public static boolean isReactNativeProject(File workspace) {
        if (workspace == null || !workspace.isDirectory()) return false;
        return new File(workspace, "package.json").isFile()
                && new File(workspace, "android").isDirectory();
    }

    /**
     * True if the workspace is a Flutter project ({@code pubspec.yaml} + {@code android/}).
     */
    public static boolean isFlutterProject(File workspace) {
        if (workspace == null || !workspace.isDirectory()) return false;
        return new File(workspace, "pubspec.yaml").isFile()
                && (new File(workspace, "android").isDirectory() || new File(workspace, "lib").isDirectory());
    }

    /**
     * Resolve the directory containing the Android Gradle root ({@code workspace} for
     * native Android apps, or {@code workspace/android} for React Native / Flutter apps).
     */
    public static File resolveAndroidGradleDir(File workspace) {
        if (workspace == null || !workspace.isDirectory()) return null;
        File directApp = new File(workspace, "app");
        if (directApp.isDirectory()
                || new File(workspace, "app/build.gradle").isFile()
                || new File(workspace, "app/build.gradle.kts").isFile()) {
            return workspace;
        }
        File nestedAndroid = new File(workspace, "android");
        if (nestedAndroid.isDirectory()
                && (new File(nestedAndroid, "app").isDirectory()
                || new File(nestedAndroid, "build.gradle").isFile()
                || new File(nestedAndroid, "build.gradle.kts").isFile()
                || new File(nestedAndroid, "settings.gradle").isFile()
                || new File(nestedAndroid, "settings.gradle.kts").isFile())) {
            return nestedAndroid;
        }
        return workspace;
    }

    /**
     * Detect whether a workspace directory looks like an Android Gradle project
     * (Native Android, React Native {@code android/}, or Flutter {@code android/}).
     */
    public static boolean isAndroidProject(File workspace) {
        if (workspace == null || !workspace.isDirectory()) return false;
        File gradleDir = resolveAndroidGradleDir(workspace);
        if (gradleDir == null) return false;
        File appBuild = new File(gradleDir, "app/build.gradle");
        File appBuildKts = new File(gradleDir, "app/build.gradle.kts");
        File rootBuild = new File(gradleDir, "build.gradle");
        File rootBuildKts = new File(gradleDir, "build.gradle.kts");
        File manifest = new File(gradleDir, "app/src/main/AndroidManifest.xml");
        return appBuild.isFile() || appBuildKts.isFile() || manifest.isFile()
                || ((rootBuild.isFile() || rootBuildKts.isFile())
                && (new File(gradleDir, "settings.gradle").isFile()
                || new File(gradleDir, "settings.gradle.kts").isFile()));
    }

    /**
     * Heuristic check for heavy projects (Jetpack Compose, C/C++ NDK, React Native, Flutter)
     * that benefit from Cloud Build when configured.
     */
    public static boolean isHeavyAndroidProject(File workspace) {
        if (workspace == null || !workspace.isDirectory()) return false;
        if (isReactNativeProject(workspace) || isFlutterProject(workspace)) {
            return true;
        }
        File gradleDir = resolveAndroidGradleDir(workspace);
        if (new File(gradleDir, "app/src/main/cpp/CMakeLists.txt").isFile()
                || new File(gradleDir, "app/src/main/cpp/Android.mk").isFile()
                || new File(gradleDir, "app/CMakeLists.txt").isFile()) {
            return true;
        }
        String[] gradleFiles = {"app/build.gradle", "app/build.gradle.kts", "build.gradle", "build.gradle.kts"};
        for (String rel : gradleFiles) {
            String text = readSmallText(new File(gradleDir, rel));
            if (text == null) continue;
            String lower = text.toLowerCase(Locale.US);
            if (lower.contains("compose") || lower.contains("externalnativebuild")
                    || lower.contains("ndkversion") || lower.contains("cmake")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolve effective build mode ("local" or "cloud") given user preference,
     * whether cloud is configured, and whether the project uses Compose/NDK.
     */
    public static String resolveEffectiveMode(String preferredMode, boolean cloudConfigured, boolean heavyProject) {
        if (MODE_CLOUD.equals(preferredMode) && cloudConfigured) {
            return MODE_CLOUD;
        }
        if (MODE_AUTO.equals(preferredMode) && heavyProject && cloudConfigured) {
            return MODE_CLOUD;
        }
        return MODE_LOCAL;
    }

    /**
     * Build the native Termux shell script that installs OpenJDK 17, ARM64 aapt2,
     * Gradle, apksigner, and sets up the Android SDK + offline init script.
     */
    public static String buildLocalToolchainInstallCommand(String prefixPath, String homePath) {
        String sdkRoot = prefixPath + "/opt/android-sdk";
        String gradleInitDir = homePath + "/.gradle/init.d";
        String aapt2Path = prefixPath + "/bin/aapt2";
        return "export TERMUX_PKG_NO_MIRROR_SELECT=1; "
                + "echo '__XYZ_PROGRESS__:8:Updating Termux package index...'; "
                + "pkg update -y && "
                + "echo '__XYZ_PROGRESS__:22:Downloading OpenJDK 17, ARM64 aapt2 & Gradle...'; "
                + "{ pkg install -y openjdk-17 aapt2 gradle apksigner android-tools dx ecj wget unzip zip || "
                + "pkg install -y openjdk-21 aapt2 gradle apksigner android-tools wget unzip zip || "
                + "pkg install -y openjdk-17 aapt2 gradle apksigner wget unzip zip; } && "
                + "echo '__XYZ_PROGRESS__:80:Configuring Android SDK & build-tools...'; "
                + "mkdir -p '" + sdkRoot + "/platforms/android-" + DEFAULT_COMPILE_SDK + "' "
                + "'" + sdkRoot + "/build-tools/" + DEFAULT_BUILD_TOOLS + "' "
                + "'" + sdkRoot + "/platform-tools' "
                + "'" + gradleInitDir + "' && "
                + "if [ -x '" + aapt2Path + "' ]; then "
                + "  ln -sf '" + aapt2Path + "' '" + sdkRoot + "/build-tools/" + DEFAULT_BUILD_TOOLS + "/aapt2'; "
                + "fi && "
                + "if [ -x '" + prefixPath + "/bin/adb' ]; then "
                + "  ln -sf '" + prefixPath + "/bin/adb' '" + sdkRoot + "/platform-tools/adb'; "
                + "fi && "
                + "echo '__XYZ_PROGRESS__:92:Writing ARM64 Gradle override...'; "
                + "cat > '" + gradleInitDir + "/xyzide-arm64.gradle' << 'EOF'\n"
                + buildGradleInitScript(aapt2Path)
                + "EOF\n"
                + "echo '__XYZ_PROGRESS__:100:Local ARM64 Android build toolchain ready!'";
    }

    /** True when Termux {@code adb} binary is installed. */
    public static boolean isAdbInstalled(String prefixPath) {
        if (prefixPath == null || prefixPath.isEmpty()) return false;
        return new File(prefixPath + "/bin/adb").exists()
                || new File(prefixPath + "/opt/android-sdk/platform-tools/adb").exists();
    }

    /**
     * Install native Termux {@code android-tools} ({@code adb}) and also install {@code adb} inside
     * the Ubuntu PRoot workshop if present, with progress markers for {@code BuildLogDialog}.
     */
    public static String buildAdbInstallCommand(String prefixPath) {
        String sdkPlatformTools = prefixPath + "/opt/android-sdk/platform-tools";
        return "export TERMUX_PKG_NO_MIRROR_SELECT=1; "
                + "echo '__XYZ_PROGRESS__:10:Updating Termux package list...'; "
                + "pkg update -y && "
                + "echo '__XYZ_PROGRESS__:35:Installing android-tools (adb & fastboot)...'; "
                + "pkg install -y android-tools && "
                + "mkdir -p '" + sdkPlatformTools + "' && "
                + "if [ -x '" + prefixPath + "/bin/adb' ]; then ln -sf '" + prefixPath + "/bin/adb' '" + sdkPlatformTools + "/adb'; fi && "
                + "echo '__XYZ_PROGRESS__:70:Checking Ubuntu PRoot adb bridge...'; "
                + "if command -v proot-distro >/dev/null 2>&1 && [ -d '" + prefixPath + "/var/lib/proot-distro/installed-rootfs/ubuntu' ]; then "
                + "  proot-distro login ubuntu --shared-tmp -- bash -c 'export DEBIAN_FRONTEND=noninteractive; apt-get update -y && (apt-get install -y adb || apt-get install -y android-tools-adb) || true'; "
                + "fi; "
                + "echo '__XYZ_PROGRESS__:100:ADB installed for Termux & Ubuntu!'" ;
    }

    /**
     * Build a command that installs {@code adb} if missing, optionally pairs via Wireless Debugging
     * ({@code adb pair localhost:<pairPort> <code>}), connects {@code 127.0.0.1:<connectPort>},
     * and prints {@code adb devices}.
     */
    public static String buildAdbPairAndConnectCommand(String prefixPath, String pairPort,
                                                       String pairCode, String connectPort) {
        String safePairPort = pairPort == null ? "" : pairPort.trim().replaceAll("[^0-9]", "");
        String safeCode = pairCode == null ? "" : pairCode.trim().replaceAll("[^0-9A-Za-z]", "");
        String safeConnectPort = connectPort == null ? "" : connectPort.trim().replaceAll("[^0-9]", "");
        StringBuilder sb = new StringBuilder();
        sb.append("export PATH=\"").append(prefixPath).append("/bin:$PATH\"; ");
        sb.append("if ! command -v adb >/dev/null 2>&1; then ");
        sb.append("echo '__XYZ_PROGRESS__:20:Installing android-tools (adb)...'; ");
        sb.append("export TERMUX_PKG_NO_MIRROR_SELECT=1; pkg update -y && pkg install -y android-tools; ");
        sb.append("fi; ");
        if (!safePairPort.isEmpty() && !safeCode.isEmpty()) {
            sb.append("echo '__XYZ_PROGRESS__:55:Pairing Wireless Debugging...'; ");
            sb.append("adb pair 127.0.0.1:").append(safePairPort).append(" '").append(safeCode).append("' && ");
        }
        if (!safeConnectPort.isEmpty()) {
            sb.append("echo '__XYZ_PROGRESS__:80:Connecting adb connect 127.0.0.1:").append(safeConnectPort).append("...'; ");
            sb.append("adb connect 127.0.0.1:").append(safeConnectPort).append(" && ");
        }
        sb.append("echo '__XYZ_PROGRESS__:95:Checking connected ADB devices...'; ");
        sb.append("adb devices && echo '__XYZ_PROGRESS__:100:Wireless ADB connected!'");
        return sb.toString();
    }

    public static final class ProgressUpdate {
        public final int percent;
        public final String stage;

        public ProgressUpdate(int percent, String stage) {
            this.percent = percent;
            this.stage = stage;
        }
    }

    private static final String PROGRESS_PREFIX = "__XYZ_PROGRESS__:";

    /**
     * Parse one line of setup or build output into a 0..100 progress update,
     * advancing smoothly through apt/pkg download & Gradle build stages.
     */
    public static ProgressUpdate parseProgressLine(String line, int currentPercent) {
        int pct = Math.max(1, Math.min(100, currentPercent));
        if (line == null) return new ProgressUpdate(pct, null);
        String trimmed = line.trim();
        int idx = trimmed.indexOf(PROGRESS_PREFIX);
        if (idx >= 0) {
            String rest = trimmed.substring(idx + PROGRESS_PREFIX.length());
            int colon = rest.indexOf(':');
            if (colon > 0) {
                try {
                    int parsed = Integer.parseInt(rest.substring(0, colon).trim());
                    String stage = rest.substring(colon + 1).trim();
                    return new ProgressUpdate(Math.max(1, Math.min(100, parsed)), stage);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        String lower = trimmed.toLowerCase(Locale.US);
        if (lower.startsWith("get:") || lower.contains("downloading") || lower.contains("fetched")) {
            int next = Math.min(60, Math.max(pct + 2, 25));
            return new ProgressUpdate(next, "Downloading packages...");
        }
        if (lower.startsWith("unpacking") || lower.startsWith("preparing to unpack")) {
            int next = Math.min(72, Math.max(pct + 2, 55));
            return new ProgressUpdate(next, "Unpacking toolchain packages...");
        }
        if (lower.startsWith("setting up")) {
            int next = Math.min(79, Math.max(pct + 2, 68));
            return new ProgressUpdate(next, "Configuring installed packages...");
        }
        if (lower.startsWith("> configure project")) {
            return new ProgressUpdate(Math.max(pct, 20), "Configuring Gradle project...");
        }
        if (lower.startsWith("> task :")) {
            int next = Math.min(95, Math.max(pct + 3, 30));
            return new ProgressUpdate(next, trimmed.replace("> Task ", "Running "));
        }
        if (lower.contains("build successful")) {
            return new ProgressUpdate(100, "Build completed!");
        }
        return new ProgressUpdate(pct, null);
    }

    /**
     * Gradle init script so every on-device Android build automatically uses the
     * ARM64 Termux aapt2 binary instead of x86_64 Maven artifacts.
     */
    public static String buildGradleInitScript(String aapt2Path) {
        return "gradle.projectsLoaded {\n"
                + "    allprojects { proj ->\n"
                + "        if (proj.extensions.extraProperties != null) {\n"
                + "            proj.extensions.extraProperties.set('android.aapt2FromMavenOverride', '" + aapt2Path + "')\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
    }

    /**
     * Build the native Termux shell command to run an on-device debug APK build.
     */
    public static String buildLocalGradleCommand(File workspace, String prefixPath, String homePath) {
        String sdkRoot = prefixPath + "/opt/android-sdk";
        String aapt2Path = prefixPath + "/bin/aapt2";
        File gradleDir = resolveAndroidGradleDir(workspace);
        String ws = (gradleDir != null ? gradleDir : workspace).getAbsolutePath().replace("'", "'\\''");
        return "export TERMUX_PKG_NO_MIRROR_SELECT=1; "
                + "export ANDROID_HOME='" + sdkRoot + "'; "
                + "export ANDROID_SDK_ROOT='" + sdkRoot + "'; "
                + "export JAVA_HOME=\"$(dirname $(dirname $(readlink -f $(command -v java))))\"; "
                + "export PATH=\"" + prefixPath + "/bin:$PATH\"; "
                + "cd '" + ws + "' && "
                + "BUILD_TOOL=$([ -x ./gradlew ] && echo './gradlew' || echo 'gradle'); "
                + "$BUILD_TOOL assembleDebug --no-daemon "
                + "-Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=512m' "
                + "-Pandroid.aapt2FromMavenOverride='" + aapt2Path + "'";
    }

    /**
     * Generate a GitHub Actions workflow YAML for Cloud Build (handles Compose, NDK, etc.).
     */
    public static String buildGitHubWorkflowYaml() {
        return "name: Xyz.IDE Cloud Android Build\n"
                + "\n"
                + "on:\n"
                + "  workflow_dispatch:\n"
                + "\n"
                + "jobs:\n"
                + "  build:\n"
                + "    runs-on: ubuntu-latest\n"
                + "    steps:\n"
                + "      - uses: actions/checkout@v4\n"
                + "      - name: Set up JDK 17\n"
                + "        uses: actions/setup-java@v4\n"
                + "        with:\n"
                + "          distribution: temurin\n"
                + "          java-version: '17'\n"
                + "      - name: Setup Android SDK\n"
                + "        uses: android-actions/setup-android@v3\n"
                + "      - name: Grant execute permission for gradlew\n"
                + "        run: chmod +x gradlew || true\n"
                + "      - name: Build Debug APK\n"
                + "        run: |\n"
                + "          if [ -f ./gradlew ]; then\n"
                + "            ./gradlew assembleDebug --no-daemon\n"
                + "          else\n"
                + "            gradle assembleDebug --no-daemon\n"
                + "          fi\n"
                + "      - name: Upload APK Artifact\n"
                + "        uses: actions/upload-artifact@v4\n"
                + "        with:\n"
                + "          name: app-debug-apk\n"
                + "          path: '**/build/outputs/apk/debug/*.apk'\n";
    }

    /**
     * Create a pre-configured Android SDK template project (compileSdk 34, AndroidX
     * AppCompat + Material, ARM64 aapt2 override, and GitHub Cloud Build workflow).
     */
    public static void createPreconfiguredTemplate(File targetDir, String appName,
                                                   String packageName, String prefixPath) throws IOException {
        if (targetDir == null) throw new IOException("Target directory is null");
        String safeAppName = (appName == null || appName.trim().isEmpty()) ? "MyAndroidApp" : appName.trim();
        String safePkg = (packageName == null || packageName.trim().isEmpty())
                ? "com.example.myandroidapp" : packageName.trim().toLowerCase(Locale.US);
        String pkgPath = safePkg.replace('.', '/');

        String aapt2Path = prefixPath + "/bin/aapt2";

        writeText(new File(targetDir, "settings.gradle"),
                "pluginManagement {\n"
                        + "    repositories {\n"
                        + "        google()\n"
                        + "        mavenCentral()\n"
                        + "        gradlePluginPortal()\n"
                        + "    }\n"
                        + "}\n"
                        + "dependencyResolutionManagement {\n"
                        + "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n"
                        + "    repositories {\n"
                        + "        google()\n"
                        + "        mavenCentral()\n"
                        + "    }\n"
                        + "}\n"
                        + "rootProject.name = '" + safeAppName.replace("'", "") + "'\n"
                        + "include ':app'\n");

        writeText(new File(targetDir, "build.gradle"),
                "plugins {\n"
                        + "    id 'com.android.application' version '8.5.2' apply false\n"
                        + "}\n");

        writeText(new File(targetDir, "gradle.properties"),
                "org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8\n"
                        + "android.useAndroidX=true\n"
                        + "android.nonTransitiveRClass=true\n"
                        + "android.aapt2FromMavenOverride=" + aapt2Path + "\n");

        writeText(new File(targetDir, "app/build.gradle"),
                "plugins {\n"
                        + "    id 'com.android.application'\n"
                        + "}\n\n"
                        + "android {\n"
                        + "    namespace '" + safePkg + "'\n"
                        + "    compileSdk " + DEFAULT_COMPILE_SDK + "\n\n"
                        + "    defaultConfig {\n"
                        + "        applicationId '" + safePkg + "'\n"
                        + "        minSdk 24\n"
                        + "        targetSdk " + DEFAULT_COMPILE_SDK + "\n"
                        + "        versionCode 1\n"
                        + "        versionName '1.0'\n"
                        + "    }\n\n"
                        + "    compileOptions {\n"
                        + "        sourceCompatibility JavaVersion.VERSION_17\n"
                        + "        targetCompatibility JavaVersion.VERSION_17\n"
                        + "    }\n"
                        + "}\n\n"
                        + "dependencies {\n"
                        + "    implementation 'androidx.appcompat:appcompat:1.7.0'\n"
                        + "    implementation 'com.google.android.material:material:1.12.0'\n"
                        + "    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'\n"
                        + "}\n");

        writeText(new File(targetDir, "app/src/main/AndroidManifest.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
                        + "    <application\n"
                        + "        android:allowBackup=\"true\"\n"
                        + "        android:label=\"@string/app_name\"\n"
                        + "        android:theme=\"@style/Theme.Material3.DayNight.NoActionBar\">\n"
                        + "        <activity\n"
                        + "            android:name=\".MainActivity\"\n"
                        + "            android:exported=\"true\">\n"
                        + "            <intent-filter>\n"
                        + "                <action android:name=\"android.intent.action.MAIN\" />\n"
                        + "                <category android:name=\"android.intent.category.LAUNCHER\" />\n"
                        + "            </intent-filter>\n"
                        + "        </activity>\n"
                        + "    </application>\n"
                        + "</manifest>\n");

        writeText(new File(targetDir, "app/src/main/res/values/strings.xml"),
                "<resources>\n"
                        + "    <string name=\"app_name\">" + safeAppName + "</string>\n"
                        + "</resources>\n");

        writeText(new File(targetDir, "app/src/main/res/layout/activity_main.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                        + "    android:layout_width=\"match_parent\"\n"
                        + "    android:layout_height=\"match_parent\"\n"
                        + "    android:gravity=\"center\"\n"
                        + "    android:orientation=\"vertical\"\n"
                        + "    android:padding=\"24dp\">\n\n"
                        + "    <TextView\n"
                        + "        android:id=\"@+id/tv_hello\"\n"
                        + "        android:layout_width=\"wrap_content\"\n"
                        + "        android:layout_height=\"wrap_content\"\n"
                        + "        android:text=\"Built on Xyz.IDE!\"\n"
                        + "        android:textSize=\"20sp\" />\n"
                        + "</LinearLayout>\n");

        writeText(new File(targetDir, "app/src/main/java/" + pkgPath + "/MainActivity.java"),
                "package " + safePkg + ";\n\n"
                        + "import android.os.Bundle;\n"
                        + "import androidx.appcompat.app.AppCompatActivity;\n\n"
                        + "public class MainActivity extends AppCompatActivity {\n"
                        + "    @Override\n"
                        + "    protected void onCreate(Bundle savedInstanceState) {\n"
                        + "        super.onCreate(savedInstanceState);\n"
                        + "        setContentView(R.layout.activity_main);\n"
                        + "    }\n"
                        + "}\n");

        writeText(new File(targetDir, ".github/workflows/android-build.yml"),
                buildGitHubWorkflowYaml());
        DebugCrashReporterConfig.injectDebugCrashReporter(targetDir);
        syncWorkspaceAgentDocs(targetDir, prefixPath, MODE_LOCAL, false, null);
    }

    /**
     * Build the managed Markdown section describing Xyz.IDE's Local ARM64 &
     * Cloud Android Build setup so any CLI coding agent (Antigravity/Gemini,
     * Claude Code, OpenCode, Codex, Kiro, Kilo, Grok) automatically knows
     * how to build and test APKs in this workspace. Never includes secrets.
     */
    public static String buildAgentContextSection(String prefixPath, String buildMode,
                                                  boolean localToolchainReady,
                                                  String cloudSummary) {
        String sdkRoot = prefixPath + "/opt/android-sdk";
        String aapt2Path = prefixPath + "/bin/aapt2";
        String mode = (buildMode == null || buildMode.isEmpty()) ? MODE_LOCAL : buildMode;
        String cloudInfo = (cloudSummary == null || cloudSummary.isEmpty())
                ? "Not configured (user can configure in Xyz.IDE Settings)"
                : cloudSummary;
        boolean adbReady = isAdbInstalled(prefixPath);
        return CONTEXT_START + "\n"
                + "## Xyz.IDE Android Build Environment\n\n"
                + "- **App / Environment**: Xyz.IDE (`com.xyzide`) on Android ARM64 (Termux + PRoot Ubuntu workshop).\n"
                + "- **Storage Mapping**: Host `/storage/emulated/0/...` maps to `/mnt/sdcard/...` inside PRoot.\n"
                + "- **Configured Default Build Mode**: `" + mode + "` (`local` = Native Termux ARM64, `cloud` = GitHub Actions / Remote, `auto` = Cloud for Compose/NDK).\n"
                + "- **Local ARM64 Toolchain Status**: " + (localToolchainReady ? "Ready" : "Not downloaded yet (user can tap Settings -> Download Local)") + "\n"
                + "- **Wireless ADB Status**: " + (adbReady ? "`adb` installed (use `adb devices` / `adb logcat` when paired via Wireless Debugging)" : "Not installed yet (tap Settings -> Install adb)") + "\n"
                + "- **Cloud Build Status**: " + cloudInfo + "\n\n"
                + "### How to Build APKs On-Device (Native Termux ARM64)\n"
                + "- Always pass `-Pandroid.aapt2FromMavenOverride=" + aapt2Path + "` (stock Maven `aapt2` is x86_64 and fails on ARM64).\n"
                + "- `ANDROID_HOME` / `ANDROID_SDK_ROOT`: `" + sdkRoot + "`\n"
                + "- Recommended Gradle command:\n"
                + "  ```bash\n"
                + "  export ANDROID_HOME=\"" + sdkRoot + "\"\n"
                + "  export ANDROID_SDK_ROOT=\"" + sdkRoot + "\"\n"
                + "  ./gradlew assembleDebug --no-daemon -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=512m' -Pandroid.aapt2FromMavenOverride=" + aapt2Path + "\n"
                + "  ```\n"
                + "- For Jetpack Compose or C/C++ NDK projects, prefer Cloud Build (`.github/workflows/android-build.yml`) when configured.\n"
                + "- **Auto Crash & Logcat Capture**: Debug APKs auto-include `XyzCrashReporterProvider` (in `app/src/debug/` or `android/app/src/debug/`). Native Java crashes, React Native JS errors (`xyzide-crash-hook.js` -> `127.0.0.1:49876`), and Flutter Dart errors (`lib/xyzide_crash_hook.dart` -> `127.0.0.1:49876`) automatically write stacktraces & PID `logcat` to `" + DebugCrashReporterConfig.REPORT_FILE_NAME + "` in the workspace root.\n"
                + CONTEXT_END;
    }

    /**
     * Insert or update the managed Xyz.IDE build section inside {@code existingContent}
     * while preserving all user-written markdown outside the markers.
     */
    public static String upsertAgentContextBlock(String existingContent, String managedSection) {
        return upsertMarkedSection(existingContent, CONTEXT_START, CONTEXT_END, managedSection);
    }

    /**
     * Insert or replace a marker-delimited section in a workspace agent doc file.
     */
    public static String upsertMarkedSection(String existingContent, String startMarker,
                                             String endMarker, String managedSection) {
        if (existingContent == null || existingContent.trim().isEmpty()) {
            return "# Project Agent Instructions\n\n" + managedSection + "\n";
        }
        int start = existingContent.indexOf(startMarker);
        int end = existingContent.indexOf(endMarker);
        if (start >= 0 && end > start) {
            int afterEnd = end + endMarker.length();
            return existingContent.substring(0, start) + managedSection + existingContent.substring(afterEnd);
        }
        String trimmed = existingContent.replaceAll("\\s+$", "");
        return trimmed + "\n\n" + managedSection + "\n";
    }

    /**
     * Automatically create/update {@code GEMINI.md}, {@code AGENTS.md}, and
     * {@code CLAUDE.md} in {@code workspace} so whichever CLI agent is used
     * immediately has the current build, browser (Playwright + WebView), & environment instructions.
     */
    public static void syncWorkspaceAgentDocs(File workspace, String prefixPath,
                                              String buildMode, boolean localToolchainReady,
                                              String cloudSummary) {
        if (workspace == null || !workspace.isDirectory()) return;
        String buildSection = buildAgentContextSection(prefixPath, buildMode, localToolchainReady, cloudSummary);
        boolean playwrightReady = BrowserAutomationConfig.isPlaywrightReady(prefixPath);
        String browserSection = BrowserAutomationConfig.buildBrowserAgentContextSection(playwrightReady);
        for (String fileName : new String[]{"GEMINI.md", "AGENTS.md", "CLAUDE.md"}) {
            File target = new File(workspace, fileName);
            try {
                String existing = readSmallText(target);
                String updated = upsertMarkedSection(existing, CONTEXT_START, CONTEXT_END, buildSection);
                updated = upsertMarkedSection(
                        updated,
                        BrowserAutomationConfig.CONTEXT_START,
                        BrowserAutomationConfig.CONTEXT_END,
                        browserSection);
                if (!updated.equals(existing)) {
                    writeText(target, updated);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void writeText(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create directory: " + parent.getAbsolutePath());
        }
        try (FileWriter fw = new FileWriter(file)) {
            fw.write(content);
        }
    }

    private static String readSmallText(File file) {
        if (file == null || !file.isFile()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n = in.read(buf);
            if (n <= 0) return null;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }
}
