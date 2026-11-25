package com.termux.app.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.app.build.AndroidBuildConfig;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class BrowserAutomationConfigTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void testNormalizeUrl() {
        assertEquals("https://www.google.com", BrowserAutomationConfig.normalizeUrl(""));
        assertEquals("http://localhost:3000", BrowserAutomationConfig.normalizeUrl("localhost:3000"));
        assertEquals("http://127.0.0.1:5173/app", BrowserAutomationConfig.normalizeUrl("127.0.0.1:5173/app"));
        assertEquals("https://github.com", BrowserAutomationConfig.normalizeUrl("github.com"));
        assertEquals("https://example.org", BrowserAutomationConfig.normalizeUrl("https://example.org"));
        assertEquals("https://www.google.com/search?q=playwright+python",
                BrowserAutomationConfig.normalizeUrl("playwright python"));

        assertTrue(BrowserAutomationConfig.isLocalDevUrl("http://localhost:3000"));
        assertTrue(BrowserAutomationConfig.isLocalDevUrl("http://127.0.0.1:5173/test"));
        assertFalse(BrowserAutomationConfig.isLocalDevUrl("https://accounts.google.com/o/oauth2/auth"));
        assertFalse(BrowserAutomationConfig.isLocalDevUrl("https://github.com/"));
    }

    @Test
    public void testPlaywrightReadyDetectionInUbuntuProot() throws Exception {
        File prefix = tmp.newFolder("prefix");
        assertFalse(BrowserAutomationConfig.isPlaywrightReady(prefix.getAbsolutePath()));

        File ubuntu = new File(prefix, "var/lib/proot-distro/installed-rootfs/ubuntu");
        File pwBin = new File(ubuntu, "root/.xyzide-playwright/.venv/bin/playwright");
        File msDir = new File(ubuntu, "root/.cache/ms-playwright");
        pwBin.getParentFile().mkdirs();
        msDir.mkdirs();
        assertTrue(pwBin.createNewFile());

        assertTrue(BrowserAutomationConfig.isPlaywrightReady(prefix.getAbsolutePath()));
    }

    @Test
    public void testBuildPlaywrightInstallCommandTargetsUbuntuWhenPresent() throws Exception {
        File prefix = tmp.newFolder("prefix");
        File ubuntu = new File(prefix, "var/lib/proot-distro/installed-rootfs/ubuntu");
        assertTrue(ubuntu.mkdirs());

        String cmd = BrowserAutomationConfig.buildPlaywrightInstallCommand(prefix.getAbsolutePath());
        assertTrue(cmd.contains("proot-distro login ubuntu"));
        assertTrue(cmd.contains("playwright install chromium"));
        assertTrue(cmd.contains("--no-sandbox"));
        assertTrue(cmd.contains("__XYZ_PROGRESS__"));
    }

    @Test
    public void testWorkspaceAgentDocsIncludeHybridBrowserGuide() throws Exception {
        File ws = tmp.newFolder("ws");
        File prefix = tmp.newFolder("prefix");

        AndroidBuildConfig.syncWorkspaceAgentDocs(
                ws, prefix.getAbsolutePath(), AndroidBuildConfig.MODE_LOCAL, true, "Not configured");

        for (String name : new String[]{"GEMINI.md", "AGENTS.md", "CLAUDE.md"}) {
            File doc = new File(ws, name);
            assertTrue(doc.isFile());
            String content = new String(Files.readAllBytes(doc.toPath()), StandardCharsets.UTF_8);
            assertTrue(content.contains(BrowserAutomationConfig.CONTEXT_START));
            assertTrue(content.contains("Playwright"));
            assertTrue(content.contains("--no-sandbox"));
            assertTrue(content.contains("WebView"));
        }
    }
}
