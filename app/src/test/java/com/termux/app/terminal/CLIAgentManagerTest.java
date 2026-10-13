package com.termux.app.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * Tests for the command-building helpers of {@link CLIAgentManager}.
 *
 * <p>These cover the two things that are easy to get wrong and expensive when wrong: shell
 * quoting of an untrusted prompt, and TLS verification being silently disabled for every agent.</p>
 */
public class CLIAgentManagerTest {

    @Test
    public void leavesPlainTextAlone() {
        assertEquals("hello world", CLIAgentManager.escapeForDoubleQuotes("hello world"));
    }

    @Test
    public void escapesDoubleQuote() {
        assertEquals("a\\\"b", CLIAgentManager.escapeForDoubleQuotes("a\"b"));
    }

    @Test
    public void escapesDollarAndBacktickAndBackslash() {
        assertEquals("a\\$b", CLIAgentManager.escapeForDoubleQuotes("a$b"));
        assertEquals("a\\`b", CLIAgentManager.escapeForDoubleQuotes("a`b"));
        assertEquals("a\\\\b", CLIAgentManager.escapeForDoubleQuotes("a\\b"));
    }

    @Test
    public void handlesNullAndEmpty() {
        assertEquals("", CLIAgentManager.escapeForDoubleQuotes(null));
        assertEquals("", CLIAgentManager.escapeForDoubleQuotes(""));
    }

    @Test
    public void promptCannotBreakOutOfDoubleQuoting() {
        String escaped = CLIAgentManager.escapeForDoubleQuotes("\"; rm -rf / #");
        // Every double quote must be backslash-escaped, so the shell never sees a quote that
        // closes the template's own surrounding quotes early.
        for (int i = 0; i < escaped.length(); i++) {
            if (escaped.charAt(i) == '"') {
                assertTrue("unescaped quote at index " + i + " in: " + escaped,
                        i > 0 && escaped.charAt(i - 1) == '\\');
            }
        }
    }

    @Test
    public void commandSubstitutionCannotEscape() {
        assertEquals("\\$(whoami)", CLIAgentManager.escapeForDoubleQuotes("$(whoami)"));
        assertEquals("\\`id\\`", CLIAgentManager.escapeForDoubleQuotes("`id`"));
    }

    @Test
    public void shellPreludeDoesNotDisableTlsVerification() {
        String prelude = CLIAgentManager.shellPrelude();
        assertFalse("prelude must not disable Node TLS verification",
                prelude.contains("NODE_TLS_REJECT_UNAUTHORIZED"));
        assertTrue("prelude should trust the Termux CA bundle instead",
                prelude.contains("NODE_EXTRA_CA_CERTS"));
        assertTrue(prelude.contains("SSL_CERT_FILE"));
    }

    @Test
    public void noInstallCommandDisablesStrictSsl() {
        for (CLIAgentManager.CLIAgent agent : CLIAgentManager.AGENTS) {
            if (agent.installCommand == null) continue;
            assertFalse(agent.id + " turns off npm certificate checking",
                    agent.installCommand.contains("strict-ssl false"));
        }
    }

    @Test
    public void agentIdsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (CLIAgentManager.CLIAgent agent : CLIAgentManager.AGENTS) {
            assertTrue("duplicate agent id: " + agent.id, ids.add(agent.id));
        }
    }

    @Test
    public void findAgentResolvesKnownIdsAndRejectsUnknown() {
        assertNotNull(CLIAgentManager.findAgent("claude_code"));
        assertNotNull(CLIAgentManager.findAgent("custom"));
        assertEquals(null, CLIAgentManager.findAgent("does_not_exist"));
    }

    @Test
    public void claudeGuiCommandUsesNonInteractivePrintFlag() {
        CLIAgentManager.CLIAgent claude = CLIAgentManager.findAgent("claude_code");
        assertNotNull(claude);
        assertTrue("expected a non-interactive template, got: " + claude.guiPromptTemplate,
                claude.guiPromptTemplate.contains("{prompt}"));
        assertTrue(claude.guiPromptTemplate.contains("-p"));
    }

    @Test
    public void antigravityGuiCommandUsesNonInteractivePrintFlag() {
        CLIAgentManager.CLIAgent agy = CLIAgentManager.findAgent("antigravity");
        assertNotNull(agy);
        assertTrue("expected a non-interactive template, got: " + agy.guiPromptTemplate,
                agy.guiPromptTemplate.contains("{prompt}"));
        assertTrue("Antigravity requires -p for non-interactive prompt, got: " + agy.guiPromptTemplate,
                agy.guiPromptTemplate.contains("-p"));
    }

    // ------------------------------------------------------------------
    // PATH correctness: an agent that is installed into one directory but looked for in
    // another fails with "command not found", so these are pinned to the real installers.
    // ------------------------------------------------------------------

    @Test
    public void everyAgentPathIsHomeRelativeSoItWorksInBothEnvironments() {
        // A template that knows only one literal absolute home fails in the other environment:
        // /root is right inside the proot rootfs, /data/data/com.termux/files/home in native
        // Termux. A $HOME-relative entry is correct in both, so one must be present.
        for (CLIAgentManager.CLIAgent agent : CLIAgentManager.AGENTS) {
            if (agent.id.equals("custom")) continue;
            if (!agent.runCommandTemplate.contains("PATH=")) continue; // npm-global agents
            assertTrue(agent.id + " run command must use a $HOME-relative bin dir: "
                            + agent.runCommandTemplate,
                    agent.runCommandTemplate.contains("$HOME/")
                            || agent.runCommandTemplate.contains("~/"));
        }
    }

    @Test
    public void shellPreludeCoversBothNativeTermuxAndTheRootfs() {
        String prelude = CLIAgentManager.shellPrelude();
        assertTrue("must be right in native Termux", prelude.contains("$HOME/.local/bin"));
        assertTrue("must be right inside the proot rootfs", prelude.contains("/root/.local/bin"));
        assertTrue("must refresh the shell's command hash after changing PATH",
                prelude.contains("hash -r"));
    }

    @Test
    public void curlInstalledAgentsExportTheirRealInstallDir() {
        // Verified against each installer script's TARGET_DIR / INSTALL_DIR / BIN_DIR.
        assertUsesPath("antigravity", "$HOME/.local/bin");
        assertUsesPath("kiro", "$HOME/.local/bin");
        assertUsesPath("opencode", "$HOME/.opencode/bin");
        assertUsesPath("grok", "$HOME/.grok/bin");
    }

    private static void assertUsesPath(String agentId, String dir) {
        CLIAgentManager.CLIAgent agent = CLIAgentManager.findAgent(agentId);
        assertNotNull(agentId + " not found", agent);
        String needle = "PATH=\"" + dir;
        assertTrue(agentId + " run command must export " + dir + ", got: " + agent.runCommandTemplate,
                agent.runCommandTemplate.contains(needle));
        assertTrue(agentId + " gui command must export " + dir + ", got: " + agent.guiPromptTemplate,
                agent.guiPromptTemplate.contains(needle));
    }

    @Test
    public void shellPreludePutsEveryInstallerBinDirOnPath() {
        String prelude = CLIAgentManager.shellPrelude();
        assertTrue(prelude.contains("$HOME/.local/bin"));
        assertTrue(prelude.contains("$HOME/.opencode/bin"));
        assertTrue(prelude.contains("$HOME/.grok/bin"));
    }

    @Test
    public void curlInstalledAgentsPersistTheirPathInBashrc() {
        for (String id : new String[]{"antigravity", "kiro", "opencode", "grok"}) {
            CLIAgentManager.CLIAgent agent = CLIAgentManager.findAgent(id);
            assertNotNull(agent);
            assertTrue(id + " should make the binary findable in a manual terminal too",
                    agent.installCommand.contains("~/.bashrc"));
        }
    }

    // ------------------------------------------------------------------
    // Environment: the app's terminal opens inside the Ubuntu proot rootfs, so agents must be
    // installed and run there too, or an installed agent is invisible.
    // ------------------------------------------------------------------

    @Test
    public void workshopWrapUsesTheDocumentedProotDistroForm() {
        String wrapped = CLIAgentManager.wrapForWorkshop("claude -p \"hi\"");
        assertTrue("unexpected wrap: " + wrapped,
                wrapped.startsWith("proot-distro login ubuntu -- bash -lc "));
        assertTrue(wrapped.endsWith("'claude -p \"hi\"'"));
    }

    @Test
    public void workshopWrapHonoursTheSetupWizardDistroChoice() {
        for (String distro : new String[]{"ubuntu", "debian", "alpine"}) {
            String wrapped = CLIAgentManager.wrapForWorkshop("echo hi", distro);
            assertTrue("unexpected wrap for " + distro + ": " + wrapped,
                    wrapped.startsWith("proot-distro login " + distro + " -- bash -lc "));
        }
        // Null/blank falls back to the bundled default.
        assertTrue(CLIAgentManager.wrapForWorkshop("echo hi", null)
                .startsWith("proot-distro login ubuntu -- bash -lc "));
        assertTrue(CLIAgentManager.wrapForWorkshop("echo hi", "")
                .startsWith("proot-distro login ubuntu -- bash -lc "));
    }

    @Test
    public void workshopPathsCoverBothProotDistroLayouts() {
        // Old bash releases use installed-rootfs/<name>, the Python rewrite uses
        // containers/<name>/rootfs and migrates the legacy dir on first login.
        assertTrue(CLIAgentManager.legacyRootfsPath("ubuntu").endsWith("installed-rootfs/ubuntu"));
        assertTrue(CLIAgentManager.containerRootfsPath("ubuntu").endsWith("containers/ubuntu/rootfs"));
        assertTrue(CLIAgentManager.legacyRootfsPath("debian").endsWith("installed-rootfs/debian"));
    }

    @Test
    public void workshopWrapKeepsThePayloadInsideOneQuotingContext() {
        // The wrapped command itself contains single quotes (from shellEscape of the workspace).
        String original = "cd '/a b' && echo 'x'";
        String wrapped = CLIAgentManager.wrapForWorkshop(original);
        assertTrue("embedded quotes must be re-escaped: " + wrapped, wrapped.contains("'\\''"));

        String payload = wrapped.substring(wrapped.indexOf("bash -lc ") + "bash -lc ".length());
        // Round-trip: undoing the single-quote escaping must give the original command back.
        // If any interior quote were left unescaped it would close the payload early and this
        // would not round-trip.
        assertEquals(original, unquoteBashSingle(payload));
    }

    /** Undo bash single-quoting, where a literal quote is encoded as {@code '\''}. */
    private static String unquoteBashSingle(String quoted) {
        assertEquals("must open with a quote", "'", quoted.substring(0, 1));
        assertEquals("must close with a quote", "'", quoted.substring(quoted.length() - 1));
        return quoted.substring(1, quoted.length() - 1).replace("'\\''", "'");
    }

    @Test
    public void singleQuoteHandlesNullEmptyAndEmbeddedQuotes() {
        assertEquals("''", CLIAgentManager.singleQuote(null));
        assertEquals("''", CLIAgentManager.singleQuote(""));
        assertEquals("'a b'", CLIAgentManager.singleQuote("a b"));
        assertEquals("'a'\\''b'", CLIAgentManager.singleQuote("a'b"));
    }

    @Test
    public void shellPreludeIsSafeInsideTheRootfsAndDoesNotClobberFiles() {
        String prelude = CLIAgentManager.shellPrelude();
        // Replaces only outdated xdg-open shims (marker check), keeps the fixed one.
        assertTrue(prelude.contains("Open this URL"));
        // Exports the Termux CA bundle only when the file is actually there: inside the Ubuntu
        // rootfs it is not, and pointing SSL_CERT_FILE at a missing file breaks every HTTPS call.
        assertTrue(prelude.contains("[ -f \""));
        // Every fallible step must be non-fatal or the whole command chain stops.
        assertTrue(prelude.contains("|| true"));
    }

    @Test
    public void shellPreludeShimFallsBackToPrintingTheUrl() {
        // Inside the Ubuntu proot the Termux opener is invisible; without an echo fallback
        // OAuth logins (agy) hang forever waiting for a browser that never opens.
        String prelude = CLIAgentManager.shellPrelude();
        assertTrue(prelude.contains("termux-open-url"));
        assertTrue(prelude.contains("Open this URL"));
        assertTrue("agents should honour $BROWSER", prelude.contains("BROWSER=xdg-open"));
    }

    @Test
    public void curlInstalledAgentsVerifyTheBinaryAfterInstall() {
        // The dashboard kept saying "NOT INSTALLED" right after a successful install because
        // nothing verified the binary. Both must print the version or a PATH hint.
        for (String id : new String[]{"antigravity", "opencode"}) {
            CLIAgentManager.CLIAgent agent = CLIAgentManager.findAgent(id);
            assertNotNull(agent);
            assertTrue(id + " install must verify the binary, got: " + agent.installCommand,
                    agent.installCommand.contains("--version"));
        }
    }

    @Test
    public void opencodeInstallDoesNotReinstallWhenBinaryFileExists() {
        // `command -v` misses ~/.opencode/bin on a fresh shell, so the guard must also
        // check the file directly or every launch reinstalls opencode.
        CLIAgentManager.CLIAgent opencode = CLIAgentManager.findAgent("opencode");
        assertNotNull(opencode);
        assertTrue("expected a direct file guard, got: " + opencode.installCommand,
                opencode.installCommand.contains("$HOME/.opencode/bin/opencode"));
    }

    @Test
    public void probeScriptReportsBinaryEqualsPathLines() {
        String script = CLIAgentManager.buildProbeScript("agy opencode");
        assertTrue(script.contains("for b in agy opencode"));
        assertTrue(script.contains("command -v"));
        assertTrue(script.contains("$HOME/.local/bin"));
        assertTrue(script.contains("$HOME/.opencode/bin"));
        assertTrue(script.contains("/root/.local/bin"));
    }

    @Test
    public void probeOutputParsingFindsBothCommandVAndDirectFileHits() {
        java.util.Map<String, CLIAgentManager.FoundBinary> into = new java.util.HashMap<>();
        CLIAgentManager.parseProbeOutput(
                "agy=/root/.local/bin/agy\nopencode=/root/.opencode/bin/opencode\n\n", true, into);
        assertTrue(into.containsKey("agy"));
        assertTrue(into.get("agy").inWorkshop);
        assertEquals("/root/.local/bin/agy", into.get("agy").path);
        // Malformed lines are ignored, never crash the GUI.
        CLIAgentManager.parseProbeOutput("garbage\n=no-path\nempty=\n", false, into);
        assertEquals(2, into.size());
    }

    @Test
    public void workshopResultWinsTheDisplayedPathButTermuxHitIsKept() {
        java.util.Map<String, CLIAgentManager.FoundBinary> into = new java.util.HashMap<>();
        CLIAgentManager.parseProbeOutput("agy=/data/data/com.termux/files/home/.local/bin/agy\n",
                false, into);
        assertTrue(into.get("agy").inTermux);
        assertEquals("/data/data/com.termux/files/home/.local/bin/agy", into.get("agy").path);
        CLIAgentManager.parseProbeOutput("agy=/root/.local/bin/agy\n", true, into);
        assertTrue(into.get("agy").inWorkshop);
        assertTrue(into.get("agy").inTermux);
        assertEquals("/root/.local/bin/agy", into.get("agy").path);
    }

    @Test
    public void everyAgentNeedsLoginExceptCustom() {
        assertTrue(CLIAgentManager.needsLogin(CLIAgentManager.findAgent("antigravity")));
        assertTrue(CLIAgentManager.needsLogin(CLIAgentManager.findAgent("opencode")));
        assertTrue(CLIAgentManager.needsLogin(CLIAgentManager.findAgent("claude_code")));
        assertFalse(CLIAgentManager.needsLogin(CLIAgentManager.findAgent("custom")));
        assertFalse(CLIAgentManager.needsLogin(null));
    }
}
