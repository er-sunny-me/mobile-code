package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CLI Agent Manager — Manages launching and monitoring CLI-based AI coding agents
 * (Claude Code, Antigravity, Codex, etc.) as background Termux sessions.
 *
 * <p>Two ways to drive an agent:</p>
 * <ul>
 *   <li><b>Interactive</b> — {@link #buildLaunchCommand} types the agent's TUI into a real
 *       terminal session. Used by the terminal screen.</li>
 *   <li><b>GUI</b> — {@link #buildGuiPromptCommand} runs the agent non-interactively with a
 *       prompt so its output can be captured and rendered as chat. Used by Agent Chat.</li>
 * </ul>
 */
public class CLIAgentManager {

    private static final String PREFS_NAME = "cli_agent_prefs";
    private static final String KEY_SELECTED_AGENT = "selected_agent";
    private static final String KEY_CUSTOM_COMMAND = "custom_agent_command";
    private static final String KEY_GUI_COMMAND_PREFIX = "gui_command_";
    /** How long a shell probe of installed binaries stays valid. */
    private static final long PROBE_CACHE_TTL_MS = 5000;

    private final Context context;
    private final SharedPreferences prefs;

    private java.util.Map<String, FoundBinary> probeCache;
    private long probeCacheTime;

    /**
     * Represents a CLI coding agent that can be installed and run in the terminal.
     */
    public static class CLIAgent {
        public final String id;
        public final String name;
        public final String description;
        public final String binaryName;        // e.g. "claude", "aider"
        public final String installCommand;     // pip/npm install command
        public final String runCommandTemplate; // interactive command, may contain {prompt}/{workspace}
        /**
         * Non-interactive command used by the Agent Chat GUI. Must print the agent's answer to
         * stdout and exit. {@code {prompt}} is replaced with the user's prompt. An empty value
         * means "this agent has no known GUI mode" — the GUI opens the terminal instead.
         */
        public final String guiPromptTemplate;
        public final String iconEmoji;

        public CLIAgent(String id, String name, String description, String binaryName,
                        String installCommand, String runCommandTemplate, String iconEmoji) {
            this(id, name, description, binaryName, installCommand, runCommandTemplate, "", iconEmoji);
        }

        public CLIAgent(String id, String name, String description, String binaryName,
                        String installCommand, String runCommandTemplate,
                        String guiPromptTemplate, String iconEmoji) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.binaryName = binaryName;
            this.installCommand = installCommand;
            this.runCommandTemplate = runCommandTemplate;
            this.guiPromptTemplate = guiPromptTemplate == null ? "" : guiPromptTemplate;
            this.iconEmoji = iconEmoji;
        }
    }

    /** All supported CLI agents */
    public static final CLIAgent[] AGENTS = {
        new CLIAgent(
            "claude_code",
            "Claude Code",
            "Anthropic's agentic coding CLI. Writes, edits, and debugs code autonomously.",
            "claude",
            "if ! command -v claude &> /dev/null; then if ! command -v npm &> /dev/null; then apt-get update && apt-get install -y nodejs npm || pkg install -y nodejs; fi; npm install -g @anthropic-ai/claude-code; fi",
            "claude",
            "claude -p \"{prompt}\"",
            "🟠"
        ),
        new CLIAgent(
            "antigravity",
            "Antigravity CLI",
            "Google DeepMind's advanced agentic coding assistant.",
            "agy",
            "if ! command -v agy &> /dev/null; then if ! command -v curl &> /dev/null; then apt-get update && apt-get install -y curl || pkg install -y curl; fi; curl -k -fsSL https://antigravity.google/cli/install.sh | bash; grep -qF '$HOME/.local/bin' ~/.bashrc 2>/dev/null || echo 'export PATH=\"$HOME/.local/bin:$PATH\"' >> ~/.bashrc; fi; export PATH=\"$HOME/.local/bin:/data/data/com.termux/files/home/.local/bin:/root/.local/bin:$PATH\"; hash -r 2>/dev/null || true; command -v agy && agy --version || echo 'NOTE: installed but not on PATH yet — restart the terminal (exec $SHELL -l) or run: export PATH=\"$HOME/.local/bin:$PATH\"'",
            "export PATH=\"$HOME/.local/bin:$PATH\" && agy",
            "export PATH=\"$HOME/.local/bin:$PATH\" && agy -p \"{prompt}\"",
            "🔵"
        ),
        new CLIAgent(
            "codex",
            "Codex CLI",
            "OpenAI's lightweight coding agent for the terminal.",
            "codex",
            "if ! command -v codex &> /dev/null; then if ! command -v npm &> /dev/null; then apt-get update && apt-get install -y nodejs npm || pkg install -y nodejs; fi; npm install -g @openai/codex; fi",
            "codex",
            "codex exec \"{prompt}\"",
            "⚪"
        ),
        new CLIAgent(
            "kiro",
            "Kiro CLI",
            "Agentic terminal IDE for spec-driven workflows.",
            "kiro-cli",
            "if ! command -v kiro-cli &> /dev/null; then if ! command -v curl &> /dev/null; then apt-get update && apt-get install -y curl || pkg install -y curl; fi; curl -k -fsSL https://cli.kiro.dev/install | bash; grep -qF '$HOME/.local/bin' ~/.bashrc 2>/dev/null || echo 'export PATH=\"$HOME/.local/bin:$PATH\"' >> ~/.bashrc; fi",
            "export PATH=\"$HOME/.local/bin:$PATH\" && kiro-cli",
            "export PATH=\"$HOME/.local/bin:$PATH\" && kiro-cli chat \"{prompt}\"",
            "🟢"
        ),
        new CLIAgent(
            "kilo",
            "Kilo CLI",
            "Multi-model vendor-neutral AI coding assistant.",
            "kilo",
            "if ! command -v kilo &> /dev/null; then if ! command -v npm &> /dev/null; then apt-get update && apt-get install -y nodejs npm || pkg install -y nodejs; fi; npm install -g @kilocode/cli; fi",
            "export PATH=\"$HOME/.local/bin:$PATH\" && kilo",
            "export PATH=\"$HOME/.local/bin:$PATH\" && kilo run \"{prompt}\"",
            "🟣"
        ),
        new CLIAgent(
            "opencode",
            "OpenCode CLI",
            "Extensible Go-based AI coding TUI with plugins.",
            "opencode",
            "if ! command -v opencode &> /dev/null && [ ! -x \"$HOME/.opencode/bin/opencode\" ]; then if ! command -v curl &> /dev/null; then apt-get update && apt-get install -y curl || pkg install -y curl; fi; curl -k -fsSL https://opencode.ai/install | bash; grep -qF '$HOME/.opencode/bin' ~/.bashrc 2>/dev/null || echo 'export PATH=\"$HOME/.opencode/bin:$PATH\"' >> ~/.bashrc; fi; export PATH=\"$HOME/.opencode/bin:/data/data/com.termux/files/home/.opencode/bin:/root/.opencode/bin:$PATH\"; hash -r 2>/dev/null || true; command -v opencode && opencode --version || { [ -x \"$HOME/.opencode/bin/opencode\" ] && \"$HOME/.opencode/bin/opencode\" --version || echo 'NOTE: installed but not on PATH yet — restart the terminal (exec $SHELL -l) or run: export PATH=\"$HOME/.opencode/bin:$PATH\"'; }",
            "export PATH=\"$HOME/.opencode/bin:$PATH\" && opencode",
            "export PATH=\"$HOME/.opencode/bin:$PATH\" && opencode run \"{prompt}\"",
            "🟡"
        ),
        new CLIAgent(
            "grok",
            "Grok CLI",
            "xAI's Grok coding assistant for the terminal.",
            "grok",
            "if ! command -v grok &> /dev/null; then if ! command -v curl &> /dev/null; then apt-get update && apt-get install -y curl || pkg install -y curl; fi; curl -fsSL https://x.ai/cli/install.sh | bash; grep -qF '$HOME/.grok/bin' ~/.bashrc 2>/dev/null || echo 'export PATH=\"$HOME/.grok/bin:$PATH\"' >> ~/.bashrc; fi",
            "export PATH=\"$HOME/.grok/bin:$PATH\" && grok",
            "export PATH=\"$HOME/.grok/bin:$PATH\" && grok -p \"{prompt}\"",
            "⚫"
        ),
        new CLIAgent(
            "github_copilot",
            "GitHub Copilot CLI",
            "GitHub's AI coding assistant for the terminal.",
            "copilot",
            "if ! command -v copilot &> /dev/null; then echo 'Installing GitHub Copilot...'; if ! command -v npm &> /dev/null; then apt-get update && apt-get install -y curl ca-certificates && curl -fsSL https://deb.nodesource.com/setup_lts.x | bash - && apt-get install -y nodejs || pkg install -y nodejs; fi; npm install -g @github/copilot || { echo 'Installation failed. Press enter to exit.'; read; }; fi",
            "npx -y @github/copilot",
            "npx -y @github/copilot -p \"{prompt}\"",
            "🐙"
        ),
        new CLIAgent(
            "custom",
            "Custom Agent",
            "Run any CLI agent command you want. Full control.",
            "",
            "",
            "{custom_command}",
            "{custom_command}",
            "⚙️"
        )
    };

    public boolean isAgentEnabled(String agentId) {
        // By default, agents are enabled
        return prefs.getBoolean("agent_enabled_" + agentId, true);
    }

    public void setAgentEnabled(String agentId, boolean enabled) {
        prefs.edit().putBoolean("agent_enabled_" + agentId, enabled).apply();
    }

    public CLIAgentManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Get all available agents with their install status.
     *
     * <p>{@code installed} means the binary is in the environment agents actually run in
     * (the workshop when present, else native Termux). {@code installedElsewhere} covers the
     * classic "install hai phir bhi install bol raha" trap: the binary exists, but in the
     * OTHER environment (e.g. installed natively before the Ubuntu workshop existed, or the
     * phone ships the new proot-distro layout) — the dashboard then offers Reinstall instead
     * of pretending nothing is there.</p>
     */
    public List<AgentStatus> getAgentStatuses() {
        java.util.Map<String, FoundBinary> detailed = probeDetailed();
        boolean workshop = isWorkshopAvailable();
        List<AgentStatus> statuses = new ArrayList<>();
        for (CLIAgent agent : AGENTS) {
            if (agent.id.equals("custom")) {
                statuses.add(new AgentStatus(agent, true, false, null));
                continue;
            }
            FoundBinary found = agent.binaryName == null ? null : detailed.get(agent.binaryName);
            boolean inRunEnv = found != null && (workshop ? found.inWorkshop : found.inTermux);
            boolean elsewhere = found != null && (workshop
                    ? (found.inTermux && !found.inWorkshop)
                    : (found.inWorkshop && !found.inTermux));
            statuses.add(new AgentStatus(agent, inRunEnv, elsewhere,
                    found == null ? null : found.path));
        }
        return statuses;
    }

    /** True when the agent's binary is on PATH in the environment the app runs agents in. */
    public boolean isAgentInstalled(CLIAgent agent) {
        if (agent.binaryName == null || agent.binaryName.isEmpty()) return false;
        FoundBinary found = probeDetailed().get(agent.binaryName);
        if (found == null) return false;
        return isWorkshopAvailable() ? found.inWorkshop : found.inTermux;
    }

    /** True when the binary exists only in the environment agents do NOT run in. */
    public boolean isAgentInstalledElsewhere(CLIAgent agent) {
        if (agent.binaryName == null || agent.binaryName.isEmpty()) return false;
        FoundBinary found = probeDetailed().get(agent.binaryName);
        if (found == null) return false;
        return isWorkshopAvailable() ? (found.inTermux && !found.inWorkshop) : false;
    }

    /** Drop the cached install state so the next check re-reads the environment. */
    public synchronized void invalidateInstallCache() {
        probeCache = null;
    }

    /** Where a binary was seen. Workshop paths win for display (that is the run env). */
    static class FoundBinary {
        boolean inWorkshop;
        boolean inTermux;
        String path;
    }

    /** Shell script printing one {@code binary=path} line per detected agent binary. */
    static String buildProbeScript(String names) {
        String binDirs = "\"$HOME/.local/bin\" \"$HOME/.opencode/bin\" \"$HOME/.grok/bin\""
                + " \"/data/data/com.termux/files/home/.local/bin\""
                + " \"/data/data/com.termux/files/home/.opencode/bin\""
                + " \"/data/data/com.termux/files/home/.grok/bin\""
                + " \"/root/.local/bin\" \"/root/.opencode/bin\" \"/root/.grok/bin\"";
        return "export PATH=\"$HOME/.local/bin:$HOME/.opencode/bin:$HOME/.grok/bin"
                + ":/data/data/com.termux/files/home/.local/bin"
                + ":/data/data/com.termux/files/home/.opencode/bin"
                + ":/data/data/com.termux/files/home/.grok/bin"
                + ":/root/.local/bin:/root/.opencode/bin:/root/.grok/bin:$PATH\" 2>/dev/null || true;"
                + " for b in " + names + "; do"
                + " p=$(command -v \"$b\" 2>/dev/null);"
                + " if [ -n \"$p\" ]; then echo \"$b=$p\"; continue; fi;"
                + " for d in " + binDirs + "; do"
                + " if [ -x \"$d/$b\" ]; then echo \"$b=$d/$b\"; break; fi; done; done";
    }

    /** Fold one probe run's {@code binary=path} lines into the shared map. */
    static void parseProbeOutput(String output, boolean inWorkshop,
                                 java.util.Map<String, FoundBinary> into) {
        if (output == null) return;
        for (String rawLine : output.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String name = line.substring(0, eq).trim();
            String path = line.substring(eq + 1).trim();
            if (name.isEmpty() || path.isEmpty()) continue;
            FoundBinary found = into.get(name);
            if (found == null) {
                found = new FoundBinary();
                into.put(name, found);
            }
            if (inWorkshop) {
                found.inWorkshop = true;
                found.path = path;
            } else {
                found.inTermux = true;
                if (found.path == null) found.path = path;
            }
        }
    }

    /**
     * Direct filesystem probe for known agent binary locations.
     * Checks both the workshop rootfs (Ubuntu/Debian/Alpine) and native Termux home/usr paths.
     * This is instant (no subshell fork/exec) and resilient against missing environment variables.
     */
    static void probeFilesystemDirectly(java.util.Map<String, FoundBinary> into) {
        String[] workshopSubdirs = {
            "root/.local/bin",
            "root/.opencode/bin",
            "root/.grok/bin",
            "usr/local/bin",
            "usr/bin",
            "bin"
        };
        String[] termuxDirs = {
            TermuxConstants.TERMUX_HOME_DIR_PATH + "/.local/bin",
            TermuxConstants.TERMUX_HOME_DIR_PATH + "/.opencode/bin",
            TermuxConstants.TERMUX_HOME_DIR_PATH + "/.grok/bin",
            TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH,
            TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin"
        };

        // 1. Check workshop rootfs paths
        for (String distro : WORKSHOP_DISTRO_CHOICES) {
            String[] rootfsCandidates = {
                containerRootfsPath(distro),
                legacyRootfsPath(distro)
            };
            for (String rootfsPath : rootfsCandidates) {
                File rootfsDir = new File(rootfsPath);
                if (!rootfsDir.isDirectory()) continue;
                for (CLIAgent agent : AGENTS) {
                    if (agent.binaryName == null || agent.binaryName.isEmpty()) continue;
                    for (String subdir : workshopSubdirs) {
                        File binaryFile = new File(rootfsDir, subdir + "/" + agent.binaryName);
                        if (binaryFile.isFile()) {
                            FoundBinary found = into.get(agent.binaryName);
                            if (found == null) {
                                found = new FoundBinary();
                                into.put(agent.binaryName, found);
                            }
                            found.inWorkshop = true;
                            if (found.path == null) {
                                found.path = "/" + subdir + "/" + agent.binaryName;
                            }
                            break;
                        }
                    }
                }
            }
        }

        // 2. Check native Termux paths
        for (CLIAgent agent : AGENTS) {
            if (agent.binaryName == null || agent.binaryName.isEmpty()) continue;
            for (String dir : termuxDirs) {
                File binaryFile = new File(dir, agent.binaryName);
                if (binaryFile.isFile()) {
                    FoundBinary found = into.get(agent.binaryName);
                    if (found == null) {
                        found = new FoundBinary();
                        into.put(agent.binaryName, found);
                    }
                    found.inTermux = true;
                    if (found.path == null) {
                        found.path = binaryFile.getAbsolutePath();
                    }
                    break;
                }
            }
        }
    }

    /**
     * Ask the shell which agent binaries exist — one process per environment instead of one
     * per agent. BOTH native Termux and (when present) the workshop rootfs are probed, because
     * that is where the app's terminal opens and therefore where the install flow puts
     * binaries — but users also install things by hand in the other one.
     *
     * <p>The probe exports every installer bin dir before calling {@code command -v} and also
     * checks the files directly. A bare {@code command -v} misses curl-installed agents
     * (agy, kiro-cli → {@code ~/.local/bin}, opencode → {@code ~/.opencode/bin},
     * grok → {@code ~/.grok/bin}) whose dirs are only added to PATH via {@code ~/.bashrc},
     * which a fresh non-interactive shell may not have sourced yet. That mismatch made the
     * dashboard say "NOT INSTALLED" right after a successful install.
     */
    private synchronized java.util.Map<String, FoundBinary> probeDetailed() {
        long now = System.currentTimeMillis();
        if (probeCache != null && now - probeCacheTime < PROBE_CACHE_TTL_MS) {
            return probeCache;
        }

        java.util.Map<String, FoundBinary> found = new java.util.HashMap<>();
        // Fast direct filesystem probe first
        probeFilesystemDirectly(found);

        StringBuilder names = new StringBuilder();
        for (CLIAgent agent : AGENTS) {
            if (agent.binaryName == null || agent.binaryName.isEmpty()) continue;
            if (names.length() > 0) names.append(' ');
            names.append(agent.binaryName);
        }

        if (names.length() > 0) {
            String script = buildProbeScript(names.toString());
            // Native Termux first — always available.
            parseProbeOutput(runProbeScript(script), false, found);
            // Then the workshop (the run env) when it exists.
            if (isWorkshopAvailable()) {
                String installed = installedWorkshopDistro();
                String wrapped = wrapForWorkshop(script,
                        installed != null ? installed : getWorkshopDistro(context));
                parseProbeOutput(runProbeScript(wrapped), true, found);
            }
        }

        probeCache = Collections.unmodifiableMap(found);
        probeCacheTime = now;
        return probeCache;
    }

    /** Backwards-compatible run-env binary set (kept for callers/tests). */
    private synchronized Set<String> probeInstalledBinaries() {
        boolean workshop = isWorkshopAvailable();
        Set<String> out = new HashSet<>();
        for (java.util.Map.Entry<String, FoundBinary> entry : probeDetailed().entrySet()) {
            FoundBinary found = entry.getValue();
            if (workshop ? found.inWorkshop : found.inTermux) out.add(entry.getKey());
        }
        return Collections.unmodifiableSet(out);
    }

    /** Run a probe script through the native Termux shell and return its stdout. */
    private String runProbeScript(String command) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", "-lc", command);
            File dir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
            if (!dir.isDirectory()) {
                dir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            }
            pb.directory(dir);
            pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/"
                    + ":" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/applets"
                    + ":" + TermuxConstants.TERMUX_HOME_DIR_PATH + "/.local/bin"
                    + ":" + System.getenv("PATH"));
            pb.environment().put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/lib");
            pb.environment().put("TMPDIR", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/tmp");
            pb.environment().put("TERM", "xterm-256color");
            pb.environment().put("LANG", "en_US.UTF-8");
            pb.redirectErrorStream(true);
            process = pb.start();
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
            process.waitFor();
            return output.toString();
        } catch (Exception ignored) {
            // Treat an unreadable environment as "nothing installed".
            return "";
        } finally {
            if (process != null) process.destroy();
        }
    }

    // ------------------------------------------------------------------
    // Environment
    // ------------------------------------------------------------------

    /** Distros the setup wizard offers. Ubuntu is bundled in the APK. */
    public static final String[] WORKSHOP_DISTRO_CHOICES = {"ubuntu", "debian", "alpine"};

    private static final String DEFAULT_WORKSHOP_DISTRO = "ubuntu";
    private static final String PREFS_WORKSHOP = "ZeroCodePrefs";
    private static final String KEY_WORKSHOP_DISTRO = "workshop_distro";

    private static String workshopDistroBase() {
        return TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/";
    }

    /** Legacy layout (old bash proot-distro): installed-rootfs/&lt;name&gt;. */
    public static String legacyRootfsPath(String distro) {
        return workshopDistroBase() + "installed-rootfs/" + distro;
    }

    /** New layout (Python proot-distro): containers/&lt;name&gt;/rootfs. */
    public static String containerRootfsPath(String distro) {
        return workshopDistroBase() + "containers/" + distro + "/rootfs";
    }

    /** The user's chosen distro, or "ubuntu" before first setup. */
    public static String getWorkshopDistro(Context context) {
        try {
            String distro = context.getSharedPreferences(PREFS_WORKSHOP, Context.MODE_PRIVATE)
                    .getString(KEY_WORKSHOP_DISTRO, DEFAULT_WORKSHOP_DISTRO);
            for (String choice : WORKSHOP_DISTRO_CHOICES) {
                if (choice.equals(distro)) return distro;
            }
        } catch (Exception ignored) {
        }
        return DEFAULT_WORKSHOP_DISTRO;
    }

    public static void setWorkshopDistro(Context context, String distro) {
        context.getSharedPreferences(PREFS_WORKSHOP, Context.MODE_PRIVATE)
                .edit().putString(KEY_WORKSHOP_DISTRO, distro).apply();
    }

    /**
     * True when the workshop rootfs the app's terminal opens in is installed. Checks the
     * selected distro in both the new ({@code containers/<name>/rootfs}) and legacy
     * ({@code installed-rootfs/<name>}) proot-distro layouts, plus the legacy ubuntu path
     * so installs from before the setup wizard still count.
     */
    public static boolean isWorkshopAvailable() {
        if (!new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/proot-distro").exists()) {
            return false;
        }
        if (new File(containerRootfsPath(DEFAULT_WORKSHOP_DISTRO)).exists()
                || new File(legacyRootfsPath(DEFAULT_WORKSHOP_DISTRO)).exists()) {
            return true;
        }
        for (String distro : WORKSHOP_DISTRO_CHOICES) {
            if (new File(containerRootfsPath(distro)).exists()
                    || new File(legacyRootfsPath(distro)).exists()) {
                return true;
            }
        }
        return false;
    }

    /** The installed distro, or null when none is installed yet. */
    public static String installedWorkshopDistro() {
        for (String distro : WORKSHOP_DISTRO_CHOICES) {
            if (new File(containerRootfsPath(distro)).exists()
                    || new File(legacyRootfsPath(distro)).exists()) {
                return distro;
            }
        }
        return null;
    }

    /**
     * Wrap a command so it runs inside the workshop rootfs — the same environment the
     * app's terminal opens in, and therefore where an install writes its binary.
     *
     * <p>Uses proot-distro's documented {@code login <distro> -- <command>} form.</p>
     */
    public static String wrapForWorkshop(String command) {
        return wrapForWorkshop(command, DEFAULT_WORKSHOP_DISTRO);
    }

    /** Variant that logs into an explicit distro (the user's setup choice). */
    public static String wrapForWorkshop(String command, String distro) {
        String name = (distro == null || distro.isEmpty()) ? DEFAULT_WORKSHOP_DISTRO : distro;
        return "proot-distro login " + name + " -- bash -lc " + singleQuote(command);
    }

    /** Single-quote a string for bash, escaping embedded single quotes. */
    public static String singleQuote(String s) {
        return "'" + (s == null ? "" : s.replace("'", "'\\''")) + "'";
    }

    public static final String KEY_AGENT_MODEL_PREFIX = "agent_model_";
    public static final String KEY_AGENT_THINKING_PREFIX = "agent_thinking_";

    public static final List<String> THINKING_LEVELS = Collections.unmodifiableList(
            Arrays.asList("Off", "Low (2k)", "Medium (8k)", "High (16k)", "Max (32k)")
    );

    public List<String> getAvailableThinkingLevels() {
        return THINKING_LEVELS;
    }

    public String getAgentThinkingLevel(String agentId) {
        return prefs.getString(KEY_AGENT_THINKING_PREFIX + agentId, "High (16k)");
    }

    public void setAgentThinkingLevel(String agentId, String level) {
        prefs.edit().putString(KEY_AGENT_THINKING_PREFIX + agentId, level).apply();
    }

    public static int getThinkingTokens(String level) {
        if (level == null) return 8192;
        if (level.startsWith("Off")) return 0;
        if (level.startsWith("Low")) return 2048;
        if (level.startsWith("Medium")) return 8192;
        if (level.startsWith("High")) return 16384;
        if (level.startsWith("Max") || level.startsWith("Extended")) return 32000;
        return 8192;
    }

    public static String defaultModelForAgent(String agentId) {
        if (agentId == null) return "default";
        switch (agentId) {
            case "claude_code":
                return "claude-3-7-sonnet";
            case "antigravity":
                return "gemini-2.5-pro";
            case "codex":
                return "o3-mini";
            case "opencode":
                return "deepseek-r1";
            case "kiro":
            case "kilo":
                return "claude-3-7-sonnet";
            case "grok":
                return "grok-2";
            case "github_copilot":
                return "gpt-4o";
            default:
                return "default";
        }
    }

    public List<String> getAvailableModels(String agentId) {
        List<String> models = new ArrayList<>();
        if (agentId == null) agentId = "";
        switch (agentId) {
            case "claude_code":
                models.add("claude-3-7-sonnet");
                models.add("claude-3-5-sonnet");
                models.add("claude-3-5-haiku");
                models.add("claude-3-opus");
                break;
            case "antigravity":
                models.add("gemini-2.5-pro");
                models.add("gemini-2.0-pro");
                models.add("gemini-2.0-flash");
                models.add("claude-3-7-sonnet");
                break;
            case "codex":
                models.add("o3-mini");
                models.add("o1");
                models.add("gpt-4o");
                models.add("gpt-4.5-preview");
                break;
            case "opencode":
                models.add("deepseek-r1");
                models.add("claude-3-7-sonnet");
                models.add("gpt-4o");
                models.add("gemini-2.0-flash");
                break;
            case "kiro":
            case "kilo":
                models.add("claude-3-7-sonnet");
                models.add("deepseek-r1");
                models.add("gpt-4o");
                models.add("claude-3-5-haiku");
                break;
            case "grok":
                models.add("grok-2");
                models.add("grok-2-mini");
                models.add("grok-beta");
                break;
            case "github_copilot":
                models.add("gpt-4o");
                models.add("claude-3-5-sonnet");
                models.add("o1-preview");
                break;
            default:
                models.add("default");
                break;
        }
        return models;
    }

    public String getAgentModel(String agentId) {
        return prefs.getString(KEY_AGENT_MODEL_PREFIX + agentId, defaultModelForAgent(agentId));
    }

    public void setAgentModel(String agentId, String model) {
        prefs.edit().putString(KEY_AGENT_MODEL_PREFIX + agentId, model).apply();
    }

    /**
     * Environment prelude prepended to every agent command.
     */
    public static String shellPrelude() {
        String cert = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/tls/cert.pem";
        String termuxHomeBin = "/data/data/com.termux/files/home";
        String termuxOpen = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/termux-open-url";
        String shimBody = "#!/bin/bash\n"
                + "url=\"$1\"\n"
                + "for opener in \"" + termuxOpen + "\" \"/data/data/com.termux/files/usr/bin/termux-open-url\" termux-open-url termux-open; do\n"
                + "  if command -v \"$opener\" >/dev/null 2>&1; then \"$opener\" \"$url\" >/dev/null 2>&1 & exit 0; fi\n"
                + "done\n"
                + "echo \"Open this URL on your phone browser: $url\"\n"
                + "exit 0\n";
        return "mkdir -p ~/.local/bin"
            + " && { [ -f ~/.local/bin/xdg-open ] || { printf '%b' '" + shimBody.replace("'", "'\\''").replace("\n", "\\n") + "' > ~/.local/bin/xdg-open && chmod +x ~/.local/bin/xdg-open; }; }"
            + " && export PATH=\"$HOME/.local/bin:$HOME/.opencode/bin:$HOME/.grok/bin:"
            + termuxHomeBin + "/.local/bin:" + termuxHomeBin + "/.opencode/bin:"
            + termuxHomeBin + "/.grok/bin:/root/.local/bin:/root/.opencode/bin:/root/.grok/bin:$PATH\""
            + " && hash -r 2>/dev/null || true"
            + " && export BROWSER=xdg-open 2>/dev/null || true"
            + " && { [ -f \"" + cert + "\" ] && export SSL_CERT_FILE=\"" + cert + "\" && export NODE_EXTRA_CA_CERTS=\"" + cert + "\" || true; }"
            + " && ";
    }

    /** {@code export} statements for the Anthropic-compatible endpoint, or "" when unset. */
    private String anthropicExports() {
        String authToken = getAnthropicAuthToken();
        String baseUrl = getAnthropicBaseUrl();
        String model = getAnthropicModel();

        StringBuilder exports = new StringBuilder();
        if (!authToken.isEmpty())
            exports.append("export ANTHROPIC_AUTH_TOKEN=\"").append(escapeForDoubleQuotes(authToken)).append("\"; ");
        if (!baseUrl.isEmpty())
            exports.append("export ANTHROPIC_BASE_URL=\"").append(escapeForDoubleQuotes(baseUrl)).append("\"; ");
        if (!model.isEmpty())
            exports.append("export ANTHROPIC_MODEL=\"").append(escapeForDoubleQuotes(model)).append("\"; ");
        return exports.toString();
    }

    /**
     * Build the terminal command to launch the given agent interactively.
     */
    public String buildLaunchCommand(CLIAgent agent, String prompt, String workspace) {
        if (agent.id.equals("custom")) {
            String customCmd = prefs.getString(KEY_CUSTOM_COMMAND, "");
            if (customCmd.isEmpty()) return "echo 'No custom command configured.'";
            return "cd " + shellEscape(workspace) + " && " + customCmd;
        }

        String cmd = agent.runCommandTemplate
                .replace("{prompt}", escapeForDoubleQuotes(prompt))
                .replace("{workspace}", workspace);

        cmd = shellPrelude() + cmd;

        // Prepend Anthropic vars if Claude Code is selected
        if (agent.id.equals("claude_code")) {
            cmd = anthropicExports() + cmd;
        }

        return "cd " + shellEscape(workspace) + " && " + cmd;
    }

    /**
     * The non-interactive command the Agent Chat GUI should run for this agent, with the prompt
     * already substituted and the environment prelude applied.
     */
    public String buildGuiPromptCommand(CLIAgent agent, String prompt, String workspace) {
        return buildGuiPromptCommand(agent, prompt, workspace, false);
    }

    /**
     * Build GUI prompt command, incorporating model and thinking level preferences,
     * and optionally continuing/resuming the active session if one exists.
     */
    public String buildGuiPromptCommand(CLIAgent agent, String prompt, String workspace, boolean continueSession) {
        String template = getGuiCommand(agent);
        if (template == null || template.trim().isEmpty()) return null;

        if (agent.id.equals("custom")) {
            String customCmd = prefs.getString(KEY_CUSTOM_COMMAND, "");
            if (customCmd.isEmpty()) return null;
            return "cd " + shellEscape(workspace) + " && " + shellPrelude() + customCmd;
        }

        String cmd = template
                .replace("{prompt}", escapeForDoubleQuotes(prompt))
                .replace("{workspace}", workspace);

        String selectedModel = getAgentModel(agent.id);
        String selectedThinking = getAgentThinkingLevel(agent.id);
        int thinkingTokens = getThinkingTokens(selectedThinking);

        // Safety net: never run antigravity without a print flag
        if (agent.id.equals("antigravity") && !hasNonInteractiveFlag(cmd)) {
            cmd = cmd.replaceFirst("\\bagy\\b(?!\\s+(-p|--print|--prompt|-i|models|agents|plugin|update|changelog|install|help))", "agy -p");
        }

        // Apply Model and Thinking parameters based on agent capabilities
        if (agent.id.equals("antigravity")) {
            if (selectedModel != null && !selectedModel.isEmpty() && !selectedModel.equals("default")) {
                cmd = cmd + " --model " + escapeForDoubleQuotes(selectedModel);
            }
            if (thinkingTokens == 0) {
                cmd = cmd + " --thinking off";
            } else if (thinkingTokens >= 16384) {
                cmd = cmd + " --thinking high";
            }
        } else if (agent.id.equals("codex")) {
            if (selectedModel != null && !selectedModel.isEmpty() && !selectedModel.equals("default")) {
                cmd = cmd + " --model " + escapeForDoubleQuotes(selectedModel);
            }
            String effort = thinkingTokens == 0 ? "low" : (thinkingTokens <= 8192 ? "medium" : "high");
            cmd = cmd + " --reasoning-effort " + effort;
        } else if (agent.id.equals("opencode") || agent.id.equals("grok")) {
            if (selectedModel != null && !selectedModel.isEmpty() && !selectedModel.equals("default")) {
                cmd = cmd + " --model " + escapeForDoubleQuotes(selectedModel);
            }
        }

        // If continuing an ongoing conversation, resume the previous session
        if (continueSession) {
            if (agent.id.equals("antigravity")) {
                if (!cmd.contains(" -c ") && !cmd.contains(" --continue")) {
                    String continueCmd = cmd.replaceFirst("\\bagy\\b", "agy -c");
                    cmd = "{ " + continueCmd + " || " + cmd + "; }";
                }
            } else if (agent.id.equals("claude_code")) {
                if (!cmd.contains(" --continue") && !cmd.contains(" -c ")) {
                    String continueCmd = cmd.replaceFirst("\\bclaude\\b", "claude --continue");
                    cmd = "{ " + continueCmd + " || " + cmd + "; }";
                }
            } else if (agent.id.equals("codex")) {
                if (!cmd.contains("resume")) {
                    String continueCmd = cmd.replaceFirst("\\bcodex exec\\b", "codex exec resume --last");
                    cmd = "{ " + continueCmd + " || " + cmd + "; }";
                }
            } else if (agent.id.equals("opencode")) {
                if (!cmd.contains(" -c ") && !cmd.contains(" -s ") && !cmd.contains(" --continue")) {
                    String continueCmd = cmd.replaceFirst("\\bopencode run\\b", "opencode run -c");
                    cmd = "{ " + continueCmd + " || " + cmd + "; }";
                }
            } else if (agent.id.equals("kiro")) {
                if (!cmd.contains(" -c ")) {
                    String continueCmd = cmd.replaceFirst("\\bkiro-cli chat\\b", "kiro-cli chat -c");
                    cmd = "{ " + continueCmd + " || " + cmd + "; }";
                }
            } else if (agent.id.equals("kilo")) {
                if (!cmd.contains(" --continue") && !cmd.contains(" -c ")) {
                    String continueCmd = cmd.replaceFirst("\\bkilo run\\b", "kilo run --continue");
                    cmd = "{ " + continueCmd + " || " + cmd + "; }";
                }
            } else if (agent.id.equals("grok")) {
                if (!cmd.contains(" -c ")) {
                    String continueCmd = cmd.replaceFirst("\\bgrok\\b", "grok -c");
                    cmd = "{ " + continueCmd + " || " + cmd + "; }";
                }
            }
        }

        cmd = shellPrelude() + cmd;

        // Model and Thinking exports for Claude Code
        if (agent.id.equals("claude_code")) {
            StringBuilder claudeEnv = new StringBuilder();
            if (selectedModel != null && !selectedModel.isEmpty() && !selectedModel.equals("default")) {
                claudeEnv.append("export ANTHROPIC_MODEL=\"").append(escapeForDoubleQuotes(selectedModel)).append("\"; ");
            }
            claudeEnv.append("export ANTHROPIC_THINKING_BUDGET=\"").append(thinkingTokens).append("\"; ");
            if (thinkingTokens > 0) {
                claudeEnv.append("export ANTHROPIC_EXTENDED_THINKING=true; ");
            }
            cmd = claudeEnv.toString() + anthropicExports() + cmd;
        }

        return "cd " + shellEscape(workspace) + " && " + cmd;
    }

    /** The GUI command template for an agent: the user's override if set, else the built-in default. */
    public String getGuiCommand(CLIAgent agent) {
        String override = prefs.getString(KEY_GUI_COMMAND_PREFIX + agent.id, null);
        if (override != null) {
            // Self-heal old broken antigravity templates saved without a print flag.
            // e.g. `... && agy "{prompt}"` fails with:
            //   Error: unexpected argument "Hello". Prompts are read only from
            //   -p/--print, -i/--prompt-interactive, or stdin...
            // Any antigravity override without a non-interactive flag is discarded
            // so the built-in `agy -p "{prompt}"` default takes over.
            if (agent.id.equals("antigravity") && !hasNonInteractiveFlag(override)) {
                prefs.edit().remove(KEY_GUI_COMMAND_PREFIX + agent.id).apply();
                return agent.guiPromptTemplate;
            }
            return override;
        }
        return agent.guiPromptTemplate;
    }

    /** True when the command drives agy non-interactively (-p/--print/--prompt/-i). */
    private static boolean hasNonInteractiveFlag(String command) {
        if (command == null) return false;
        // Look for agy followed by a print/interactive flag. Checking the flag next to
        // the binary avoids false positives from PATH entries or other words.
        return command.contains("agy -p")
                || command.contains("agy --print")
                || command.contains("agy --prompt")
                || command.contains("agy -i")
                || command.contains("agy --prompt-interactive");
    }

    /** True when the user has overridden the built-in GUI command for this agent. */
    public boolean hasCustomGuiCommand(String agentId) {
        return prefs.contains(KEY_GUI_COMMAND_PREFIX + agentId);
    }

    /** Store a GUI command override. Passing null/blank restores the built-in default. */
    public void setGuiCommand(String agentId, String command) {
        if (command == null || command.trim().isEmpty()) {
            prefs.edit().remove(KEY_GUI_COMMAND_PREFIX + agentId).apply();
        } else {
            prefs.edit().putString(KEY_GUI_COMMAND_PREFIX + agentId, command.trim()).apply();
        }
    }

    /**
     * Escape a string for use inside a bash double-quoted context, so a prompt containing quotes,
     * backticks or {@code $} cannot break out of the quoting.
     */
    static String escapeForDoubleQuotes(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '"' || c == '`' || c == '$') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    public void setAnthropicAuthToken(String token) {
        prefs.edit().putString("anthropic_auth_token", token).apply();
    }

    public String getAnthropicAuthToken() {
        return prefs.getString("anthropic_auth_token", "");
    }

    public void setAnthropicBaseUrl(String url) {
        prefs.edit().putString("anthropic_base_url", url).apply();
    }

    public String getAnthropicBaseUrl() {
        return prefs.getString("anthropic_base_url", "");
    }

    public void setAnthropicModel(String model) {
        prefs.edit().putString("anthropic_model", model).apply();
    }

    public String getAnthropicModel() {
        return prefs.getString("anthropic_model", "");
    }

    /**
     * Build the install command for an agent, including the CA-trust prelude so npm does not have
     * to fall back to {@code strict-ssl false}.
     */
    public String buildInstallCommand(CLIAgent agent) {
        if (agent.installCommand == null || agent.installCommand.isEmpty()) {
            return "echo 'No install command available for this agent.'";
        }
        return shellPrelude() + agent.installCommand;
    }

    /**
     * True when this agent needs an interactive sign-in before the chat GUI can use it.
     * Browser-OAuth agents (antigravity), API-key agents (claude/codex/copilot) and every
     * other bundled CLI all fall back to the terminal for their first-run auth.
     */
    public static boolean needsLogin(CLIAgent agent) {
        return agent != null && !agent.id.equals("custom");
    }

    /**
     * Interactive login command for the terminal. Unlike the GUI {@code -p} command this
     * starts the agent's full TUI so its OAuth / API-key flow can run, with the browser
     * shim prelude applied so the auth URL is at least printed when no browser exists
     * (Ubuntu proot has no Android browser integration).
     */
    public String buildLoginCommand(CLIAgent agent, String workspace) {
        if (agent.id.equals("custom")) {
            String customCmd = prefs.getString(KEY_CUSTOM_COMMAND, "");
            if (customCmd.isEmpty()) return "echo 'No custom command configured.'";
            return "cd " + shellEscape(workspace) + " && " + shellPrelude() + customCmd;
        }
        String interactive = agent.runCommandTemplate
                .replace("{prompt}", "")
                .replace("{workspace}", workspace);
        String cmd = shellPrelude() + interactive;
        if (agent.id.equals("claude_code")) {
            cmd = anthropicExports() + cmd;
        }
        if (agent.id.equals("antigravity")) {
            cmd = cmd + " ; echo ''; echo '[Mobile Code] If no browser opened, copy the URL above into your phone browser, sign in, and paste the code back here.'";
        }
        return "cd " + shellEscape(workspace) + " && " + cmd;
    }

    /**
     * One-shot workshop setup: Ubuntu rootfs (same steps as the terminal boot script) plus
     * every missing agent install back-to-back. This is the closest thing to "bundling"
     * without shipping a multi-GB APK: one tap, everything the terminal would have fetched
     * anyway, in the same environment the terminal uses.
     */
    public String buildSetupAllCommand(java.util.List<CLIAgent> missingAgents) {
        StringBuilder sb = new StringBuilder();
        sb.append(shellPrelude());
        sb.append("if ! command -v proot-distro &> /dev/null; then pkg update -y && pkg install -y proot-distro; fi; ");
        sb.append("if [ ! -d \"$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu\" ]; then proot-distro install ubuntu; fi; ");
        for (CLIAgent agent : missingAgents) {
            if (agent.installCommand != null && !agent.installCommand.isEmpty()) {
                sb.append("{ echo '=== Installing ").append(agent.name.replace("'", ""))
                  .append(" ==='; ").append(agent.installCommand).append("; } ; ");
            }
        }
        sb.append("echo '=== Setup finished. Restart the terminal once (exec $SHELL -l). ==='; ");
        sb.append("echo 'Installed:'; ");
        sb.append("for b in claude agy codex kiro-cli kilo opencode grok copilot; do command -v \"$b\" >/dev/null 2>&1 && echo \"  $b: $(command -v \"$b\")\" || echo \"  $b: MISSING\"; done");
        return sb.toString();
    }

    /**
     * Save the user's selected agent preference.
     */
    public void setSelectedAgent(String agentId) {
        prefs.edit().putString(KEY_SELECTED_AGENT, agentId).apply();
    }

    /**
     * Get the currently selected agent.
     */
    public CLIAgent getSelectedAgent() {
        String selectedId = prefs.getString(KEY_SELECTED_AGENT, "claude_code");
        for (CLIAgent agent : AGENTS) {
            if (agent.id.equals(selectedId)) return agent;
        }
        return AGENTS[0]; // default to Claude Code
    }

    /**
     * Save custom command for the custom agent.
     */
    public void setCustomCommand(String command) {
        prefs.edit().putString(KEY_CUSTOM_COMMAND, command).apply();
    }

    /**
     * Get the saved custom command.
     */
    public String getCustomCommand() {
        return prefs.getString(KEY_CUSTOM_COMMAND, "");
    }

    /**
     * Find an agent by its ID.
     */
    public static CLIAgent findAgent(String id) {
        for (CLIAgent agent : AGENTS) {
            if (agent.id.equals(id)) return agent;
        }
        return null;
    }

    private String shellEscape(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    /**
     * Agent status wrapper with install state.
     */
    public static class AgentStatus {
        public final CLIAgent agent;
        /** Binary is in the environment agents run in (workshop if present, else Termux). */
        public final boolean installed;
        /** Binary exists, but only in the OTHER environment — offer Reinstall. */
        public final boolean installedElsewhere;
        /** Detected binary path, if any (workshop path preferred). */
        public final String foundPath;

        public AgentStatus(CLIAgent agent, boolean installed) {
            this(agent, installed, false, null);
        }

        public AgentStatus(CLIAgent agent, boolean installed, boolean installedElsewhere,
                           String foundPath) {
            this.agent = agent;
            this.installed = installed;
            this.installedElsewhere = installedElsewhere;
            this.foundPath = foundPath;
        }
    }
}
