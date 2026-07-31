package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI Agent Manager — Manages launching and monitoring CLI-based AI coding agents
 * (Claude Code, Antigravity, Aider, Codex, etc.) as background Termux sessions.
 *
 * Each agent runs directly in the terminal and the user can see output in both
 * a GUI status panel and the terminal tab itself.
 */
public class CLIAgentManager {

    private static final String PREFS_NAME = "cli_agent_prefs";
    private static final String KEY_SELECTED_AGENT = "selected_agent";
    private static final String KEY_CUSTOM_COMMAND = "custom_agent_command";

    private final Context context;
    private final SharedPreferences prefs;

    /**
     * Represents a CLI coding agent that can be installed and run in the terminal.
     */
    public static class CLIAgent {
        public final String id;
        public final String name;
        public final String description;
        public final String binaryName;        // e.g. "claude", "aider"
        public final String installCommand;     // pip/npm install command
        public final String runCommandTemplate; // template with {workspace} placeholder
        public final String iconEmoji;

        public CLIAgent(String id, String name, String description, String binaryName,
                        String installCommand, String runCommandTemplate, String iconEmoji) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.binaryName = binaryName;
            this.installCommand = installCommand;
            this.runCommandTemplate = runCommandTemplate;
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
            "🟠"
        ),
        new CLIAgent(
            "antigravity",
            "Antigravity CLI",
            "Google DeepMind's advanced agentic coding assistant.",
            "agy",
            "if ! command -v agy &> /dev/null; then if ! command -v curl &> /dev/null; then apt-get update && apt-get install -y curl || pkg install -y curl; fi; curl -fsSL https://antigravity.google/cli/install.sh | bash && export PATH=\"/root/.local/bin:$PATH\" && echo 'export PATH=\"/root/.local/bin:$PATH\"' >> ~/.bashrc; fi",
            "export PATH=\"/root/.local/bin:$PATH\" && agy \"{prompt}\"",
            "🔵"
        ),
        new CLIAgent(
            "aider",
            "Aider",
            "AI pair programming in your terminal. Works with any LLM backend.",
            "aider",
            "if ! command -v pip &> /dev/null; then apt-get update && apt-get install -y python3-pip || pkg install -y python3 python-pip; fi; pip install aider-chat",
            "aider --yes-always",
            "🟢"
        ),
        new CLIAgent(
            "codex",
            "Codex CLI",
            "OpenAI's lightweight coding agent for the terminal.",
            "codex",
            "if ! command -v npm &> /dev/null; then apt-get update && apt-get install -y nodejs npm || pkg install -y nodejs; fi; npm install -g @openai/codex",
            "codex \"{prompt}\"",
            "⚪"
        ),
        new CLIAgent(
            "gemini_cli",
            "Gemini CLI",
            "Google's Gemini-powered coding CLI agent.",
            "gemini",
            "if ! command -v npm &> /dev/null; then apt-get update && apt-get install -y nodejs npm || pkg install -y nodejs; fi; npm install -g @anthropic-ai/gemini-cli",
            "gemini \"{prompt}\"",
            "🟣"
        ),
        new CLIAgent(
            "custom",
            "Custom Agent",
            "Run any CLI agent command you want. Full control.",
            "",
            "",
            "{custom_command}",
            "⚙️"
        )
    };

    public CLIAgentManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Get all available agents with their install status.
     */
    public List<AgentStatus> getAgentStatuses() {
        List<AgentStatus> statuses = new ArrayList<>();
        for (CLIAgent agent : AGENTS) {
            boolean installed = agent.id.equals("custom") || isAgentInstalled(agent);
            statuses.add(new AgentStatus(agent, installed));
        }
        return statuses;
    }

    /**
     * Check if an agent binary exists in $PREFIX/bin/ or on the PATH.
     */
    public boolean isAgentInstalled(CLIAgent agent) {
        if (agent.binaryName == null || agent.binaryName.isEmpty()) return false;
        
        // Check $PREFIX/bin
        String prefixBin = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/" + agent.binaryName;
        if (new File(prefixBin).exists()) return true;

        // Check /usr/bin and /usr/local/bin (chroot environments)
        if (new File("/data/data/com.termux/files/usr/bin/" + agent.binaryName).exists()) return true;
        
        // Try `which` command
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"which", agent.binaryName});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();
            return line != null && !line.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Build the terminal command to launch the given agent.
     *
     * @param agent      The CLI agent to launch
     * @param prompt     User's prompt/instruction for the agent
     * @param workspace  The workspace directory path
     * @return Shell command string ready to execute in terminal
     */
    public String buildLaunchCommand(CLIAgent agent, String prompt, String workspace) {
        if (agent.id.equals("custom")) {
            String customCmd = prefs.getString(KEY_CUSTOM_COMMAND, "");
            if (customCmd.isEmpty()) return "echo 'No custom command configured.'";
            return "cd " + shellEscape(workspace) + " && " + customCmd;
        }

        String cmd = agent.runCommandTemplate
                .replace("{prompt}", prompt.replace("\"", "\\\""))
                .replace("{workspace}", workspace);

        if (agent.installCommand != null && !agent.installCommand.isEmpty()) {
            return "cd " + shellEscape(workspace) + " && " + agent.installCommand + " && " + cmd;
        }

        return "cd " + shellEscape(workspace) + " && " + cmd;
    }

    /**
     * Build the install command for an agent.
     */
    public String buildInstallCommand(CLIAgent agent) {
        if (agent.installCommand == null || agent.installCommand.isEmpty()) {
            return "echo 'No install command available for this agent.'";
        }
        return agent.installCommand;
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
        public final boolean installed;

        public AgentStatus(CLIAgent agent, boolean installed) {
            this.agent = agent;
            this.installed = installed;
        }
    }
}
