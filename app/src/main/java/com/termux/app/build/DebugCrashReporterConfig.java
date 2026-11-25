package com.termux.app.build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Auto-injects a lightweight, zero-dependency debug crash & logcat reporter
 * ({@code XyzCrashReporterProvider}) into Native Android, React Native ({@code android/}),
 * and Flutter ({@code android/}) projects' {@code debug} source set.
 * Release builds stay completely untouched.
 */
public final class DebugCrashReporterConfig {

    public static final String ACTION_REPORT_CRASH = "com.xyzide.app.ACTION_REPORT_CRASH";
    public static final String ACTION_FIX_CRASH = "com.xyzide.app.ACTION_FIX_CRASH";
    public static final String REPORT_FILE_NAME = "CRASH_REPORT.md";
    public static final String XYZIDE_PACKAGE = "com.xyzide";
    public static final String RECEIVER_CLASS = "com.termux.app.build.CrashReportReceiver";
    public static final int LOCAL_CRASH_BRIDGE_PORT = 49876;

    private static final String MARKER = "XyzCrashReporterProvider v2";
    private static final String JS_HOOK_IMPORT = "// [XYZIDE_CRASH_HOOK] Auto-capture JS errors in debug builds";
    private static final String DART_HOOK_MARKER = "// [XYZIDE_CRASH_HOOK]";

    private DebugCrashReporterConfig() {
    }

    /**
     * Inject debug crash/logcat reporting into Native Android, React Native, or Flutter workspaces.
     *
     * @return {@code true} if any file was created or updated.
     */
    public static boolean injectDebugCrashReporter(File workspace) throws IOException {
        if (workspace == null || !workspace.isDirectory()) return false;
        File gradleDir = AndroidBuildConfig.resolveAndroidGradleDir(workspace);
        if (gradleDir == null) return false;
        File appDir = new File(gradleDir, "app");
        if (!appDir.isDirectory()) {
            return false;
        }

        boolean changed = false;

        File debugManifest = new File(appDir, "src/debug/AndroidManifest.xml");
        File debugProvider = new File(appDir, "src/debug/java/com/xyzide/debug/XyzCrashReporterProvider.java");

        String existingManifest = readText(debugManifest);
        if (existingManifest == null || existingManifest.contains("xyzcrashinit")
                || existingManifest.contains("XyzCrashReporterProvider")) {
            String targetManifest = buildDebugManifestXml();
            if (!targetManifest.equals(existingManifest)) {
                writeText(debugManifest, targetManifest);
                changed = true;
            }
        }

        String existingProvider = readText(debugProvider);
        String targetProvider = buildCrashReporterProviderSource();
        if (existingProvider == null || !targetProvider.equals(existingProvider)) {
            writeText(debugProvider, targetProvider);
            changed = true;
        }

        if (AndroidBuildConfig.isReactNativeProject(workspace)) {
            changed |= injectReactNativeJsHook(workspace);
        }
        if (AndroidBuildConfig.isFlutterProject(workspace)) {
            changed |= injectFlutterDartHook(workspace);
        }

        return changed;
    }

    static boolean injectReactNativeJsHook(File workspace) throws IOException {
        boolean changed = false;
        File hookFile = new File(workspace, "xyzide-crash-hook.js");
        String hookBody = buildReactNativeJsHook();
        if (!hookBody.equals(readText(hookFile))) {
            writeText(hookFile, hookBody);
            changed = true;
        }

        for (String entryName : new String[]{"index.js", "index.tsx", "index.ts", "App.js", "App.tsx"}) {
            File entry = new File(workspace, entryName);
            if (!entry.isFile()) continue;
            String content = readText(entry);
            if (content != null && !content.contains("xyzide-crash-hook")) {
                String prefix = JS_HOOK_IMPORT + "\n"
                        + "if (typeof __DEV__ !== 'undefined' && __DEV__) { try { require('./xyzide-crash-hook'); } catch (_) {} }\n";
                writeText(entry, prefix + content);
                changed = true;
            }
            break;
        }
        return changed;
    }

    static boolean injectFlutterDartHook(File workspace) throws IOException {
        boolean changed = false;
        File libDir = new File(workspace, "lib");
        if (!libDir.isDirectory()) return false;

        File hookFile = new File(libDir, "xyzide_crash_hook.dart");
        String hookBody = buildFlutterDartHook();
        if (!hookBody.equals(readText(hookFile))) {
            writeText(hookFile, hookBody);
            changed = true;
        }

        File mainDart = new File(libDir, "main.dart");
        String content = readText(mainDart);
        if (content != null && !content.contains("xyzide_crash_hook.dart")) {
            String updated = "import 'xyzide_crash_hook.dart'; " + DART_HOOK_MARKER + "\n" + content;
            String wired = updated.replaceFirst(
                    "(void\\s+main\\s*\\([^)]*\\)\\s*(?:async\\s*)?\\{)",
                    "$1\n  installXyzCrashHook(); " + DART_HOOK_MARKER);
            if (!wired.equals(updated)) {
                writeText(mainDart, wired);
                changed = true;
            } else {
                writeText(mainDart, updated);
                changed = true;
            }
        }
        return changed;
    }

    public static String buildReactNativeJsHook() {
        return "// Auto-managed by Xyz.IDE - captures React Native JS errors in __DEV__ builds\n"
                + "(function () {\n"
                + "  if (typeof global === 'undefined' || !global.__DEV__ || global.__xyzHookInstalled) return;\n"
                + "  global.__xyzHookInstalled = true;\n"
                + "  function send(title, message, stack) {\n"
                + "    try {\n"
                + "      fetch('http://127.0.0.1:" + LOCAL_CRASH_BRIDGE_PORT + "/crash', {\n"
                + "        method: 'POST',\n"
                + "        headers: { 'Content-Type': 'application/json' },\n"
                + "        body: JSON.stringify({\n"
                + "          runtime: 'React Native JS',\n"
                + "          exception_class: title || 'JSError',\n"
                + "          error_message: String(message || ''),\n"
                + "          stacktrace: String(stack || '')\n"
                + "        })\n"
                + "      }).catch(function () {});\n"
                + "    } catch (_) {}\n"
                + "  }\n"
                + "  if (global.ErrorUtils && typeof global.ErrorUtils.getGlobalHandler === 'function') {\n"
                + "    var prev = global.ErrorUtils.getGlobalHandler();\n"
                + "    global.ErrorUtils.setGlobalHandler(function (err, isFatal) {\n"
                + "      var title = (isFatal ? 'Fatal ' : '') + (err && err.name ? err.name : 'JSError');\n"
                + "      send(title, err && err.message ? err.message : String(err), err && err.stack ? err.stack : '');\n"
                + "      if (typeof prev === 'function') prev(err, isFatal);\n"
                + "    });\n"
                + "  }\n"
                + "})();\n";
    }

    public static String buildFlutterDartHook() {
        return "// Auto-managed by Xyz.IDE - forwards Flutter/Dart errors in debug mode\n"
                + "import 'dart:convert';\n"
                + "import 'dart:io';\n"
                + "import 'dart:ui';\n"
                + "import 'package:flutter/foundation.dart';\n\n"
                + "bool _xyzInstalled = false;\n\n"
                + "void installXyzCrashHook() {\n"
                + "  if (!kDebugMode || _xyzInstalled) return;\n"
                + "  _xyzInstalled = true;\n\n"
                + "  final prevFlutterError = FlutterError.onError;\n"
                + "  FlutterError.onError = (FlutterErrorDetails details) {\n"
                + "    _sendToXyz('FlutterError', details.exceptionAsString(), details.stack?.toString() ?? '');\n"
                + "    if (prevFlutterError != null) {\n"
                + "      prevFlutterError(details);\n"
                + "    } else {\n"
                + "      FlutterError.presentError(details);\n"
                + "    }\n"
                + "  };\n\n"
                + "  final prevPlatformError = PlatformDispatcher.instance.onError;\n"
                + "  PlatformDispatcher.instance.onError = (Object error, StackTrace stack) {\n"
                + "    _sendToXyz(error.runtimeType.toString(), error.toString(), stack.toString());\n"
                + "    if (prevPlatformError != null) {\n"
                + "      return prevPlatformError(error, stack);\n"
                + "    }\n"
                + "    return false;\n"
                + "  };\n"
                + "}\n\n"
                + "void _sendToXyz(String type, String message, String stack) {\n"
                + "  HttpClient()\n"
                + "      .post('127.0.0.1', " + LOCAL_CRASH_BRIDGE_PORT + ", '/crash')\n"
                + "      .then((req) {\n"
                + "        req.headers.contentType = ContentType.json;\n"
                + "        req.write(jsonEncode({\n"
                + "          'runtime': 'Flutter Dart',\n"
                + "          'exception_class': type,\n"
                + "          'error_message': message,\n"
                + "          'stacktrace': stack,\n"
                + "        }));\n"
                + "        return req.close();\n"
                + "      })\n"
                + "      .catchError((_) {});\n"
                + "}\n";
    }

    public static String buildDebugManifestXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<!-- Auto-managed by Xyz.IDE (" + MARKER + ") - Debug builds only -->\n"
                + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n\n"
                + "    <uses-permission android:name=\"android.permission.INTERNET\" />\n\n"
                + "    <queries>\n"
                + "        <package android:name=\"" + XYZIDE_PACKAGE + "\" />\n"
                + "    </queries>\n\n"
                + "    <application android:usesCleartextTraffic=\"true\">\n"
                + "        <provider\n"
                + "            android:name=\"com.xyzide.debug.XyzCrashReporterProvider\"\n"
                + "            android:authorities=\"${applicationId}.xyzcrashinit\"\n"
                + "            android:exported=\"false\" />\n"
                + "    </application>\n\n"
                + "</manifest>\n";
    }

    public static String buildCrashReporterProviderSource() {
        return "package com.xyzide.debug;\n\n"
                + "import android.content.ComponentName;\n"
                + "import android.content.ContentProvider;\n"
                + "import android.content.ContentValues;\n"
                + "import android.content.Context;\n"
                + "import android.content.Intent;\n"
                + "import android.database.Cursor;\n"
                + "import android.net.Uri;\n"
                + "import android.os.Build;\n"
                + "import android.os.Process;\n"
                + "import org.json.JSONObject;\n"
                + "import java.io.BufferedReader;\n"
                + "import java.io.InputStreamReader;\n"
                + "import java.io.OutputStream;\n"
                + "import java.io.PrintWriter;\n"
                + "import java.io.StringWriter;\n"
                + "import java.net.InetAddress;\n"
                + "import java.net.ServerSocket;\n"
                + "import java.net.Socket;\n"
                + "import java.nio.charset.StandardCharsets;\n\n"
                + "/**\n"
                + " * " + MARKER + "\n"
                + " * Captures uncaught Java exceptions, React Native JS errors & Flutter Dart errors\n"
                + " * (+ current-PID logcat) in debug APKs and forwards them to Xyz.IDE.\n"
                + " */\n"
                + "public class XyzCrashReporterProvider extends ContentProvider {\n\n"
                + "    private static volatile Context appContext;\n\n"
                + "    @Override\n"
                + "    public boolean onCreate() {\n"
                + "        Context ctx = getContext();\n"
                + "        if (ctx != null) {\n"
                + "            appContext = ctx.getApplicationContext();\n"
                + "            installHandler(appContext);\n"
                + "            startLoopbackBridge(appContext);\n"
                + "        }\n"
                + "        return true;\n"
                + "    }\n\n"
                + "    private static void installHandler(final Context context) {\n"
                + "        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();\n"
                + "        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {\n"
                + "            try {\n"
                + "                sendThrowable(context, throwable);\n"
                + "            } catch (Throwable ignored) {\n"
                + "            }\n"
                + "            if (previous != null) {\n"
                + "                previous.uncaughtException(thread, throwable);\n"
                + "            } else {\n"
                + "                Process.killProcess(Process.myPid());\n"
                + "                System.exit(10);\n"
                + "            }\n"
                + "        });\n"
                + "    }\n\n"
                + "    private static void startLoopbackBridge(final Context context) {\n"
                + "        Thread t = new Thread(() -> {\n"
                + "            try (ServerSocket server = new ServerSocket(" + LOCAL_CRASH_BRIDGE_PORT + ", 4, InetAddress.getByName(\"127.0.0.1\"))) {\n"
                + "                while (!server.isClosed()) {\n"
                + "                    try (Socket client = server.accept()) {\n"
                + "                        BufferedReader br = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));\n"
                + "                        String line;\n"
                + "                        int contentLength = 0;\n"
                + "                        while ((line = br.readLine()) != null && !line.isEmpty()) {\n"
                + "                            if (line.toLowerCase().startsWith(\"content-length:\")) {\n"
                + "                                try { contentLength = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {}\n"
                + "                            }\n"
                + "                        }\n"
                + "                        char[] buf = new char[Math.min(contentLength, 65536)];\n"
                + "                        int read = 0;\n"
                + "                        while (read < buf.length) {\n"
                + "                            int n = br.read(buf, read, buf.length - read);\n"
                + "                            if (n <= 0) break;\n"
                + "                            read += n;\n"
                + "                        }\n"
                + "                        if (read > 0) {\n"
                + "                            JSONObject json = new JSONObject(new String(buf, 0, read));\n"
                + "                            String runtime = json.optString(\"runtime\", \"Runtime\");\n"
                + "                            String exc = \"[\" + runtime + \"] \" + json.optString(\"exception_class\", \"Error\");\n"
                + "                            String msg = json.optString(\"error_message\", \"\");\n"
                + "                            String stack = json.optString(\"stacktrace\", \"\");\n"
                + "                            broadcastReport(context, exc, msg, stack);\n"
                + "                        }\n"
                + "                        OutputStream os = client.getOutputStream();\n"
                + "                        os.write(\"HTTP/1.1 200 OK\\r\\nContent-Length: 2\\r\\n\\r\\nok\".getBytes(StandardCharsets.UTF_8));\n"
                + "                        os.flush();\n"
                + "                    } catch (Throwable ignored) {\n"
                + "                    }\n"
                + "                }\n"
                + "            } catch (Throwable ignored) {\n"
                + "            }\n"
                + "        }, \"xyzide-crash-bridge\");\n"
                + "        t.setDaemon(true);\n"
                + "        t.start();\n"
                + "    }\n\n"
                + "    public static void logError(Throwable throwable) {\n"
                + "        if (appContext != null && throwable != null) {\n"
                + "            sendThrowable(appContext, throwable);\n"
                + "        }\n"
                + "    }\n\n"
                + "    private static void sendThrowable(Context context, Throwable throwable) {\n"
                + "        StringWriter sw = new StringWriter();\n"
                + "        throwable.printStackTrace(new PrintWriter(sw));\n"
                + "        String trace = sw.toString();\n"
                + "        if (trace.length() > 16000) trace = trace.substring(0, 16000);\n"
                + "        broadcastReport(context, throwable.getClass().getName(), throwable.getMessage(), trace);\n"
                + "    }\n\n"
                + "    private static void broadcastReport(Context context, String excClass, String msg, String trace) {\n"
                + "        String logcat = captureOwnPidLogcat(150);\n"
                + "        Intent intent = new Intent(\"" + ACTION_REPORT_CRASH + "\");\n"
                + "        intent.setComponent(new ComponentName(\"" + XYZIDE_PACKAGE + "\", \"" + RECEIVER_CLASS + "\"));\n"
                + "        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);\n"
                + "        intent.putExtra(\"package_name\", context.getPackageName());\n"
                + "        intent.putExtra(\"exception_class\", excClass == null ? \"Error\" : excClass);\n"
                + "        intent.putExtra(\"error_message\", msg == null ? \"\" : msg);\n"
                + "        intent.putExtra(\"stacktrace\", trace == null ? \"\" : trace);\n"
                + "        intent.putExtra(\"logcat\", logcat);\n"
                + "        intent.putExtra(\"device\", Build.MANUFACTURER + \" \" + Build.MODEL + \" (Android \" + Build.VERSION.RELEASE + \")\");\n"
                + "        context.sendBroadcast(intent);\n"
                + "    }\n\n"
                + "    private static String captureOwnPidLogcat(int maxLines) {\n"
                + "        StringBuilder sb = new StringBuilder();\n"
                + "        try {\n"
                + "            int pid = Process.myPid();\n"
                + "            java.lang.Process proc = new ProcessBuilder(\"logcat\", \"-d\", \"-v\", \"time\", \"--pid=\" + pid).start();\n"
                + "            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));\n"
                + "            java.util.ArrayDeque<String> ring = new java.util.ArrayDeque<>();\n"
                + "            String line;\n"
                + "            while ((line = br.readLine()) != null) {\n"
                + "                if (ring.size() >= maxLines) ring.removeFirst();\n"
                + "                ring.addLast(line);\n"
                + "            }\n"
                + "            for (String l : ring) sb.append(l).append('\\n');\n"
                + "        } catch (Throwable ignored) {\n"
                + "        }\n"
                + "        return sb.toString();\n"
                + "    }\n\n"
                + "    @Override public Cursor query(Uri u, String[] p, String s, String[] sa, String so) { return null; }\n"
                + "    @Override public String getType(Uri u) { return null; }\n"
                + "    @Override public Uri insert(Uri u, ContentValues v) { return null; }\n"
                + "    @Override public int delete(Uri u, String s, String[] sa) { return 0; }\n"
                + "    @Override public int update(Uri u, ContentValues v, String s, String[] sa) { return 0; }\n"
                + "}\n";
    }

    /**
     * Format a structured Markdown crash report saved to {@code CRASH_REPORT.md}
     * in the active workspace so any AI coding agent can read it immediately.
     */
    public static String formatCrashReportMarkdown(String packageName, String exceptionClass,
                                                   String errorMessage, String stacktrace,
                                                   String logcat, String deviceInfo, long timestampMs) {
        String pkg = isBlank(packageName) ? "unknown.package" : packageName.trim();
        String exc = isBlank(exceptionClass) ? "Throwable" : exceptionClass.trim();
        String msg = isBlank(errorMessage) ? "(no message)" : errorMessage.trim();
        String trace = isBlank(stacktrace) ? "(empty stacktrace)" : stacktrace.trim();
        String logs = isBlank(logcat) ? "(no PID logcat captured)" : logcat.trim();
        String dev = isBlank(deviceInfo) ? "Android device" : deviceInfo.trim();
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(timestampMs));

        return "# Crash Report (" + pkg + ")\n\n"
                + "- **Captured At**: " + when + "\n"
                + "- **Package**: `" + pkg + "`\n"
                + "- **Device**: " + dev + "\n"
                + "- **Exception**: `" + exc + "`: " + msg + "\n\n"
                + "## Stacktrace\n"
                + "```text\n"
                + trace + "\n"
                + "```\n\n"
                + "## App Logcat (PID Tail)\n"
                + "```text\n"
                + logs + "\n"
                + "```\n";
    }

    public static String buildCrashFixPrompt(String packageName) {
        String pkg = isBlank(packageName) ? "the debug app" : packageName.trim();
        return "Analyze CRASH_REPORT.md in this workspace (crash from " + pkg
                + "), inspect the stacktrace and logcat, fix the root cause in the source code, and rebuild.";
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String readText(File file) {
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

    private static void writeText(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create directory: " + parent.getAbsolutePath());
        }
        try (FileWriter fw = new FileWriter(file)) {
            fw.write(content);
        }
    }
}
