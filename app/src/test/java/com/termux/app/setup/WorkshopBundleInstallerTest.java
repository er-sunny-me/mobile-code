package com.termux.app.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

/** Pure-helper tests for the APK workshop bundle (no Android framework needed). */
public class WorkshopBundleInstallerTest {

    @Test
    public void abiMapsToBundledArch() {
        assertEquals("arm64", WorkshopBundleInstaller.bundleArchForAbi("arm64-v8a"));
        assertEquals("x86_64", WorkshopBundleInstaller.bundleArchForAbi("x86_64"));
        assertNull(WorkshopBundleInstaller.bundleArchForAbi("armeabi-v7a"));
        assertNull(WorkshopBundleInstaller.bundleArchForAbi("x86"));
        assertNull(WorkshopBundleInstaller.bundleArchForAbi(null));
    }

    @Test
    public void agentKeysOnlyExistForBundledAgentsOnArm64() {
        assertEquals("agent.agy.arm64",
                WorkshopBundleInstaller.bundledAgentKey("antigravity", "arm64"));
        assertEquals("agent.opencode.arm64",
                WorkshopBundleInstaller.bundledAgentKey("opencode", "arm64"));
        assertEquals("agent.grok.arm64",
                WorkshopBundleInstaller.bundledAgentKey("grok", "arm64"));
        assertEquals("agent.kiro.arm64",
                WorkshopBundleInstaller.bundledAgentKey("kiro", "arm64"));
        // npm-based agents are never bundled (they need node at setup time).
        assertNull(WorkshopBundleInstaller.bundledAgentKey("claude_code", "arm64"));
        assertNull(WorkshopBundleInstaller.bundledAgentKey("codex", "arm64"));
        assertNull(WorkshopBundleInstaller.bundledAgentKey("custom", "arm64"));
        // Agents ship arm64-only for test phones.
        assertNull(WorkshopBundleInstaller.bundledAgentKey("antigravity", "x86_64"));
    }

    @Test
    public void onlyUbuntuRootfsIsBundled() {
        assertEquals("rootfs.ubuntu.arm64",
                WorkshopBundleInstaller.bundledRootfsKey("ubuntu", "arm64"));
        assertEquals("rootfs.ubuntu.x86_64",
                WorkshopBundleInstaller.bundledRootfsKey("ubuntu", "x86_64"));
        assertNull(WorkshopBundleInstaller.bundledRootfsKey("debian", "arm64"));
        assertNull(WorkshopBundleInstaller.bundledRootfsKey("alpine", "arm64"));
        assertNull(WorkshopBundleInstaller.bundledRootfsKey("ubuntu", "arm"));
    }

    @Test
    public void extractCommandTargetsTheLegacySlotBothProotVersionsUnderstand() {
        String cmd = WorkshopBundleInstaller.buildRootfsExtractCommand(
                "/cache/ubuntu-arm64.tar.gz",
                "/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu");
        assertTrue(cmd.contains("tar -xzpf"));
        assertTrue(cmd.contains("installed-rootfs/ubuntu"));
        // Paths with spaces must stay one shell word.
        String spaced = WorkshopBundleInstaller.buildRootfsExtractCommand(
                "/a b/c.tar.gz", "/d e/f");
        assertTrue(spaced.contains("'/a b/c.tar.gz'"));
        assertTrue(spaced.contains("'/d e/f'"));
    }

    @Test
    public void bundledSnippetsVerifyTheBinary() {
        // Every snippet must end in a check whose exit code proves success,
        // because the wizard treats a zero exit as "installed".
        assertTrue(WorkshopBundleInstaller
                .bundledAgentInstallSnippet("antigravity", "/c/agy.tar.gz")
                .contains("command -v agy"));
        assertTrue(WorkshopBundleInstaller
                .bundledAgentInstallSnippet("opencode", "/c/oc.tar.gz")
                .contains(".opencode/bin/opencode"));
        assertTrue(WorkshopBundleInstaller
                .bundledAgentInstallSnippet("grok", "/c/grok.bin")
                .contains(".grok/bin/grok"));
        assertTrue(WorkshopBundleInstaller
                .bundledAgentInstallSnippet("kiro", "/c/kiro-dir")
                .contains("kirocli/install.sh"));
        assertNull(WorkshopBundleInstaller.bundledAgentInstallSnippet("codex", "/c/x"));
    }

    @Test
    public void shellQuoteKeepsPathsAsOneWord() {
        assertEquals("'/a b'", WorkshopBundleInstaller.shellQuote("/a b"));
        assertTrue(WorkshopBundleInstaller.shellQuote("/a'b").contains("'\\''"));
    }

    @Test
    public void unzipHelperRejectsTraversalAndExtractsFiles() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "bundle-test-" + System.nanoTime());
        File zip = new File(dir, "t.zip");
        dir.mkdirs();
        try (java.util.zip.ZipOutputStream zout = new java.util.zip.ZipOutputStream(
                new java.io.FileOutputStream(zip))) {
            zout.putNextEntry(new java.util.zip.ZipEntry("ok/hello.txt"));
            zout.write("hi".getBytes("UTF-8"));
            zout.closeEntry();
            zout.putNextEntry(new java.util.zip.ZipEntry("../../evil.txt"));
            zout.write("x".getBytes("UTF-8"));
            zout.closeEntry();
        }
        File dest = new File(dir, "out");
        assertTrue(WorkshopBundleInstaller.unzipToDir(zip, dest));
        assertTrue(new File(dest, "ok/hello.txt").isFile());
        assertTrue(!new File(dir, "evil.txt").exists()
                && !new File(dest, "evil.txt").exists());
    }
}
