package com.termux.app.build;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class DebugCrashReporterConfigTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void testInjectDebugCrashReporterIntoAndroidProject() throws Exception {
        File ws = tmp.newFolder("sample-app");
        new File(ws, "app/src/main").mkdirs();
        Files.write(new File(ws, "app/build.gradle").toPath(), "plugins {}".getBytes(StandardCharsets.UTF_8));

        boolean injected = DebugCrashReporterConfig.injectDebugCrashReporter(ws);
        assertTrue(injected);

        File manifest = new File(ws, "app/src/debug/AndroidManifest.xml");
        File provider = new File(ws, "app/src/debug/java/com/xyzide/debug/XyzCrashReporterProvider.java");
        assertTrue(manifest.isFile());
        assertTrue(provider.isFile());

        String mText = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
        assertTrue(mText.contains("XyzCrashReporterProvider"));
        assertTrue(mText.contains("com.xyzide"));

        String pText = new String(Files.readAllBytes(provider.toPath()), StandardCharsets.UTF_8);
        assertTrue(pText.contains("ACTION_REPORT_CRASH"));
        assertTrue(pText.contains("logcat"));
        assertTrue(pText.contains("setDefaultUncaughtExceptionHandler"));
        assertTrue(pText.contains("49876"));
    }

    @Test
    public void testInjectIntoReactNativeProjectWiresJsAndAndroidDebug() throws Exception {
        File ws = tmp.newFolder("rn-app");
        Files.write(new File(ws, "package.json").toPath(), "{\"name\":\"rn\"}".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(ws, "index.js").toPath(), "import {AppRegistry} from 'react-native';\n".getBytes(StandardCharsets.UTF_8));
        new File(ws, "android/app/src/main").mkdirs();
        Files.write(new File(ws, "android/app/build.gradle").toPath(), "plugins {}".getBytes(StandardCharsets.UTF_8));

        assertTrue(AndroidBuildConfig.isReactNativeProject(ws));
        assertTrue(AndroidBuildConfig.isAndroidProject(ws));
        assertTrue(DebugCrashReporterConfig.injectDebugCrashReporter(ws));

        assertTrue(new File(ws, "xyzide-crash-hook.js").isFile());
        assertTrue(new File(ws, "android/app/src/debug/java/com/xyzide/debug/XyzCrashReporterProvider.java").isFile());

        String idx = new String(Files.readAllBytes(new File(ws, "index.js").toPath()), StandardCharsets.UTF_8);
        assertTrue(idx.contains("require('./xyzide-crash-hook')"));
    }

    @Test
    public void testInjectIntoFlutterProjectWiresDartAndAndroidDebug() throws Exception {
        File ws = tmp.newFolder("flutter-app");
        Files.write(new File(ws, "pubspec.yaml").toPath(), "name: demo\n".getBytes(StandardCharsets.UTF_8));
        new File(ws, "lib").mkdirs();
        Files.write(new File(ws, "lib/main.dart").toPath(), "void main() {\n  runApp(const MyApp());\n}\n".getBytes(StandardCharsets.UTF_8));
        new File(ws, "android/app/src/main").mkdirs();
        Files.write(new File(ws, "android/app/build.gradle").toPath(), "plugins {}".getBytes(StandardCharsets.UTF_8));

        assertTrue(AndroidBuildConfig.isFlutterProject(ws));
        assertTrue(AndroidBuildConfig.isAndroidProject(ws));
        assertTrue(DebugCrashReporterConfig.injectDebugCrashReporter(ws));

        assertTrue(new File(ws, "lib/xyzide_crash_hook.dart").isFile());
        assertTrue(new File(ws, "android/app/src/debug/java/com/xyzide/debug/XyzCrashReporterProvider.java").isFile());

        String mainDart = new String(Files.readAllBytes(new File(ws, "lib/main.dart").toPath()), StandardCharsets.UTF_8);
        assertTrue(mainDart.contains("import 'xyzide_crash_hook.dart';"));
        assertTrue(mainDart.contains("installXyzCrashHook();"));
    }

    @Test
    public void testInjectSkipsNonAndroidWorkspace() throws Exception {
        File ws = tmp.newFolder("python-app");
        Files.write(new File(ws, "main.py").toPath(), "print(1)".getBytes(StandardCharsets.UTF_8));
        assertFalse(DebugCrashReporterConfig.injectDebugCrashReporter(ws));
    }

    @Test
    public void testFormatCrashReportMarkdownContainsAllSections() {
        String md = DebugCrashReporterConfig.formatCrashReportMarkdown(
                "com.example.demo",
                "java.lang.NullPointerException",
                "Attempt to invoke virtual method",
                "java.lang.NullPointerException\n\tat MainActivity.onCreate(MainActivity.java:24)",
                "01-01 00:00:00.000 E/AndroidRuntime: FATAL EXCEPTION: main",
                "OnePlus CPH (Android 15)",
                1700000000000L);

        assertTrue(md.contains("# Crash Report (com.example.demo)"));
        assertTrue(md.contains("## Stacktrace"));
        assertTrue(md.contains("MainActivity.java:24"));
        assertTrue(md.contains("## App Logcat (PID Tail)"));
        assertTrue(md.contains("FATAL EXCEPTION: main"));
    }

    @Test
    public void testAgentDocsDocumentAutoCrashReporter() throws Exception {
        File ws = tmp.newFolder("doc-ws");
        File prefix = tmp.newFolder("prefix");
        AndroidBuildConfig.syncWorkspaceAgentDocs(
                ws, prefix.getAbsolutePath(), AndroidBuildConfig.MODE_LOCAL, true, "Not configured");

        String doc = new String(
                Files.readAllBytes(new File(ws, "GEMINI.md").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(doc.contains("CRASH_REPORT.md"));
        assertTrue(doc.contains("XyzCrashReporterProvider"));
    }
}
