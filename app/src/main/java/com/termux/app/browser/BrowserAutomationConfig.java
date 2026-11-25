package com.termux.app.browser;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Configuration and Ubuntu PRoot Playwright installer helpers for the
 * Playwright (AI agent automation) + Android WebView (human browsing) hybrid setup.
 */
public final class BrowserAutomationConfig {

    public static final String CONTEXT_START = "<!-- XYZIDE_BROWSER_START -->";
    public static final String CONTEXT_END = "<!-- XYZIDE_BROWSER_END -->";

    private static final String UBUNTU_ROOTFS_REL = "/var/lib/proot-distro/installed-rootfs/ubuntu";

    private BrowserAutomationConfig() {
    }

    public static boolean isLocalDevUrl(String url) {
        if (url == null) return false;
        String lower = url.trim().toLowerCase(Locale.US);
        return lower.startsWith("http://localhost")
                || lower.startsWith("https://localhost")
                || lower.startsWith("http://127.0.0.1")
                || lower.startsWith("https://127.0.0.1")
                || lower.startsWith("http://0.0.0.0")
                || lower.startsWith("http://[::1]");
    }

    /**
     * Normalize user-entered URL or search query into a loadable URL for WebView.
     */
    public static String normalizeUrl(String rawInput) {
        if (rawInput == null) return "https://www.google.com";
        String trimmed = rawInput.trim();
        if (trimmed.isEmpty()) return "https://www.google.com";

        String lower = trimmed.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("file://") || lower.startsWith("about:")) {
            return trimmed;
        }
        if (lower.startsWith("localhost") || lower.startsWith("127.0.0.1")
                || lower.startsWith("[::1]") || lower.startsWith("10.")
                || lower.startsWith("192.168.")) {
            return "http://" + trimmed;
        }
        if (!trimmed.contains(" ") && trimmed.contains(".")) {
            return "https://" + trimmed;
        }
        try {
            return "https://www.google.com/search?q=" + URLEncoder.encode(trimmed, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "https://www.google.com/search?q=" + trimmed.replace(" ", "+");
        }
    }

    /**
     * Locate the Ubuntu PRoot rootfs directory under Termux {@code $PREFIX}, if installed.
     */
    public static File getUbuntuRootfs(String prefixPath) {
        if (prefixPath == null || prefixPath.isEmpty()) return null;
        return new File(prefixPath + UBUNTU_ROOTFS_REL);
    }

    /**
     * True when Playwright and a headless Chromium binary exist in Ubuntu PRoot (preferred)
     * or in native Termux fallback.
     */
    public static boolean isPlaywrightReady(String prefixPath) {
        if (prefixPath == null || prefixPath.isEmpty()) return false;
        File ubuntu = getUbuntuRootfs(prefixPath);
        if (ubuntu != null && ubuntu.isDirectory()) {
            boolean hasVenv = new File(ubuntu, "root/.xyzide-playwright/.venv/bin/playwright").isFile()
                    || new File(ubuntu, "root/.local/bin/xyz-playwright").isFile();
            boolean hasMsBrowser = new File(ubuntu, "root/.cache/ms-playwright").isDirectory()
                    || new File(ubuntu, "usr/bin/chromium").exists()
                    || new File(ubuntu, "usr/bin/chromium-browser").exists();
            if (hasVenv && hasMsBrowser) return true;
        }
        return new File(prefixPath + "/bin/playwright").exists()
                || new File(prefixPath + "/bin/chromium").exists();
    }

    /**
     * Build the shell command executed from Termux to install Playwright + standalone ARM64 Chromium
     * inside Ubuntu PRoot (without Snap), emitting {@code __XYZ_PROGRESS__} updates for {@code BuildLogDialog}.
     */
    public static String buildPlaywrightInstallCommand(String prefixPath) {
        File ubuntu = getUbuntuRootfs(prefixPath);
        String helperPy = "#!/usr/bin/env python3\n"
                + "import os, sys, glob\n"
                + "from playwright.sync_api import sync_playwright\n"
                + "\n"
                + "ARGS = ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu', '--headless=new']\n"
                + "\n"
                + "def find_exec():\n"
                + "    for p in ['/usr/bin/chromium', '/usr/bin/chromium-browser']:\n"
                + "        if os.path.exists(p) and os.path.getsize(p) > 200000:\n"
                + "            return p\n"
                + "    return None\n"
                + "\n"
                + "def main():\n"
                + "    if len(sys.argv) < 3:\n"
                + "        print('Usage: xyz-playwright <text|screenshot|check> <url> [out.png]')\n"
                + "        sys.exit(1)\n"
                + "    mode, url = sys.argv[1], sys.argv[2]\n"
                + "    with sync_playwright() as p:\n"
                + "        kw = {'headless': True, 'args': ARGS}\n"
                + "        exe = find_exec()\n"
                + "        if exe:\n"
                + "            kw['executable_path'] = exe\n"
                + "        browser = p.chromium.launch(**kw)\n"
                + "        page = browser.new_page()\n"
                + "        errors = []\n"
                + "        page.on('pageerror', lambda e: errors.append(str(e)))\n"
                + "        page.goto(url, wait_until='domcontentloaded', timeout=30000)\n"
                + "        if mode == 'screenshot':\n"
                + "            out = sys.argv[3] if len(sys.argv) > 3 else 'screenshot.png'\n"
                + "            page.screenshot(path=out, full_page=True)\n"
                + "            print('Saved screenshot:', out)\n"
                + "        elif mode == 'text':\n"
                + "            print(page.inner_text('body'))\n"
                + "        else:\n"
                + "            print('TITLE:', page.title())\n"
                + "            print('URL:', page.url)\n"
                + "            print('PAGE_ERRORS:', len(errors))\n"
                + "            for e in errors[:10]:\n"
                + "                print('ERR:', e)\n"
                + "        browser.close()\n"
                + "\n"
                + "if __name__ == '__main__':\n"
                + "    main()\n";

        String encodedHelper = helperPy.replace("'", "'\"'\"'");

        String innerUbuntuScript =
                "set -e; "
                + "echo '__XYZ_PROGRESS__:10:Updating Ubuntu package lists...'; "
                + "export DEBIAN_FRONTEND=noninteractive; "
                + "apt-get update -y; "
                + "echo '__XYZ_PROGRESS__:28:Installing Python & Chromium shared libraries...'; "
                + "apt-get install -y python3 python3-venv python3-pip ca-certificates curl wget fonts-liberation "
                + "  libnss3 libnspr4 libdrm2 libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libgbm1 libpango-1.0-0 libcairo2 || true; "
                + "(apt-get install -y libatk1.0-0t64 libatk-bridge2.0-0t64 libcups2t64 libasound2t64 || "
                + " apt-get install -y libatk1.0-0 libatk-bridge2.0-0 libcups2 libasound2 || true); "
                + "echo '__XYZ_PROGRESS__:52:Setting up Playwright Python venv...'; "
                + "mkdir -p /root/.xyzide-playwright /root/.local/bin; "
                + "python3 -m venv /root/.xyzide-playwright/.venv; "
                + "/root/.xyzide-playwright/.venv/bin/pip install --upgrade pip playwright; "
                + "echo '__XYZ_PROGRESS__:74:Downloading standalone ARM64 Chromium for Playwright...'; "
                + "/root/.xyzide-playwright/.venv/bin/playwright install chromium; "
                + "/root/.xyzide-playwright/.venv/bin/playwright install-deps chromium || true; "
                + "echo '__XYZ_PROGRESS__:92:Writing xyz-playwright CLI helper...'; "
                + "cat << 'XYZEOF' > /root/.local/bin/xyz-playwright\n"
                + "#!/usr/bin/env bash\n"
                + "exec /root/.xyzide-playwright/.venv/bin/python /root/.xyzide-playwright/cli.py \"$@\"\n"
                + "XYZEOF\n"
                + "cat << 'XYZEOF' > /root/.xyzide-playwright/cli.py\n"
                + helperPy
                + "XYZEOF\n"
                + "chmod +x /root/.local/bin/xyz-playwright /root/.xyzide-playwright/cli.py; "
                + "grep -qF '.xyzide-playwright/.venv/bin' /root/.bashrc 2>/dev/null || "
                + "echo 'export PATH=\"/root/.local/bin:/root/.xyzide-playwright/.venv/bin:$PATH\"' >> /root/.bashrc; "
                + "echo '__XYZ_PROGRESS__:100:Playwright + Headless Chromium ready inside Ubuntu!'";

        if (ubuntu != null && ubuntu.isDirectory()) {
            String escapedInner = innerUbuntuScript.replace("'", "'\\''");
            return "echo '__XYZ_PROGRESS__:4:Entering Ubuntu workshop container...'; "
                    + "if command -v proot-distro >/dev/null 2>&1; then "
                    + "  proot-distro login ubuntu --shared-tmp -- bash -c '" + escapedInner + "'; "
                    + "else "
                    + "  echo 'proot-distro not found. Run Setup Wizard to install Ubuntu first.' >&2; exit 1; "
                    + "fi";
        }

        // Fallback if Ubuntu rootfs is not installed yet
        String nativeFallback =
                "echo '__XYZ_PROGRESS__:12:Ubuntu not found — checking Termux python/playwright fallback...'; "
                + "export TERMUX_PKG_NO_MIRROR_SELECT=1; "
                + "pkg update -y && pkg install -y python tur-repo && pkg install -y chromium || true; "
                + "mkdir -p ~/.xyzide-playwright ~/.local/bin; "
                + "python3 -m venv ~/.xyzide-playwright/.venv && ~/.xyzide-playwright/.venv/bin/pip install playwright; "
                + "printf '%s' '" + encodedHelper + "' > ~/.xyzide-playwright/cli.py; "
                + "echo '#!/bin/bash\nexec ~/.xyzide-playwright/.venv/bin/python ~/.xyzide-playwright/cli.py \"$@\"' > ~/.local/bin/xyz-playwright; "
                + "chmod +x ~/.local/bin/xyz-playwright ~/.xyzide-playwright/cli.py; "
                + "echo '__XYZ_PROGRESS__:100:Playwright fallback setup complete!'";
        return nativeFallback;
    }

    /**
     * Build the managed Markdown section describing Xyz.IDE's Playwright (AI automation)
     * + Android WebView (in-app browsing) hybrid setup for {@code GEMINI.md}, {@code AGENTS.md}, and {@code CLAUDE.md}.
     */
    public static String buildBrowserAgentContextSection(boolean playwrightReady) {
        return CONTEXT_START + "\n"
                + "## Xyz.IDE Hybrid Browser Architecture (Playwright + WebView)\n\n"
                + "- **AI Browser Automation**: Headless Playwright inside Ubuntu PRoot (`" + (playwrightReady ? "Ready" : "Not installed yet — tap Browser -> Install Playwright or Settings") + "`).\n"
                + "- **Human In-App Browser**: Built-in Android `WebView` (`BrowserActivity`) inside Xyz.IDE for manual browsing and `http://localhost:PORT` preview.\n\n"
                + "### Rules for AI Agents Controlling the Browser\n"
                + "- For automated UI checks, DOM scraping, screenshots, or console-error checks, use **Playwright**:\n"
                + "  - Quick CLI helper: `xyz-playwright check http://127.0.0.1:3000` | `xyz-playwright text <url>` | `xyz-playwright screenshot <url> out.png`\n"
                + "  - Python venv: `/root/.xyzide-playwright/.venv/bin/python`\n"
                + "- **PRoot Required Chromium Flags**: Always launch Playwright Chromium with:\n"
                + "  `headless=True, args=[\"--no-sandbox\", \"--disable-dev-shm-usage\", \"--disable-gpu\", \"--headless=new\"]`\n"
                + "- Do not try to automate Android Chrome or the user's in-app WebView; when serving a web app on `localhost`, share the URL so the user can view it in Xyz.IDE's WebView Browser.\n"
                + CONTEXT_END;
    }
}
