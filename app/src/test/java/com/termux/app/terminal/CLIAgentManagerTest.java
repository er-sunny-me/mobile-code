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
        // /root is right inside the proot rootfs, /data/data/com.xyzide/files/home in native
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
        CLIAgentManager.parseProbeOutput("agy=/data/data/com.xyzide/files/home/.local/bin/agy\n",
                false, into);
        assertTrue(into.get("agy").inTermux);
        assertEquals("/data/data/com.xyzide/files/home/.local/bin/agy", into.get("agy").path);
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

    @Test
    public void availableModelsOnlyIncludeValidVendorModels() {
        // Antigravity (Google DeepMind) must only have Gemini models
        java.util.List<String> agyModels = CLIAgentManager.getAvailableModels("antigravity");
        assertFalse("Antigravity models list must not be empty", agyModels.isEmpty());
        for (String m : agyModels) {
            assertTrue("Antigravity model must be Gemini: " + m, m.startsWith("gemini-"));
        }

        // Claude Code must only have Claude models
        java.util.List<String> claudeModels = CLIAgentManager.getAvailableModels("claude_code");
        assertFalse("Claude models list must not be empty", claudeModels.isEmpty());
        for (String m : claudeModels) {
            assertTrue("Claude model must be Claude: " + m, m.startsWith("claude-"));
        }

        // Codex must only have OpenAI models
        java.util.List<String> codexModels = CLIAgentManager.getAvailableModels("codex");
        assertFalse("Codex models list must not be empty", codexModels.isEmpty());
        for (String m : codexModels) {
            assertTrue("Codex model must be OpenAI: " + m,
                    m.startsWith("o1") || m.startsWith("o3") || m.startsWith("gpt-"));
        }
    }

    @Test
    public void isAlienModelForAgentDetectsMismatches() {
        assertTrue(CLIAgentManager.isAlienModelForAgent("antigravity", "claude-3-7-sonnet"));
        assertTrue(CLIAgentManager.isAlienModelForAgent("antigravity", "gpt-4o"));
        assertFalse(CLIAgentManager.isAlienModelForAgent("antigravity", "gemini-2.5-pro"));

        assertTrue(CLIAgentManager.isAlienModelForAgent("claude_code", "gemini-2.5-pro"));
        assertTrue(CLIAgentManager.isAlienModelForAgent("claude_code", "o3-mini"));
        assertFalse(CLIAgentManager.isAlienModelForAgent("claude_code", "claude-3-7-sonnet"));

        assertTrue(CLIAgentManager.isAlienModelForAgent("codex", "claude-3-7-sonnet"));
        assertTrue(CLIAgentManager.isAlienModelForAgent("codex", "gemini-2.0-flash"));
        assertFalse(CLIAgentManager.isAlienModelForAgent("codex", "o3-mini"));
    }

    @Test
    public void parseModelsFromCliOutputExtractsValidModels() {
        String agySample = "Available models:\n" +
                "- gemini-2.5-pro (default)\n" +
                "- gemini-2.0-flash\n" +
                "- gemini-2.0-pro\n" +
                "Use agy --model <model> to switch.\n";
        java.util.List<String> parsedAgy = CLIAgentManager.parseModelsFromCliOutput("antigravity", agySample);
        assertTrue(parsedAgy.contains("gemini-2.5-pro"));
        assertTrue(parsedAgy.contains("gemini-2.0-flash"));
        assertTrue(parsedAgy.contains("gemini-2.0-pro"));
        assertFalse(parsedAgy.contains("agy"));

        String claudeHelp = "Usage: claude [options]\n" +
                "  --model <model>  Model to use: claude-3-7-sonnet, claude-3-5-sonnet, claude-3-5-haiku\n";
        java.util.List<String> parsedClaude = CLIAgentManager.parseModelsFromCliOutput("claude_code", claudeHelp);
        assertTrue(parsedClaude.contains("claude-3-7-sonnet"));
        assertTrue(parsedClaude.contains("claude-3-5-sonnet"));
        assertTrue(parsedClaude.contains("claude-3-5-haiku"));
        assertFalse(parsedClaude.contains("options"));
    }

    @Test
    public void buildModelProbeCommandReturnsNonEmptyForSupportedAgents() {
        assertNotNull(CLIAgentManager.buildModelProbeCommand(CLIAgentManager.findAgent("antigravity")));
        assertNotNull(CLIAgentManager.buildModelProbeCommand(CLIAgentManager.findAgent("claude_code")));
        assertNotNull(CLIAgentManager.buildModelProbeCommand(CLIAgentManager.findAgent("codex")));
        assertNotNull(CLIAgentManager.buildModelProbeCommand(CLIAgentManager.findAgent("opencode")));
    }

    @Test
    public void defaultModelIsAlwaysTheCliOwnDefault() {
        // Regression (device screenshot, OpenCode): the app invented per-agent
        // model ids — "deepseek-r1" for OpenCode — and forced them via
        // --model, while the CLI's own list/default was a different model.
        // "Default" must be the no---model sentinel for EVERY agent.
        for (CLIAgentManager.CLIAgent agent : CLIAgentManager.AGENTS) {
            assertEquals("default", CLIAgentManager.defaultModelForAgent(agent.id));
        }
        assertEquals("default", CLIAgentManager.defaultModelForAgent(null));
        assertEquals("default", CLIAgentManager.defaultModelForAgent("unknown_agent"));
    }

    @Test
    public void thinkingOnlySupportedWhereTheGuiCommandAppliesIt() {
        assertTrue(CLIAgentManager.isThinkingSupported("claude_code"));
        assertTrue(CLIAgentManager.isThinkingSupported("antigravity"));
        assertTrue(CLIAgentManager.isThinkingSupported("codex"));
        // OpenCode became supported when its GUI mode went LINKED: the level
        // is sent to the server as the prompt's `variant` field, resolved
        // against the model's own variant names (see OpenCodeLinkTest).
        assertTrue(CLIAgentManager.isThinkingSupported("opencode"));
        // These CLIs get no thinking flag/env/variant from the GUI at all,
        // so a non-Off level for them was a chip that lied.
        assertFalse(CLIAgentManager.isThinkingSupported("grok"));
        assertFalse(CLIAgentManager.isThinkingSupported("kiro"));
        assertFalse(CLIAgentManager.isThinkingSupported("kilo"));
        assertFalse(CLIAgentManager.isThinkingSupported("github_copilot"));
        assertFalse(CLIAgentManager.isThinkingSupported("custom"));
        assertFalse(CLIAgentManager.isThinkingSupported(null));
    }

    @Test
    public void thinkingLevelsForUnsupportedAgentsAreOffOnly() {
        assertEquals(java.util.Collections.singletonList("Off"),
                CLIAgentManager.getAvailableThinkingLevels("grok"));
        assertEquals(CLIAgentManager.THINKING_LEVELS,
                CLIAgentManager.getAvailableThinkingLevels("claude_code"));
        assertEquals(CLIAgentManager.THINKING_LEVELS,
                CLIAgentManager.getAvailableThinkingLevels("opencode"));
    }

    // ------------------------------------------------------------------
    // Real default model fetch: values come from the CLI's own config /
    // state files, parsed exactly — a wrong parse here would put a wrong
    // model name on the chip, which is the glitch being fixed.
    // ------------------------------------------------------------------

    @Test
    public void jsonStringFieldParsesConfigModel() {
        String config = "{\n  \"$schema\": \"https://opencode.ai/config.json\",\n"
                + "  \"model\": \"opencode/deepseek-v4.1-flash-free\",\n  \"theme\": \"dark\"\n}";
        assertEquals("opencode/deepseek-v4.1-flash-free",
                CLIAgentManager.parseJsonStringField(config, "model"));
        assertEquals(null, CLIAgentManager.parseJsonStringField(config, "small_model"));
        assertEquals(null, CLIAgentManager.parseJsonStringField(null, "model"));
        assertEquals(null, CLIAgentManager.parseJsonStringField("", "model"));
    }

    @Test
    public void opencodeRecentModelComesFromTheRecentArray() {
        // Shape of ~/.local/state/opencode/model.json as written by the TUI.
        String state = "{\"favorite\":[{\"providerID\":\"opencode\",\"modelID\":\"gpt-5-nano\"}],"
                + "\"recent\":[{\"providerID\":\"opencode\",\"modelID\":\"big-pickle\"},"
                + "{\"providerID\":\"opencode\",\"modelID\":\"deepseek-v4.1-flash-free\"}],"
                + "\"variant\":{}}";
        assertEquals("opencode/big-pickle", CLIAgentManager.parseOpencodeRecentModel(state));
        // No recent array -> nothing to report (a favourite is NOT a default).
        assertEquals(null, CLIAgentManager.parseOpencodeRecentModel(
                "{\"favorite\":[{\"providerID\":\"opencode\",\"modelID\":\"gpt-5-nano\"}]}"));
        assertEquals(null, CLIAgentManager.parseOpencodeRecentModel(null));
        assertEquals(null, CLIAgentManager.parseOpencodeRecentModel("not json"));
    }

    @Test
    public void opencodeDefaultResolutionFollowsTheClisOwnPriority() {
        // Config beats last-used beats built-in; the built-in free default
        // (opencode/big-pickle) applies only with no provider connected.
        assertEquals("anthropic/claude-sonnet-4",
                CLIAgentManager.resolveOpencodeDefaultModel(
                        "anthropic/claude-sonnet-4", "opencode/big-pickle", true));
        assertEquals("opencode/big-pickle",
                CLIAgentManager.resolveOpencodeDefaultModel(null, "opencode/big-pickle", true));
        assertEquals("opencode/big-pickle",
                CLIAgentManager.resolveOpencodeDefaultModel(null, null, false));
        assertEquals(null,
                CLIAgentManager.resolveOpencodeDefaultModel(null, null, true));
    }

    @Test
    public void codexTomlModelIsTopLevelOnly() {
        String config = "# codex config\nmodel = \"gpt-5-codex\"\nmodel_provider = \"openai\"\n\n"
                + "[profiles.work]\nmodel = \"gpt-5\"\n";
        assertEquals("gpt-5-codex", CLIAgentManager.parseCodexTomlModel(config));
        // A model that only exists inside a profile section is not the default.
        assertEquals(null, CLIAgentManager.parseCodexTomlModel(
                "model_provider = \"openai\"\n[profiles.work]\nmodel = \"gpt-5\"\n"));
        assertEquals(null, CLIAgentManager.parseCodexTomlModel(null));
        assertEquals(null, CLIAgentManager.parseCodexTomlModel(""));
    }

    // ------------------------------------------------------------------
    // Running-agent check: the reopen guard stands or falls on matching
    // the agent's process without false positives.
    // ------------------------------------------------------------------

    @Test
    public void cmdlineMatchingFindsAgentProcesses() {
        assertEquals("opencode", CLIAgentManager.matchAgentInCmdline(
                "/root/.opencode/bin/opencode\u0000"));
        assertEquals("claude_code", CLIAgentManager.matchAgentInCmdline(
                "node\u0000/root/.nvm/versions/node/v22/lib/node_modules/@anthropic-ai/claude-code/cli.js\u0000"));
        assertEquals("github_copilot", CLIAgentManager.matchAgentInCmdline(
                "npx\u0000-y\u0000@github/copilot\u0000"));
        assertEquals("codex", CLIAgentManager.matchAgentInCmdline(
                "/usr/local/bin/codex\u0000"));
        assertEquals("antigravity", CLIAgentManager.matchAgentInCmdline(
                "/root/.local/bin/agy\u0000"));
    }

    @Test
    public void cmdlineMatchingIgnoresShellsAndLookalikeArgs() {
        // argv[0] decides: a grep FOR opencode is not the agent running.
        assertEquals(null, CLIAgentManager.matchAgentInCmdline("grep\u0000opencode\u0000file.txt\u0000"));
        assertEquals(null, CLIAgentManager.matchAgentInCmdline("bash\u0000"));
        assertEquals(null, CLIAgentManager.matchAgentInCmdline("-bash\u0000"));
        assertEquals(null, CLIAgentManager.matchAgentInCmdline("proot\u0000--rootfs=/data/data/com.xyzide/files/usr/var/lib/proot-distro/containers/ubuntu/rootfs\u0000"));
        assertEquals(null, CLIAgentManager.matchAgentInCmdline(null));
        assertEquals(null, CLIAgentManager.matchAgentInCmdline(""));
    }

    // ------------------------------------------------------------------
    // Live model list: OpenCode prints full provider/model ids, one per
    // line. The old parser kept only the part after the last '/' and only
    // when it started with deepseek/claude/gpt/gemini/o1/o3, so from the
    // device-verified list below exactly ONE model survived
    // (deepseek-v4.1-flash-free) — and even that one lost its prefix.
    // ------------------------------------------------------------------

    /** `opencode models` output as seen on X's phone (the TUI's free-model
     * list) plus the usual connected providers. */
    private static final String OPENCODE_MODELS_SAMPLE =
            "opencode/big-pickle\n" +
            "opencode/hy4-flash-free\n" +
            "opencode/iceman-free\n" +
            "opencode/inkling-2-small-free\n" +
            "opencode/longcat-2.5-free\n" +
            "opencode/flint-40b-free\n" +
            "opencode/deepseek-v4.1-flash-free\n" +
            "opencode/nemotron-3.5-super-vl-free\n" +
            "opencode/beam-free\n" +
            "opencode/mimo-v2.6-flash-free\n" +
            "opencode/glm-5.3-flash-free\n" +
            "anthropic/claude-sonnet-4-5\n" +
            "openai/gpt-5\n" +
            "google/gemini-2.5-pro\n";

    @Test
    public void opencodeParserKeepsTheFullProviderModelList() {
        java.util.List<String> parsed =
                CLIAgentManager.parseModelsFromCliOutput("opencode", OPENCODE_MODELS_SAMPLE);
        assertEquals(14, parsed.size());
        // CLI order preserved, ids kept whole (provider prefix included).
        assertEquals("opencode/big-pickle", parsed.get(0));
        assertTrue(parsed.contains("opencode/hy4-flash-free"));
        assertTrue(parsed.contains("opencode/iceman-free"));
        assertTrue(parsed.contains("opencode/inkling-2-small-free"));
        assertTrue(parsed.contains("opencode/longcat-2.5-free"));
        assertTrue(parsed.contains("opencode/flint-40b-free"));
        assertTrue(parsed.contains("opencode/deepseek-v4.1-flash-free"));
        assertTrue(parsed.contains("opencode/nemotron-3.5-super-vl-free"));
        assertTrue(parsed.contains("opencode/beam-free"));
        assertTrue(parsed.contains("opencode/mimo-v2.6-flash-free"));
        assertTrue(parsed.contains("opencode/glm-5.3-flash-free"));
        assertTrue(parsed.contains("anthropic/claude-sonnet-4-5"));
        assertTrue(parsed.contains("openai/gpt-5"));
        assertTrue(parsed.contains("google/gemini-2.5-pro"));
        // No stripped duplicates of the part after the slash.
        assertFalse(parsed.contains("big-pickle"));
        assertFalse(parsed.contains("deepseek-v4.1-flash-free"));
        assertFalse(parsed.contains("gpt-5"));
        assertFalse(parsed.contains("gemini-2.5-pro"));
    }

    @Test
    public void kiloParserAlsoKeepsProviderModelIds() {
        java.util.List<String> parsed = CLIAgentManager.parseModelsFromCliOutput(
                "kilo", "kilo/kilo-code-free\nopencode/deepseek-v4.1-flash-free\n");
        assertTrue(parsed.contains("kilo/kilo-code-free"));
        assertTrue(parsed.contains("opencode/deepseek-v4.1-flash-free"));
    }

    @Test
    public void providerModelIdCheckRejectsPathFragments() {
        assertTrue(CLIAgentManager.looksLikeProviderModelId("opencode/big-pickle"));
        assertTrue(CLIAgentManager.looksLikeProviderModelId("anthropic/claude-sonnet-4-5"));
        assertTrue(CLIAgentManager.looksLikeProviderModelId("openrouter/openai/gpt-5"));
        // Help-text path fragments are not models.
        assertFalse(CLIAgentManager.looksLikeProviderModelId("config/opencode"));
        assertFalse(CLIAgentManager.looksLikeProviderModelId("usr/bin"));
        assertFalse(CLIAgentManager.looksLikeProviderModelId("data/data"));
        assertFalse(CLIAgentManager.looksLikeProviderModelId("opencode/opencode"));
        assertFalse(CLIAgentManager.looksLikeProviderModelId("opencode/config.json"));
        assertFalse(CLIAgentManager.looksLikeProviderModelId("noslash"));
        assertFalse(CLIAgentManager.looksLikeProviderModelId(null));
    }

    @Test
    public void opencodeParserIgnoresHelpTextNoise() {
        String help = "Usage: opencode [options] [command]\n" +
                "Config: ~/.config/opencode/opencode.json\n" +
                "Run opencode models to list models.\n" +
                "opencode/big-pickle\n";
        java.util.List<String> parsed =
                CLIAgentManager.parseModelsFromCliOutput("opencode", help);
        assertTrue(parsed.contains("opencode/big-pickle"));
        assertFalse(parsed.contains("config/opencode"));
        assertFalse(parsed.contains("opencode/opencode"));
        assertFalse(parsed.contains("opencode.json"));
    }

    // ---------------- Standalone OpenCode TUI detection ----------------

    @Test
    public void standaloneOpenCodeTuiIsDetected() {
        // /proc cmdlines are NUL-separated; a bare `opencode` (optionally
        // with flags) is a standalone TUI with its own private session.
        assertTrue(CLIAgentManager.isStandaloneOpenCodeCmdline("opencode\u0000"));
        assertTrue(CLIAgentManager.isStandaloneOpenCodeCmdline(
                "/root/.opencode/bin/opencode\u0000--model\u0000opencode/big-pickle\u0000"));
    }

    @Test
    public void attachedServedAndOneShotOpenCodeAreNotStandalone() {
        assertFalse(CLIAgentManager.isStandaloneOpenCodeCmdline(
                "opencode\u0000attach\u0000http://127.0.0.1:4096\u0000--session\u0000ses_1\u0000"));
        assertFalse(CLIAgentManager.isStandaloneOpenCodeCmdline(
                "opencode\u0000serve\u0000--hostname\u0000127.0.0.1\u0000--port\u00004096\u0000"));
        assertFalse(CLIAgentManager.isStandaloneOpenCodeCmdline(
                "opencode\u0000run\u0000hello\u0000"));
    }

    @Test
    public void otherAgentsAndJunkAreNotStandaloneOpenCode() {
        assertFalse(CLIAgentManager.isStandaloneOpenCodeCmdline("claude\u0000"));
        assertFalse(CLIAgentManager.isStandaloneOpenCodeCmdline("bash\u0000-lc\u0000opencode\u0000"));
        assertFalse(CLIAgentManager.isStandaloneOpenCodeCmdline(null));
        assertFalse(CLIAgentManager.isStandaloneOpenCodeCmdline(""));
    }

    @Test
    public void descendantCollectionWalksTheWholeTreeOnly() {
        java.util.Map<Integer, java.util.List<Integer>> tree = new java.util.HashMap<>();
        tree.put(100, java.util.Arrays.asList(200, 201));
        tree.put(200, java.util.Arrays.asList(300));
        tree.put(300, java.util.Arrays.asList(400));
        tree.put(999, java.util.Arrays.asList(100)); // unrelated branch pointing in
        java.util.Set<Integer> found = CLIAgentManager.collectDescendantPids(100, tree);
        assertEquals(new HashSet<>(java.util.Arrays.asList(200, 201, 300, 400)), found);
        assertTrue(CLIAgentManager.collectDescendantPids(555, tree).isEmpty());
    }
}
