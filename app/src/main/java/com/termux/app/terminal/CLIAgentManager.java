package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.app.RepoCompatInstaller;
import com.termux.shared.termux.TermuxConstants;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /** Hard cap for one probe process (install check or live-model query). */
    private static final long PROBE_TIMEOUT_MS = 30000;

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
            "claude -p \"{prompt}\" --dangerously-skip-permissions",
            "🟠"
        ),
        new CLIAgent(
            "antigravity",
            "Antigravity CLI",
            "Google DeepMind's advanced agentic coding assistant.",
            "agy",
            "if ! command -v agy &> /dev/null; then if ! command -v curl &> /dev/null; then apt-get update && apt-get install -y curl || pkg install -y curl; fi; curl -k -fsSL https://antigravity.google/cli/install.sh | bash; grep -qF '$HOME/.local/bin' ~/.bashrc 2>/dev/null || echo 'export PATH=\"$HOME/.local/bin:$PATH\"' >> ~/.bashrc; fi; export PATH=\"$HOME/.local/bin:" + TermuxConstants.TERMUX_HOME_DIR_PATH + "/.local/bin:/root/.local/bin:$PATH\"; hash -r 2>/dev/null || true; command -v agy && agy --version || echo 'NOTE: installed but not on PATH yet — restart the terminal (exec $SHELL -l) or run: export PATH=\"$HOME/.local/bin:$PATH\"'",
            "export PATH=\"$HOME/.local/bin:$PATH\" && agy",
            "export PATH=\"$HOME/.local/bin:$PATH\" && agy -p \"{prompt}\" --dangerously-skip-permissions",
            "🔵"
        ),
        new CLIAgent(
            "codex",
            "Codex CLI",
            "OpenAI's lightweight coding agent for the terminal.",
            "codex",
            "if ! command -v codex &> /dev/null; then if ! command -v npm &> /dev/null; then apt-get update && apt-get install -y nodejs npm || pkg install -y nodejs; fi; npm install -g @openai/codex; fi",
            "codex",
            "codex exec \"{prompt}\" --dangerously-bypass-approvals-and-sandbox",
            "⚪"
        ),
        new CLIAgent(
            "kiro",
            "Kiro CLI",
            "Agentic terminal IDE for spec-driven workflows.",
            "kiro-cli",
            "if ! command -v kiro-cli &> /dev/null; then if ! command -v curl &> /dev/null; then apt-get update && apt-get install -y curl || pkg install -y curl; fi; curl -k -fsSL https://cli.kiro.dev/install | bash; grep -qF '$HOME/.local/bin' ~/.bashrc 2>/dev/null || echo 'export PATH=\"$HOME/.local/bin:$PATH\"' >> ~/.bashrc; fi",
            "export PATH=\"$HOME/.local/bin:$PATH\" && kiro-cli",
            "export PATH=\"$HOME/.local/bin:$PATH\" && kiro-cli chat \"{prompt}\" --trust-all-tools",
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
            "if ! command -v opencode &> /dev/null && [ ! -x \"$HOME/.opencode/bin/opencode\" ]; then if ! command -v curl &> /dev/null; then apt-get update && apt-get install -y curl || pkg install -y curl; fi; curl -k -fsSL https://opencode.ai/install | bash; grep -qF '$HOME/.opencode/bin' ~/.bashrc 2>/dev/null || echo 'export PATH=\"$HOME/.opencode/bin:$PATH\"' >> ~/.bashrc; fi; export PATH=\"$HOME/.opencode/bin:" + TermuxConstants.TERMUX_HOME_DIR_PATH + "/.opencode/bin:/root/.opencode/bin:$PATH\"; hash -r 2>/dev/null || true; command -v opencode && opencode --version || { [ -x \"$HOME/.opencode/bin/opencode\" ] && \"$HOME/.opencode/bin/opencode\" --version || echo 'NOTE: installed but not on PATH yet — restart the terminal (exec $SHELL -l) or run: export PATH=\"$HOME/.opencode/bin:$PATH\"'; }",
            "export PATH=\"$HOME/.opencode/bin:$PATH\" && opencode",
            "export PATH=\"$HOME/.opencode/bin:$PATH\" && opencode run \"{prompt}\" --dangerously-skip-permissions",
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
            "npx -y @github/copilot -p \"{prompt}\" --allow-all-tools",
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
                + " \"" + TermuxConstants.TERMUX_HOME_DIR_PATH + "/.local/bin\""
                + " \"" + TermuxConstants.TERMUX_HOME_DIR_PATH + "/.opencode/bin\""
                + " \"" + TermuxConstants.TERMUX_HOME_DIR_PATH + "/.grok/bin\""
                + " \"/root/.local/bin\" \"/root/.opencode/bin\" \"/root/.grok/bin\"";
        return "export PATH=\"$HOME/.local/bin:$HOME/.opencode/bin:$HOME/.grok/bin"
                + ":" + TermuxConstants.TERMUX_HOME_DIR_PATH + "/.local/bin"
                + ":" + TermuxConstants.TERMUX_HOME_DIR_PATH + "/.opencode/bin"
                + ":" + TermuxConstants.TERMUX_HOME_DIR_PATH + "/.grok/bin"
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
            // Watchdog: a probe target that waits on the network or an auth
            // prompt would otherwise block the caller forever (the live-model
            // fetch used to hang silently and never deliver results). Kill the
            // process after the timeout; the read loop then ends at EOF and
            // whatever output was gathered is returned.
            final Process running = process;
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(PROBE_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    running.destroy();
                } catch (Exception ignored) {
                }
            }, "probe-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < 512 * 1024) {
                    output.append(line).append('\n');
                }
            }
            process.waitFor();
            watchdog.interrupt();
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
    // v2: the v1 cache can hold a 1-model list written by the old parser,
    // which dropped almost every OpenCode model (see parseModelsFromCliOutput).
    // Versioning the key makes those stale caches invisible; the next live
    // query repopulates with the full list.
    public static final String KEY_AGENT_LIVE_MODELS_PREFIX = "agent_live_models_v2_";

    public static final List<String> THINKING_LEVELS = Collections.unmodifiableList(
            Arrays.asList("Off", "Low (2k)", "Medium (8k)", "High (16k)", "Max (32k)")
    );

    public List<String> getAvailableThinkingLevels() {
        return THINKING_LEVELS;
    }

    /**
     * Thinking levels actually offered for one agent. Agents whose GUI command
     * has no thinking channel (see {@link #isThinkingSupported}) only ever get
     * "Off" — offering them a budget the app never applies made the chat chip
     * lie (it showed e.g. "High (16k)" for OpenCode while nothing was passed
     * to the CLI at all).
     */
    public static List<String> getAvailableThinkingLevels(String agentId) {
        if (!isThinkingSupported(agentId)) {
            return Collections.singletonList("Off");
        }
        return THINKING_LEVELS;
    }

    /**
     * True when a thinking / reasoning setting really reaches this agent:
     * Claude Code (thinking-budget env), Antigravity ({@code --thinking}),
     * Codex ({@code --reasoning-effort}) — and OpenCode, whose GUI mode is
     * the LINKED server session: the level is resolved to one of the
     * selected model's own server {@code variant} names and sent as the
     * prompt body's {@code variant} field (see {@link OpenCodeLink}); the
     * one-shot fallback appends {@code --variant} when a name resolves.
     * OpenCode was Off-only in the one-shot era because that command had no
     * thinking channel at all. Every other CLI still has no channel, so its
     * thinking level is always "Off" here.
     */
    public static boolean isThinkingSupported(String agentId) {
        return "claude_code".equals(agentId)
                || "antigravity".equals(agentId)
                || "codex".equals(agentId)
                || "opencode".equals(agentId);
    }

    /**
     * The agent's thinking level. Default is "Off" for every agent: the old
     * "High (16k)" fallback forced a 16k budget (Claude Code env) or a High
     * chip on agents where nothing was applied at all, which is exactly the
     * mismatch reported from a real device. "Off" is the only default where
     * the label and what is applied always agree.
     */
    public String getAgentThinkingLevel(String agentId) {
        if (!isThinkingSupported(agentId)) return "Off";
        return prefs.getString(KEY_AGENT_THINKING_PREFIX + agentId, "Off");
    }

    public void setAgentThinkingLevel(String agentId, String level) {
        if (!isThinkingSupported(agentId)) {
            // Never store a level the GUI command cannot apply.
            prefs.edit().remove(KEY_AGENT_THINKING_PREFIX + agentId).apply();
            return;
        }
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

    /**
     * What "Default" means for an agent's model: the CLI's OWN default.
     *
     * <p>This used to return invented per-agent model ids ("deepseek-r1" for
     * OpenCode, "claude-3-7-sonnet" for Claude Code, "o3-mini" for Codex…).
     * Nothing in this app configures a model for any CLI — the install
     * commands only install the binaries, no config file is ever written —
     * so those ids were stale guesses, and {@link #buildGuiPromptCommand}
     * then FORCED them onto every run via {@code --model} / the Anthropic
     * env. On a real device OpenCode's own model list contained only its
     * configured free model (deepseek-v4.1-flash-free) while the app showed
     * and passed "deepseek-r1" — a model the CLI did not even offer.
     *
     * <p>Returning the {@code "default"} sentinel makes label, selection and
     * behaviour agree: the picker shows "Default (CLI's own default)", and
     * the command builders pass no {@code --model} and set no model env, so
     * each CLI runs with whatever default it is itself configured with. A
     * model the user picks explicitly (picker / custom) is still honoured
     * exactly as before.
     */
    public static String defaultModelForAgent(String agentId) {
        return "default";
    }

    public static boolean isAlienModelForAgent(String agentId, String model) {
        if (agentId == null || model == null || model.isEmpty() || model.equalsIgnoreCase("default")) {
            return false;
        }
        String m = model.toLowerCase();
        switch (agentId) {
            case "claude_code":
            case "kiro":
                return !m.contains("claude");
            case "antigravity":
                return !m.contains("gemini");
            case "codex":
                return !m.contains("gpt") && !m.startsWith("o1") && !m.startsWith("o3") && !m.startsWith("o4");
            case "grok":
                return !m.contains("grok");
            case "github_copilot":
                return !m.contains("gpt") && !m.contains("claude") && !m.startsWith("o1");
            default:
                return false;
        }
    }

    public static List<String> getKnownModels(String agentId) {
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
                models.add("gemini-2.0-flash-thinking");
                models.add("gemini-1.5-pro");
                models.add("gemini-1.5-flash");
                break;
            case "codex":
                models.add("o3-mini");
                models.add("o1");
                models.add("o1-mini");
                models.add("gpt-4o");
                models.add("gpt-4o-mini");
                models.add("gpt-4.5-preview");
                break;
            case "kiro":
                models.add("claude-3-7-sonnet");
                models.add("claude-3-5-sonnet");
                models.add("claude-3-5-haiku");
                break;
            case "kilo":
                models.add("claude-3-7-sonnet");
                models.add("claude-3-5-sonnet");
                models.add("deepseek-r1");
                models.add("deepseek-v3");
                models.add("gpt-4o");
                models.add("gemini-2.0-flash");
                break;
            case "opencode":
                models.add("deepseek-r1");
                models.add("deepseek-v3");
                models.add("claude-3-7-sonnet");
                models.add("claude-3-5-sonnet");
                models.add("gpt-4o");
                models.add("gemini-2.0-flash");
                break;
            case "grok":
                models.add("grok-3");
                models.add("grok-3-mini");
                models.add("grok-2");
                models.add("grok-2-mini");
                models.add("grok-beta");
                break;
            case "github_copilot":
                models.add("gpt-4o");
                models.add("claude-3-5-sonnet");
                models.add("o1");
                models.add("o1-mini");
                break;
            default:
                models.add("default");
                break;
        }
        return models;
    }

    public static List<String> getAvailableModels(String agentId) {
        return getKnownModels(agentId);
    }

    public List<String> getAvailableModelsForAgent(String agentId) {
        List<String> cached = getCachedLiveModels(agentId);
        if (!cached.isEmpty()) {
            return cached;
        }
        return getKnownModels(agentId);
    }

    public interface LiveModelsCallback {
        void onModelsLoaded(List<String> models);
    }

    public static boolean looksLikeModelName(String agentId, String token) {
        if (token == null || token.length() < 3 || token.length() > 50) return false;
        String lower = token.toLowerCase();
        if (lower.endsWith(".tar") || lower.endsWith(".gz") || lower.endsWith(".bin")
                || lower.endsWith(".sh") || lower.endsWith(".js") || lower.endsWith(".json")
                || lower.startsWith("http") || lower.contains("version") || lower.contains("commit")
                || lower.contains("path") || lower.contains("usage") || lower.contains("help")
                || lower.contains("flag") || lower.contains("option")) {
            return false;
        }
        if (agentId == null) agentId = "";
        switch (agentId) {
            case "antigravity":
                return lower.startsWith("gemini-") || lower.startsWith("gemini/");
            case "claude_code":
            case "kiro":
                return lower.startsWith("claude-") || lower.startsWith("claude/");
            case "codex":
                return lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4")
                        || lower.startsWith("gpt-") || lower.startsWith("openai/");
            case "grok":
                return lower.startsWith("grok-") || lower.startsWith("grok/");
            case "github_copilot":
                return lower.startsWith("gpt-") || lower.startsWith("claude-") || lower.startsWith("o1");
            case "opencode":
            case "kilo":
                return lower.startsWith("deepseek") || lower.startsWith("claude")
                        || lower.startsWith("gpt") || lower.startsWith("gemini")
                        || lower.startsWith("o1") || lower.startsWith("o3");
            default:
                return lower.startsWith("gemini-") || lower.startsWith("claude-")
                        || lower.startsWith("gpt-") || lower.startsWith("deepseek-")
                        || lower.startsWith("o1") || lower.startsWith("o3");
        }
    }

    /**
     * Agents whose CLI enumerates models as full {@code provider/model} ids,
     * one per line ({@code opencode models}; Kilo is an OpenCode-family CLI
     * with the same listing format). For those agents the provider prefix is
     * PART of the model id: the CLI expects it back verbatim in
     * {@code --model provider/model}.
     */
    static boolean isProviderModelAgent(String agentId) {
        return "opencode".equals(agentId) || "kilo".equals(agentId);
    }

    /** First path segments that mark a slash token as a filesystem path
     * fragment (e.g. from --help text), never a provider name. */
    private static final Set<String> PATH_FIRST_SEGMENTS = new HashSet<>(Arrays.asList(
            "usr", "bin", "sbin", "etc", "var", "tmp", "home", "data", "storage",
            "mnt", "proc", "sys", "dev", "lib", "share", "local", "config",
            "root", "opt", "run", "android", "sdcard"));

    /**
     * Is this token a full {@code provider/model} id (e.g.
     * {@code opencode/big-pickle}, {@code anthropic/claude-sonnet-4-5})?
     * Deliberately stricter than "contains a slash": the provider must look
     * like a provider name (not a path segment) and the model part must
     * carry a '-' / '.' / digit — real model ids virtually always do, while
     * help-text path fragments like {@code config/opencode} do not.
     */
    public static boolean looksLikeProviderModelId(String token) {
        if (token == null || token.length() > 100) return false;
        int slash = token.indexOf('/');
        if (slash <= 0 || slash == token.length() - 1) return false;
        String provider = token.substring(0, slash).toLowerCase();
        String model = token.substring(slash + 1);
        if (PATH_FIRST_SEGMENTS.contains(provider)) return false;
        if (!provider.matches("[a-z0-9][a-z0-9._-]*")) return false;
        String lowerModel = model.toLowerCase();
        if (lowerModel.endsWith(".tar") || lowerModel.endsWith(".gz")
                || lowerModel.endsWith(".bin") || lowerModel.endsWith(".sh")
                || lowerModel.endsWith(".js") || lowerModel.endsWith(".json")) {
            return false;
        }
        if (!model.matches("[a-zA-Z0-9][a-zA-Z0-9._:+/-]*")) return false;
        return lowerModel.contains("-") || lowerModel.contains(".")
                || lowerModel.matches(".*\\d.*");
    }

    public static List<String> parseModelsFromCliOutput(String agentId, String output) {
        List<String> found = new ArrayList<>();
        if (output == null || output.trim().isEmpty()) return found;
        Set<String> seen = new LinkedHashSet<>();
        boolean providerModelAgent = isProviderModelAgent(agentId);

        // Line pass FIRST: CLIs that print one model per line (notably
        // `opencode models`, which prints full provider/model ids) keep
        // their exact order and their exact ids here. The old code ran the
        // regex pass first and, for slash tokens, kept only the part after
        // the last '/' if it started with deepseek/claude/gpt/gemini/o1/o3 —
        // so from OpenCode's full list only "deepseek-v4.1-flash-free"
        // survived and every other model (big-pickle, hy4-flash, glm-…,
        // mimo-…, nemotron-…) was silently dropped, and even the survivor
        // lost its "opencode/" prefix.
        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            trimmed = trimmed.replaceFirst("^[-*•\\d.]+\\s*", "").trim();
            int spaceIdx = trimmed.indexOf(' ');
            String candidate = (spaceIdx > 0) ? trimmed.substring(0, spaceIdx) : trimmed;
            if (providerModelAgent && looksLikeProviderModelId(candidate)) {
                if (!seen.contains(candidate)) {
                    seen.add(candidate);
                    found.add(candidate);
                }
                continue;
            }
            if (candidate.contains("/")) {
                String sub = candidate.substring(candidate.lastIndexOf('/') + 1);
                if (looksLikeModelName(agentId, sub)) candidate = sub;
            }
            if (looksLikeModelName(agentId, candidate) && !seen.contains(candidate)) {
                seen.add(candidate);
                found.add(candidate);
            }

            // Display-name listings (e.g. Antigravity prints "Gemini 2.5 Pro
            // (High)") never match the dashed-id checks above, so the live
            // fetch used to come back empty for those CLIs. Normalise the
            // leading words of the line into a dashed id and test that too.
            String dashed = dashedDisplayNameCandidate(trimmed);
            if (dashed != null && looksLikeModelName(agentId, dashed) && !seen.contains(dashed)) {
                seen.add(dashed);
                found.add(dashed);
            }
        }

        // Regex pass: catches ids embedded in prose / --help text that the
        // line pass missed. For provider/model agents a full slash token
        // that looks like a provider/model id is kept WHOLE (never stripped
        // to a duplicate of the part after the slash).
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\b([a-zA-Z0-9]+(?:[-./][a-zA-Z0-9]+)+)\\b");
        java.util.regex.Matcher matcher = pattern.matcher(output);
        while (matcher.find()) {
            String token = matcher.group(1).trim();
            if (token.contains("/")) {
                if (providerModelAgent && looksLikeProviderModelId(token)) {
                    if (!seen.contains(token)) {
                        seen.add(token);
                        found.add(token);
                    }
                    continue;
                }
                String sub = token.substring(token.lastIndexOf('/') + 1);
                if (looksLikeModelName(agentId, sub)) {
                    token = sub;
                }
            }
            if (looksLikeModelName(agentId, token) && !seen.contains(token)) {
                seen.add(token);
                found.add(token);
            }
        }
        return found;
    }

    /**
     * Turn a human display name at the start of a line ("Gemini 2.5 Pro (High)",
     * "Claude Sonnet 4.5") into the dashed id form ("gemini-2.5-pro"), or null
     * when the line does not start with a known model family word.
     */
    static String dashedDisplayNameCandidate(String line) {
        if (line == null) return null;
        String lower = line.toLowerCase();
        if (!lower.startsWith("gemini") && !lower.startsWith("claude")
                && !lower.startsWith("gpt") && !lower.startsWith("grok")
                && !lower.startsWith("deepseek")) {
            return null;
        }
        String[] tokens = lower.split("[^a-z0-9.]+");
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            if (used == 3) break;
            // Stop before parenthetical qualifiers like "(high)"/"(low)" —
            // the split above already removed the bracket, so stop on the
            // bare qualifier words instead.
            if (used > 0 && (token.equals("high") || token.equals("low")
                    || token.equals("medium") || token.equals("default"))) {
                break;
            }
            if (sb.length() > 0) sb.append('-');
            sb.append(token);
            used++;
        }
        String candidate = sb.toString();
        return candidate.length() >= 3 ? candidate : null;
    }

    public static String buildModelProbeCommand(CLIAgent agent) {
        if (agent == null) return null;
        switch (agent.id) {
            case "antigravity":
                return "export PATH=\"$HOME/.local/bin:$PATH\"; hash -r 2>/dev/null || true; { agy models 2>&1 || agy --help 2>&1; }";
            case "claude_code":
                return "{ claude models 2>&1 || claude --help 2>&1; }";
            case "codex":
                return "{ codex models 2>&1 || codex --help 2>&1; }";
            case "opencode":
                return "export PATH=\"$HOME/.opencode/bin:$PATH\"; hash -r 2>/dev/null || true; { opencode models 2>&1 || opencode --help 2>&1; }";
            case "kiro":
                return "export PATH=\"$HOME/.local/bin:$PATH\"; hash -r 2>/dev/null || true; { kiro-cli models 2>&1 || kiro-cli --help 2>&1; }";
            case "kilo":
                return "export PATH=\"$HOME/.local/bin:$PATH\"; hash -r 2>/dev/null || true; { kilo models 2>&1 || kilo --help 2>&1; }";
            case "grok":
                return "export PATH=\"$HOME/.grok/bin:$PATH\"; hash -r 2>/dev/null || true; { grok models 2>&1 || grok --help 2>&1; }";
            case "github_copilot":
                return "{ copilot models 2>&1 || copilot --help 2>&1; }";
            default:
                return null;
        }
    }

    public List<String> queryLiveModelsSync(CLIAgent agent) {
        if (agent == null) return Collections.emptyList();
        String cmd = buildModelProbeCommand(agent);
        if (cmd == null) return getKnownModels(agent.id);

        String rawOutput = "";
        if (isWorkshopAvailable()) {
            String installed = installedWorkshopDistro();
            String wrapped = wrapForWorkshop(cmd, installed != null ? installed : getWorkshopDistro(context));
            rawOutput = runProbeScript(wrapped);
        }
        if (rawOutput == null || rawOutput.trim().isEmpty()) {
            rawOutput = runProbeScript(cmd);
        }

        List<String> parsed = parseModelsFromCliOutput(agent.id, rawOutput);
        if (parsed.isEmpty()) {
            // The run-environment probe printed something we could not parse
            // (or nothing at all): also try the other environment and parse
            // both outputs together before giving up to the cache/known list.
            String nativeRaw = runProbeScript(cmd);
            if (nativeRaw != null && !nativeRaw.trim().isEmpty()) {
                parsed = parseModelsFromCliOutput(agent.id,
                        (rawOutput == null ? "" : rawOutput) + "\n" + nativeRaw);
            }
        }
        if (agent.id.equals("opencode")) {
            // `opencode models` only lists CONNECTED providers (on X's phone:
            // just the built-in opencode/ provider, 13 models), while the
            // TUI's "View all providers" list is the full models.dev
            // catalogue. Merge: connected ids first (live, in CLI order),
            // then every other provider/model the catalogue offers.
            List<String> merged = OpenCodeLink.fetchFullCatalogueSync(parsed);
            // Alongside the ids, cache each model's server variant names —
            // the thinking dialog/send path resolves levels against these
            // and must never guess a variant the model doesn't define.
            Map<String, List<String>> variants = OpenCodeLink.fetchVariantMapSync();
            if (!variants.isEmpty()) {
                saveModelVariants(variants);
            }
            if (merged.size() > parsed.size()) {
                saveLiveModelsCache(agent.id, merged);
                return merged;
            }
        }

        if (!parsed.isEmpty()) {
            saveLiveModelsCache(agent.id, parsed);
            return parsed;
        }

        List<String> cached = getCachedLiveModels(agent.id);
        if (!cached.isEmpty()) return cached;
        return getKnownModels(agent.id);
    }

    public void queryLiveModelsAsync(CLIAgent agent, LiveModelsCallback callback) {
        new Thread(() -> {
            List<String> models = queryLiveModelsSync(agent);
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onModelsLoaded(models));
            }
        }).start();
    }

    // ------------------------------------------------------------------
    // Per-model thinking variants (OpenCode linked mode)
    // ------------------------------------------------------------------

    /** Cache of modelId → comma-joined variant names ("" = known none). */
    public static final String KEY_MODEL_VARIANTS_PREFIX = "model_variants_v1_";

    /** Persist the variant map fetched alongside the OpenCode catalogue. */
    public void saveModelVariants(Map<String, List<String>> variantsByModel) {
        if (variantsByModel == null || variantsByModel.isEmpty()) return;
        SharedPreferences.Editor ed = prefs.edit();
        for (Map.Entry<String, List<String>> e : variantsByModel.entrySet()) {
            StringBuilder sb = new StringBuilder();
            List<String> names = e.getValue();
            if (names != null) {
                for (int i = 0; i < names.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(names.get(i));
                }
            }
            ed.putString(KEY_MODEL_VARIANTS_PREFIX + e.getKey(), sb.toString());
        }
        ed.apply();
    }

    /**
     * The variant names {@code modelId} (full {@code provider/model}) is
     * known to define, or null when nothing has been fetched for it yet.
     * An empty list means "fetched, and the model defines none" — for
     * big-pickle that is the real answer (models.dev gives it
     * {@code reasoning_options: []}, so the server builds it no effort
     * variants), and callers must not invent one.
     */
    public List<String> getModelVariants(String modelId) {
        if (modelId == null) return null;
        String saved = prefs.getString(KEY_MODEL_VARIANTS_PREFIX + modelId, null);
        if (saved == null) return null;
        List<String> out = new ArrayList<>();
        if (!saved.isEmpty()) {
            for (String n : saved.split(",")) {
                String t = n.trim();
                if (!t.isEmpty() && !out.contains(t)) out.add(t);
            }
        }
        return out;
    }

    /**
     * Resolve the stored thinking level for {@code agentId} + {@code modelId}
     * to the server variant name to send, or null when nothing may be sent
     * (Off, unknown variants, or the model doesn't define the mapped name).
     * Only OpenCode has variants; every other agent resolves to null here.
     */
    public String resolveThinkingVariant(String agentId, String modelId, String level) {
        if (!"opencode".equals(agentId)) return null;
        return OpenCodeLink.resolveVariant(level, getModelVariants(modelId));
    }

    /**
     * Reconcile OpenCode's config-route thinking override with the stored
     * level and the currently effective model. The level reaches the
     * server by exactly one route at a time:
     *
     * <ul>
     *   <li>level Off, or the level maps to a variant the model defines →
     *       the per-prompt variant route; the config override is cleared.</li>
     *   <li>otherwise (the model defines no matching variant — big-pickle
     *       defines none at all) → the level's reasoning effort is written
     *       into the serve config for that model
     *       ({@link OpenCodeLink#buildConfigContent}); a running app-owned
     *       server is stopped so it restarts once with the new config.</li>
     * </ul>
     *
     * Safe to call whenever the level or the model changes and before
     * every linked turn; it is a no-op when the state already matches.
     *
     * @return true when the override state changed (a server restart is
     *         pending on the next start)
     */
    public boolean applyOpenCodeThinkingConfig() {
        String selected = getAgentModel("opencode");
        String effectiveModel = (selected == null || selected.equals("default"))
                ? getCachedRealModel("opencode") : selected;
        String level = getAgentThinkingLevel("opencode");
        String variant = resolveThinkingVariant("opencode", effectiveModel, level);
        if (variant != null || level == null || level.startsWith("Off")) {
            return OpenCodeLink.applyConfigReasoningEffort(null, null);
        }
        String effort = OpenCodeLink.effortForLevel(level);
        if (effort == null || effectiveModel == null) {
            // Cannot aim the override at an unknown model yet; leave the
            // state alone — the next turn re-runs this once the real
            // model has been fetched.
            return false;
        }
        return OpenCodeLink.applyConfigReasoningEffort(effectiveModel, effort);
    }

    public void saveLiveModelsCache(String agentId, List<String> models) {
        if (models == null || models.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < models.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(models.get(i));
        }
        prefs.edit().putString(KEY_AGENT_LIVE_MODELS_PREFIX + agentId, sb.toString()).apply();
    }

    public List<String> getCachedLiveModels(String agentId) {
        String saved = prefs.getString(KEY_AGENT_LIVE_MODELS_PREFIX + agentId, null);
        if (saved == null || saved.trim().isEmpty()) return Collections.emptyList();
        String[] parts = saved.split(",");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty() && !list.contains(trimmed)) {
                list.add(trimmed);
            }
        }
        return list;
    }

    // ------------------------------------------------------------------
    // Real default model fetch
    // ------------------------------------------------------------------

    public static final String KEY_AGENT_REAL_MODEL_PREFIX = "agent_real_model_";

    public interface RealModelCallback {
        void onRealModelLoaded(String model);
    }

    /**
     * The model the CLI will actually use when the app passes no
     * {@code --model} — fetched from the CLI's OWN artefacts, never invented:
     *
     * <ul>
     *   <li><b>OpenCode</b> resolves its default exactly the way the CLI does:
     *       the {@code model} field of {@code ~/.config/opencode/opencode.json(c)}
     *       first, then the last-used model the TUI recorded in
     *       {@code ~/.local/state/opencode/model.json} ({@code recent[0]}),
     *       then — only when no provider is connected (no keys in
     *       {@code ~/.local/share/opencode/auth.json}) — OpenCode's built-in
     *       free default {@code opencode/big-pickle}, which is what its TUI
     *       shows out of the box ("Build · Big Pickle"). When a provider IS
     *       connected but neither file names a model, the default is that
     *       provider's own first model, which no file records — null.</li>
     *   <li><b>Claude Code</b>: the app's own AI-Keys model when set (it is
     *       exported as {@code ANTHROPIC_MODEL}, which beats config), else the
     *       {@code model} field of {@code ~/.claude/settings.json}. When
     *       neither exists the default depends on the user's Anthropic plan,
     *       which no local file records — null.</li>
     *   <li><b>Codex</b>: the top-level {@code model = "..."} of
     *       {@code ~/.codex/config.toml}. Unset → plan-dependent — null.</li>
     *   <li>Antigravity, Kiro, Kilo, Grok, Copilot and Custom expose no local
     *       artefact that records a current/default model (their
     *       {@code models} listings do not mark one), so they honestly return
     *       null and the UI keeps the plain "Default (CLI's own default)"
     *       label for them.</li>
     * </ul>
     *
     * <p>Homes are searched in run-environment order: the workshop rootfs
     * home ({@code <rootfs>/root}, where the terminal's proot login lands)
     * first, then the native Termux home. Pure file reads — no process is
     * spawned — but callers should still use
     * {@link #refreshRealModelAsync} off the UI thread.</p>
     */
    public String fetchRealDefaultModel(String agentId) {
        if (agentId == null) return null;
        switch (agentId) {
            case "claude_code": {
                String appModel = getAnthropicModel();
                if (appModel != null && !appModel.trim().isEmpty()) return appModel.trim();
                for (File home : agentHomeDirs()) {
                    String model = parseJsonStringField(
                            readSmallTextFile(new File(home, ".claude/settings.json")), "model");
                    if (model != null) return model;
                }
                return null;
            }
            case "opencode": {
                String configModel = null;
                String recentModel = null;
                boolean hasAuth = false;
                for (File home : agentHomeDirs()) {
                    if (configModel == null) {
                        for (String name : new String[]{"opencode.jsonc", "opencode.json", "config.json"}) {
                            configModel = parseJsonStringField(readSmallTextFile(
                                    new File(home, ".config/opencode/" + name)), "model");
                            if (configModel != null) break;
                        }
                    }
                    if (recentModel == null) {
                        recentModel = parseOpencodeRecentModel(readSmallTextFile(
                                new File(home, ".local/state/opencode/model.json")));
                    }
                    if (!hasAuth) {
                        String auth = readSmallTextFile(
                                new File(home, ".local/share/opencode/auth.json"));
                        hasAuth = auth != null && auth.contains("\"key\"");
                    }
                    if (configModel != null && recentModel != null && hasAuth) break;
                }
                return resolveOpencodeDefaultModel(configModel, recentModel, hasAuth);
            }
            case "codex": {
                for (File home : agentHomeDirs()) {
                    String model = parseCodexTomlModel(
                            readSmallTextFile(new File(home, ".codex/config.toml")));
                    if (model != null) return model;
                }
                return null;
            }
            default:
                return null;
        }
    }

    /** OpenCode's own default-resolution order, as a pure function for tests. */
    static String resolveOpencodeDefaultModel(String configModel, String recentModel,
                                              boolean hasConnectedProvider) {
        if (configModel != null && !configModel.isEmpty()) return configModel;
        if (recentModel != null && !recentModel.isEmpty()) return recentModel;
        // Keyless out-of-the-box default observed in the CLI's own TUI.
        if (!hasConnectedProvider) return "opencode/big-pickle";
        return null;
    }

    /** First {@code "field": "value"} string in a JSON/JSONC text, or null. */
    static String parseJsonStringField(String content, String field) {
        if (content == null || field == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(content);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The first entry of the {@code recent[]} array in OpenCode's
     * {@code ~/.local/state/opencode/model.json}, as {@code provider/model}.
     * Entries are {@code {"providerID": "...", "modelID": "..."}} objects;
     * {@code recent[0]} is the model the TUI last used and will start with.
     */
    static String parseOpencodeRecentModel(String content) {
        if (content == null) return null;
        int idx = content.indexOf("\"recent\"");
        if (idx < 0) return null;
        String scope = content.substring(idx);
        String provider = parseJsonStringField(scope, "providerID");
        String model = parseJsonStringField(scope, "modelID");
        if (model == null || model.isEmpty()) return null;
        return (provider != null && !provider.isEmpty()) ? provider + "/" + model : model;
    }

    /** The top-level {@code model = "..."} of a Codex config.toml, or null. */
    static String parseCodexTomlModel(String content) {
        if (content == null) return null;
        java.util.regex.Pattern pattern =
                java.util.regex.Pattern.compile("^model\\s*=\\s*\"([^\"]+)\"");
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("[")) break; // first section: top-level keys are above it
            java.util.regex.Matcher m = pattern.matcher(line);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /** Homes an agent CLI may keep its config in, run environment first. */
    private List<File> agentHomeDirs() {
        List<File> homes = new ArrayList<>();
        if (isWorkshopAvailable()) {
            for (String distro : WORKSHOP_DISTRO_CHOICES) {
                for (String rootfs : new String[]{containerRootfsPath(distro), legacyRootfsPath(distro)}) {
                    File root = new File(rootfs);
                    if (root.isDirectory()) homes.add(new File(root, "root"));
                }
            }
        }
        homes.add(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
        return homes;
    }

    /** Read a small text file fully (config/cmdline sized), or null. */
    static String readSmallTextFile(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            try {
                byte[] buf = new byte[256 * 1024];
                int n = in.read(buf);
                if (n <= 0) return null;
                return new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** The last fetched real default model for an agent, or null if unknown. */
    public String getCachedRealModel(String agentId) {
        if (agentId == null) return null;
        String value = prefs.getString(KEY_AGENT_REAL_MODEL_PREFIX + agentId, null);
        return (value == null || value.trim().isEmpty()) ? null : value;
    }

    /** Picker label for the Default row: names the real model once fetched. */
    public String getDefaultModelLabel(String agentId) {
        String real = getCachedRealModel(agentId);
        return real != null ? "Default (" + real + ")" : "Default (CLI's own default)";
    }

    /**
     * Fetch the agent's real default model on a background thread (same
     * pattern as {@link #queryLiveModelsAsync}), cache it for the chip and
     * picker, and deliver it on the main thread. A null result clears any
     * stale cache so the UI falls back to the honest plain label.
     */
    public void refreshRealModelAsync(CLIAgent agent, RealModelCallback callback) {
        if (agent == null) {
            if (callback != null) callback.onRealModelLoaded(null);
            return;
        }
        new Thread(() -> {
            String model = fetchRealDefaultModel(agent.id);
            if (model != null) {
                prefs.edit().putString(KEY_AGENT_REAL_MODEL_PREFIX + agent.id, model).apply();
            } else {
                prefs.edit().remove(KEY_AGENT_REAL_MODEL_PREFIX + agent.id).apply();
            }
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onRealModelLoaded(model));
            }
        }).start();
    }

    // ------------------------------------------------------------------
    // Running-agent check for terminal sessions
    // ------------------------------------------------------------------

    /** npm package paths that betray a node-hosted agent in a process cmdline. */
    static final String[][] AGENT_CMDLINE_MARKERS = {
            {"claude_code", "@anthropic-ai/claude-code"},
            {"codex", "@openai/codex"},
            {"kilo", "@kilocode/cli"},
            {"github_copilot", "@github/copilot"},
    };

    /**
     * Which agent (if any) a process cmdline belongs to. Only argv[0]'s
     * basename is matched against agent binary names — matching every token
     * would false-positive on things like {@code grep opencode file} — plus
     * npm package markers anywhere in the cmdline for node-hosted CLIs whose
     * argv[0] is just {@code node} / {@code npx}.
     */
    static String matchAgentInCmdline(String cmdline) {
        if (cmdline == null || cmdline.isEmpty()) return null;
        String[] argv = cmdline.split("\u0000");
        if (argv.length == 0) return null;
        String argv0 = argv[0].trim();
        String base = argv0.substring(argv0.lastIndexOf('/') + 1);
        for (CLIAgent agent : AGENTS) {
            if (agent.binaryName != null && !agent.binaryName.isEmpty()
                    && base.equals(agent.binaryName)) {
                return agent.id;
            }
        }
        for (String[] marker : AGENT_CMDLINE_MARKERS) {
            if (cmdline.contains(marker[1])) return marker[0];
        }
        return null;
    }

    /** All descendants of {@code rootPid} in a parent→children map (BFS, cycle-safe). */
    static java.util.Set<Integer> collectDescendantPids(
            int rootPid, java.util.Map<Integer, java.util.List<Integer>> childrenByParent) {
        java.util.Set<Integer> out = new java.util.LinkedHashSet<>();
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        queue.add(rootPid);
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            java.util.List<Integer> kids = childrenByParent.get(cur);
            if (kids == null) continue;
            for (int kid : kids) {
                if (out.add(kid)) queue.add(kid);
            }
        }
        return out;
    }

    /**
     * The agent currently running inside the terminal session whose shell
     * has pid {@code sessionPid}, or null when none is. This reads LIVE
     * /proc state (the session shell's process tree: host bash → proot →
     * rootfs bash → agent), so it cannot go stale the way a persisted flag
     * would — when the agent exits, its process is gone and this returns
     * null again, and a reopened terminal starts it normally.
     */
    public static String findRunningAgentIdForSessionPid(int sessionPid) {
        if (sessionPid <= 0) return null;
        try {
            java.util.Map<Integer, java.util.List<Integer>> children = readProcChildrenMap(sessionPid);
            for (int pid : collectDescendantPids(sessionPid, children)) {
                String agentId = matchAgentInCmdline(
                        readSmallTextFile(new File("/proc/" + pid + "/cmdline")));
                if (agentId != null) return agentId;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * True when {@code cmdline} is a STANDALONE OpenCode TUI: an opencode
     * process that is neither attached to the shared server ({@code
     * attach}), nor the server itself ({@code serve}), nor a one-shot
     * ({@code run}). A standalone TUI keeps its own session and can never
     * join the GUI's shared conversation — callers use this to tell the
     * user so, instead of letting them assume a sync that isn't there.
     * Pure and unit-testable.
     */
    public static boolean isStandaloneOpenCodeCmdline(String cmdline) {
        if (cmdline == null) return false;
        if (!"opencode".equals(matchAgentInCmdline(cmdline))) return false;
        for (String token : cmdline.split("\u0000")) {
            String t = token.trim();
            if (t.equals("attach") || t.equals("serve") || t.equals("run")) return false;
        }
        return true;
    }

    /**
     * True when a standalone OpenCode TUI (see
     * {@link #isStandaloneOpenCodeCmdline}) is alive in the process tree
     * of terminal session {@code sessionPid}.
     */
    public static boolean isStandaloneOpenCodeTuiInSession(int sessionPid) {
        if (sessionPid <= 0) return false;
        try {
            java.util.Map<Integer, java.util.List<Integer>> children = readProcChildrenMap(sessionPid);
            for (int pid : collectDescendantPids(sessionPid, children)) {
                String cmdline = readSmallTextFile(new File("/proc/" + pid + "/cmdline"));
                if (cmdline != null && isStandaloneOpenCodeCmdline(cmdline)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Parent→children map for the tree under {@code rootPid}. Fast path:
     * {@code /proc/<pid>/task/<pid>/children} walked recursively (only the
     * session's own tree is touched). Fallback when no children file is
     * readable (old kernels): a full /proc ppid scan.
     */
    private static java.util.Map<Integer, java.util.List<Integer>> readProcChildrenMap(int rootPid) {
        java.util.Map<Integer, java.util.List<Integer>> map = new java.util.HashMap<>();
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        queue.add(rootPid);
        boolean anyChildrenFile = false;
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            String content = readSmallTextFile(new File("/proc/" + cur + "/task/" + cur + "/children"));
            if (content == null) continue;
            anyChildrenFile = true;
            java.util.List<Integer> kids = new java.util.ArrayList<>();
            for (String token : content.trim().split("\\s+")) {
                if (token.isEmpty()) continue;
                try {
                    int kid = Integer.parseInt(token);
                    kids.add(kid);
                    queue.add(kid);
                } catch (NumberFormatException ignored) {
                }
            }
            if (!kids.isEmpty()) map.put(cur, kids);
        }
        if (anyChildrenFile) return map;

        File[] procEntries = new File("/proc").listFiles();
        if (procEntries == null) return map;
        for (File entry : procEntries) {
            int pid;
            try {
                pid = Integer.parseInt(entry.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            String stat = readSmallTextFile(new File(entry, "stat"));
            if (stat == null) continue;
            int close = stat.lastIndexOf(')');
            if (close < 0) continue;
            String[] fields = stat.substring(close + 1).trim().split("\\s+");
            if (fields.length < 2) continue;
            try {
                int ppid = Integer.parseInt(fields[1]);
                java.util.List<Integer> kids = map.get(ppid);
                if (kids == null) {
                    kids = new java.util.ArrayList<>();
                    map.put(ppid, kids);
                }
                kids.add(pid);
            } catch (NumberFormatException ignored) {
            }
        }
        return map;
    }

    public String getAgentModel(String agentId) {
        String defaultModel = defaultModelForAgent(agentId);
        String saved = prefs.getString(KEY_AGENT_MODEL_PREFIX + agentId, null);
        if (saved == null || saved.isEmpty() || saved.equalsIgnoreCase("default")) {
            return defaultModel;
        }
        if (isAlienModelForAgent(agentId, saved)) {
            setAgentModel(agentId, null);
            return defaultModel;
        }
        return saved;
    }

    public void setAgentModel(String agentId, String model) {
        if (model == null || model.isEmpty() || model.equalsIgnoreCase("default")) {
            prefs.edit().remove(KEY_AGENT_MODEL_PREFIX + agentId).apply();
        } else {
            prefs.edit().putString(KEY_AGENT_MODEL_PREFIX + agentId, model).apply();
        }
    }

    private static final String KEY_GUI_SESSION_ACTIVE_PREFIX = "gui_session_active_";

    /**
     * Whether the GUI chat has a resumable CLI-side session for this agent.
     * Persisted (not just an activity field) so an activity recreation —
     * rotation, process restart, coming back from the terminal — does not
     * silently drop the "continue" state and restart the conversation.
     */
    public boolean isGuiSessionActive(String agentId) {
        return agentId != null
                && prefs.getBoolean(KEY_GUI_SESSION_ACTIVE_PREFIX + agentId, false);
    }

    public void setGuiSessionActive(String agentId, boolean active) {
        if (agentId == null) return;
        prefs.edit().putBoolean(KEY_GUI_SESSION_ACTIVE_PREFIX + agentId, active).apply();
    }

    /**
     * Environment prelude prepended to every agent command.
     */
    public static String shellPrelude() {
        String cert = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/tls/cert.pem";
        String termuxHomeBin = TermuxConstants.TERMUX_HOME_DIR_PATH;
        String termuxOpen = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/termux-open-url";
        String shimBody = "#!/bin/bash\n"
                + "url=\"$1\"\n"
                + "for opener in \"" + termuxOpen + "\" \"" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/termux-open-url\" termux-open-url termux-open; do\n"
                + "  if command -v \"$opener\" >/dev/null 2>&1; then \"$opener\" \"$url\" >/dev/null 2>&1 & exit 0; fi\n"
                + "done\n"
                + "echo \"Open this URL on your phone browser: $url\"\n"
                + "exit 0\n";
        // TERMUX_PKG_NO_MIRROR_SELECT (exported below): keep pkg on the bootstrap
        // default Cloudflare mirror instead of its test-everything weighted
        // lottery, which picked a slow, stale Chinese mirror on a real device.
        // Related: proot-distro installs must name ncurses + ncurses-utils in the
        // same transaction or an old ncurses-utils pin breaks apt (see wizard).
        return "mkdir -p ~/.local/bin"
            + " && { [ -f ~/.local/bin/xdg-open ] || { printf '%b' '" + shimBody.replace("'", "'\\''").replace("\n", "\\n") + "' > ~/.local/bin/xdg-open && chmod +x ~/.local/bin/xdg-open; }; }"
            + " && export PATH=\"$HOME/.local/bin:$HOME/.opencode/bin:$HOME/.grok/bin:"
            + termuxHomeBin + "/.local/bin:" + termuxHomeBin + "/.opencode/bin:"
            + termuxHomeBin + "/.grok/bin:/root/.local/bin:/root/.opencode/bin:/root/.grok/bin:$PATH\""
            + " && hash -r 2>/dev/null || true"
            + " && export TERMUX_PKG_NO_MIRROR_SELECT=1"
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
     * THE one command that starts OpenCode in a terminal: shell prelude +
     * ensure-the-shared-server + attach to it with the resolver's current
     * session id. Every terminal entry point for OpenCode —
     * {@link #buildLaunchCommand} (spinner, chat terminal button) and
     * {@link #buildLoginCommand} (dashboard/chat Login) — must go through
     * here; any path that falls back to the raw run template starts a
     * standalone TUI in its own session and silently breaks GUI↔CLI sync
     * (the dashboard/chat Login path did exactly that until this was
     * factored out).
     */
    private String openCodeSharedTerminalCommand() {
        return shellPrelude()
            + OpenCodeLink.buildTerminalCommand(OpenCodeLink.getSessionId());
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

        // Linked mode: the terminal must attach to the SHARED OpenCode
        // server instead of starting a separate TUI instance — one live
        // session, shared by GUI and CLI, same conversation on both sides,
        // in BOTH open orders:
        //  - GUI first: the server is already up; the command's ensure
        //    step is a no-op probe and the attach names the GUI's session
        //    (--session <id>, read fresh here at terminal-open time — a
        //    bare attach lands the TUI in a NEW empty session, which a
        //    phone test caught).
        //  - Terminal first: no server exists yet, so the command starts
        //    it (same serve invocation + config the app itself uses)
        //    before attaching; when the GUI later sends, it adopts the
        //    session this TUI created (see OpenCodeLink session adoption).
        // Without the workshop there is no shared environment to serve
        // from, so the plain template command stays as the fallback.
        if (agent.id.equals("opencode") && isWorkshopAvailable()) {
            cmd = openCodeSharedTerminalCommand();
        }

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
            // Every level maps to the --thinking word the chip names (agy's
            // own model listings use the same low/medium/high words — see
            // dashedDisplayNameCandidate). Low/Medium used to append nothing,
            // so the chip promised a budget the CLI never received.
            if (thinkingTokens == 0) {
                cmd = cmd + " --thinking off";
            } else if (thinkingTokens <= 2048) {
                cmd = cmd + " --thinking low";
            } else if (thinkingTokens <= 8192) {
                cmd = cmd + " --thinking medium";
            } else {
                cmd = cmd + " --thinking high";
            }
        } else if (agent.id.equals("codex")) {
            if (selectedModel != null && !selectedModel.isEmpty() && !selectedModel.equals("default")) {
                cmd = cmd + " --model " + escapeForDoubleQuotes(selectedModel);
            }
            // "Off" must mean off: no effort flag at all (the CLI then uses
            // its own default). It used to append --reasoning-effort low, and
            // Low (2k) used to map to "medium" — label and applied disagreed.
            if (thinkingTokens > 0) {
                String effort = thinkingTokens <= 2048 ? "low" : (thinkingTokens <= 8192 ? "medium" : "high");
                cmd = cmd + " --reasoning-effort " + effort;
            }
        } else if (agent.id.equals("opencode") || agent.id.equals("grok")
                || agent.id.equals("kiro") || agent.id.equals("kilo") || agent.id.equals("github_copilot")) {
            if (selectedModel != null && !selectedModel.isEmpty() && !selectedModel.equals("default")) {
                cmd = cmd + " --model " + escapeForDoubleQuotes(selectedModel);
            }
            if (agent.id.equals("opencode")) {
                // One-shot fallback for the linked thinking level: `opencode
                // run` accepts --variant with the same per-model names the
                // server takes. Append it ONLY when the level resolves
                // against the model's fetched variant list — an unknown
                // variant name is a model-resolution error in the CLI.
                String variantModel = (selectedModel != null && !selectedModel.equals("default"))
                        ? selectedModel : getCachedRealModel(agent.id);
                String variant = resolveThinkingVariant(agent.id, variantModel, selectedThinking);
                if (variant != null) {
                    cmd = cmd + " --variant " + variant;
                }
            }
        }

        // Permission fix: the chat GUI cannot answer interactive tool-approval
        // prompts (one-shot -p processes never read stdin for approvals, so the
        // agent used to get every file edit / command denied). Make sure the
        // agent's auto-approve flag is present even when the user has an older
        // saved GUI-command override that predates the flag.
        cmd = ensureAutoApproveFlag(agent.id, cmd);

        // If continuing an ongoing conversation, resume the previous session
        if (continueSession) {
            if (agent.id.equals("antigravity")) {
                if (!cmd.contains(" -c ") && !cmd.contains(" --continue")) {
                    // Try both spellings: if this agy build rejects --continue the
                    // -c form still resumes; only when both fail do we start fresh.
                    // (With only one form, a rejected flag silently restarted the
                    // conversation on every message.)
                    String continueLong = cmd.replaceFirst("\\bagy\\b", "agy --continue");
                    String continueShort = cmd.replaceFirst("\\bagy\\b", "agy -c");
                    cmd = "{ { " + continueLong + " || " + continueShort + "; } || " + cmd + "; }";
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

    /**
     * Append the agent's auto-approve / skip-permissions flag when the command
     * does not already carry one. GUI chat runs are one-shot: there is no
     * terminal for the CLI to prompt in, so without this flag every tool call
     * that needs approval is silently denied and the agent cannot edit files
     * or run commands from the chat view.
     */
    static String ensureAutoApproveFlag(String agentId, String cmd) {
        if (agentId == null || cmd == null) return cmd;
        switch (agentId) {
            case "claude_code":
            case "antigravity":
            case "opencode":
                if (!cmd.contains("skip-permissions") && !cmd.contains("permission-mode")) {
                    return cmd + " --dangerously-skip-permissions";
                }
                return cmd;
            case "codex":
                if (!cmd.contains("bypass-approvals") && !cmd.contains("ask-for-approval")) {
                    return cmd + " --dangerously-bypass-approvals-and-sandbox";
                }
                return cmd;
            case "kiro":
                if (!cmd.contains("trust-all-tools") && !cmd.contains("trust-tools")) {
                    return cmd + " --trust-all-tools";
                }
                return cmd;
            case "github_copilot":
                if (!cmd.contains("allow-all-tools") && !cmd.contains("allow-tool")) {
                    return cmd + " --allow-all-tools";
                }
                return cmd;
            default:
                return cmd;
        }
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
        // OpenCode login goes through the SHARED terminal command too:
        // signing in from a standalone TUI session leaves the GUI chat in
        // a different session than the one the user just used — the exact
        // "GUI and CLI are not synced" complaint. Inside the attached
        // TUI, /connect and friends work the same.
        if (agent.id.equals("opencode") && isWorkshopAvailable()) {
            cmd = openCodeSharedTerminalCommand();
        }
        if (agent.id.equals("claude_code")) {
            cmd = anthropicExports() + cmd;
        }
        if (agent.id.equals("antigravity")) {
            cmd = cmd + " ; echo ''; echo '[Xyz.IDE] If no browser opened, copy the URL above into your phone browser, sign in, and paste the code back here.'";
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
        // The command below pkg-installs proot-distro on the host; repo .debs
        // need the translation layer first (see RepoCompatInstaller).
        RepoCompatInstaller.ensureInstalled();
        StringBuilder sb = new StringBuilder();
        sb.append(shellPrelude());
        sb.append("if ! command -v proot-distro &> /dev/null; then pkg update -y && pkg install -y ncurses ncurses-utils proot-distro; fi; ");
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
