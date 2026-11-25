package com.termux.app.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;

public class AndroidBuildConfigTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void toolchainInstallScriptContainsArm64ToolsAndInitOverride() {
        String cmd = AndroidBuildConfig.buildLocalToolchainInstallCommand(
                "/data/data/com.xyzide/files/usr",
                "/data/data/com.xyzide/files/home");
        assertTrue(cmd.contains("openjdk-17"));
        assertTrue(cmd.contains("aapt2"));
        assertTrue(cmd.contains("gradle"));
        assertTrue(cmd.contains("apksigner"));
        assertTrue(cmd.contains("android-tools"));
        assertTrue(cmd.contains("android.aapt2FromMavenOverride"));
        assertTrue(cmd.contains("xyzide-arm64.gradle"));
    }

    @Test
    public void adbHelpersBuildInstallAndPairCommands() throws Exception {
        File prefix = tmp.newFolder("prefix");
        assertFalse(AndroidBuildConfig.isAdbInstalled(prefix.getAbsolutePath()));
        new File(prefix, "bin").mkdirs();
        assertTrue(new File(prefix, "bin/adb").createNewFile());
        assertTrue(AndroidBuildConfig.isAdbInstalled(prefix.getAbsolutePath()));

        String installCmd = AndroidBuildConfig.buildAdbInstallCommand(prefix.getAbsolutePath());
        assertTrue(installCmd.contains("pkg install -y android-tools"));
        assertTrue(installCmd.contains("proot-distro login ubuntu"));

        String pairCmd = AndroidBuildConfig.buildAdbPairAndConnectCommand(
                prefix.getAbsolutePath(), "37123", "456789", "41567");
        assertTrue(pairCmd.contains("adb pair 127.0.0.1:37123 '456789'"));
        assertTrue(pairCmd.contains("adb connect 127.0.0.1:41567"));
        assertTrue(pairCmd.contains("adb devices"));
    }

    @Test
    public void localGradleCommandPassesAapt2OverrideAndSdkEnv() throws Exception {
        File ws = tmp.newFolder("my_app");
        String cmd = AndroidBuildConfig.buildLocalGradleCommand(
                ws,
                "/data/data/com.xyzide/files/usr",
                "/data/data/com.xyzide/files/home");
        assertTrue(cmd.contains("ANDROID_HOME='/data/data/com.xyzide/files/usr/opt/android-sdk'"));
        assertTrue(cmd.contains("assembleDebug --no-daemon"));
        assertTrue(cmd.contains("-Pandroid.aapt2FromMavenOverride='/data/data/com.xyzide/files/usr/bin/aapt2'"));
    }

    @Test
    public void resolvesEffectiveBuildModeForNormalAndHeavyProjects() {
        assertEquals(AndroidBuildConfig.MODE_LOCAL,
                AndroidBuildConfig.resolveEffectiveMode(AndroidBuildConfig.MODE_LOCAL, true, true));
        assertEquals(AndroidBuildConfig.MODE_CLOUD,
                AndroidBuildConfig.resolveEffectiveMode(AndroidBuildConfig.MODE_CLOUD, true, false));
        assertEquals(AndroidBuildConfig.MODE_LOCAL,
                AndroidBuildConfig.resolveEffectiveMode(AndroidBuildConfig.MODE_CLOUD, false, false));
        assertEquals(AndroidBuildConfig.MODE_CLOUD,
                AndroidBuildConfig.resolveEffectiveMode(AndroidBuildConfig.MODE_AUTO, true, true));
        assertEquals(AndroidBuildConfig.MODE_LOCAL,
                AndroidBuildConfig.resolveEffectiveMode(AndroidBuildConfig.MODE_AUTO, true, false));
    }

    @Test
    public void preconfiguredTemplateCreatesAndroidAndCloudFiles() throws Exception {
        File ws = tmp.newFolder("template_app");
        AndroidBuildConfig.createPreconfiguredTemplate(
                ws, "DemoApp", "com.example.demoapp", "/data/data/com.xyzide/files/usr");

        assertTrue(AndroidBuildConfig.isAndroidProject(ws));
        assertFalse(AndroidBuildConfig.isHeavyAndroidProject(ws));
        assertTrue(new File(ws, "build.gradle").isFile());
        assertTrue(new File(ws, "app/build.gradle").isFile());
        assertTrue(new File(ws, "gradle.properties").isFile());
        assertTrue(new File(ws, "app/src/main/AndroidManifest.xml").isFile());
        assertTrue(new File(ws, "app/src/main/java/com/example/demoapp/MainActivity.java").isFile());
        assertTrue(new File(ws, ".github/workflows/android-build.yml").isFile());
    }

    @Test
    public void detectsHeavyComposeAndNdkProjects() throws Exception {
        File composeWs = tmp.newFolder("compose_app");
        File appDir = new File(composeWs, "app");
        appDir.mkdirs();
        try (FileWriter fw = new FileWriter(new File(appDir, "build.gradle"))) {
            fw.write("android { buildFeatures { compose true } }");
        }
        assertTrue(AndroidBuildConfig.isAndroidProject(composeWs));
        assertTrue(AndroidBuildConfig.isHeavyAndroidProject(composeWs));

        File ndkWs = tmp.newFolder("ndk_app");
        File cppDir = new File(ndkWs, "app/src/main/cpp");
        cppDir.mkdirs();
        new File(cppDir, "CMakeLists.txt").createNewFile();
        assertTrue(AndroidBuildConfig.isHeavyAndroidProject(ndkWs));
    }

    @Test
    public void cloudWorkflowYamlContainsExpectedSteps() {
        String yaml = AndroidBuildConfig.buildGitHubWorkflowYaml();
        assertTrue(yaml.contains("workflow_dispatch"));
        assertTrue(yaml.contains("actions/setup-java@v4"));
        assertTrue(yaml.contains("android-actions/setup-android@v3"));
        assertTrue(yaml.contains("assembleDebug"));
        assertTrue(yaml.contains("actions/upload-artifact@v4"));
    }

    @Test
    public void syncWorkspaceAgentDocsCreatesAndUpdatesGeminiAgentsClaudeMdPreservingUserNotes() throws Exception {
        File ws = tmp.newFolder("agent_docs_ws");
        File gemini = new File(ws, "GEMINI.md");
        try (FileWriter fw = new FileWriter(gemini)) {
            fw.write("# My Custom Rules\n\nKeep code clean.\n");
        }

        AndroidBuildConfig.syncWorkspaceAgentDocs(
                ws, "/data/data/com.xyzide/files/usr",
                AndroidBuildConfig.MODE_LOCAL, true, "GitHub: user/app (main)");

        assertTrue(new File(ws, "GEMINI.md").isFile());
        assertTrue(new File(ws, "AGENTS.md").isFile());
        assertTrue(new File(ws, "CLAUDE.md").isFile());

        String content = new String(java.nio.file.Files.readAllBytes(gemini.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(content.contains("# My Custom Rules"));
        assertTrue(content.contains("Keep code clean."));
        assertTrue(content.contains("android.aapt2FromMavenOverride"));
        assertTrue(content.contains("GitHub: user/app (main)"));

        // Second sync updates the managed block in-place without duplicating it or erasing custom notes.
        AndroidBuildConfig.syncWorkspaceAgentDocs(
                ws, "/data/data/com.xyzide/files/usr",
                AndroidBuildConfig.MODE_CLOUD, false, "Remote: https://builder.test");
        String updated = new String(java.nio.file.Files.readAllBytes(gemini.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(updated.contains("# My Custom Rules"));
        assertTrue(updated.contains("`cloud`"));
        assertTrue(updated.contains("Remote: https://builder.test"));
        assertEquals(updated.indexOf("<!-- XYZIDE_ANDROID_BUILD_START -->"),
                updated.lastIndexOf("<!-- XYZIDE_ANDROID_BUILD_START -->"));
    }

    @Test
    public void parseProgressLineAdvancesSmoothlyFromOneToHundred() {
        AndroidBuildConfig.ProgressUpdate p1 =
                AndroidBuildConfig.parseProgressLine("__XYZ_PROGRESS__:8:Updating package index...", 1);
        assertEquals(8, p1.percent);
        assertEquals("Updating package index...", p1.stage);

        AndroidBuildConfig.ProgressUpdate p2 =
                AndroidBuildConfig.parseProgressLine("Get:1 https://packages.termux.dev/openjdk-17", p1.percent);
        assertTrue(p2.percent > p1.percent);

        AndroidBuildConfig.ProgressUpdate p3 =
                AndroidBuildConfig.parseProgressLine("> Task :app:assembleDebug", 40);
        assertTrue(p3.percent > 40);

        AndroidBuildConfig.ProgressUpdate p4 =
                AndroidBuildConfig.parseProgressLine("BUILD SUCCESSFUL in 12s", p3.percent);
        assertEquals(100, p4.percent);
    }
}
