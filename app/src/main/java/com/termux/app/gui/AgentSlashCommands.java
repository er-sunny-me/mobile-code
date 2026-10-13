package com.termux.app.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Registry of slash (/) commands tailored to each CLI coding agent.
 */
public class AgentSlashCommands {

    public static class Command {
        public final String name;          // e.g. "/plan", "/compact", "/cost"
        public final String title;         // e.g. "Implementation Plan"
        public final String description;   // e.g. "Create detailed step-by-step plan before coding"
        public final String icon;          // e.g. "📋", "🗜️", "💰"
        public final boolean requiresArgs; // true if it prompts the user to add details (e.g. "/plan <feature>")
        public final boolean isLocalAction;// true if handled locally in the app (e.g. "/clear", "/terminal")

        public Command(String name, String title, String description, String icon, boolean requiresArgs, boolean isLocalAction) {
            this.name = name;
            this.title = title;
            this.description = description;
            this.icon = icon;
            this.requiresArgs = requiresArgs;
            this.isLocalAction = isLocalAction;
        }
    }

    /**
     * Returns the curated list of slash commands for a specific agent id.
     */
    public static List<Command> getCommandsForAgent(String agentId) {
        List<Command> list = new ArrayList<>();
        if (agentId == null) agentId = "";

        switch (agentId) {
            case "claude_code":
                list.add(new Command("/compact", "Compact Context", "Compress conversation history to save context tokens", "🗜️", false, false));
                list.add(new Command("/cost", "Session Cost", "Show token consumption and cost for current session", "💰", false, false));
                list.add(new Command("/doctor", "Health Check", "Diagnose environment and troubleshooting tools", "🩺", false, false));
                list.add(new Command("/init", "Initialize Project", "Analyze codebase structure and create CLAUDE.md guide", "🚀", false, false));
                list.add(new Command("/review", "Review Code", "Review local code changes and diffs for bugs", "🔍", true, false));
                list.add(new Command("/pr", "Create Pull Request", "Create a GitHub PR for current branch changes", "🔀", true, false));
                list.add(new Command("/bug", "Report Bug", "Report a bug or issue to Anthropic", "🐛", true, false));
                list.add(new Command("/help", "Claude Help", "Show Claude Code documentation and CLI options", "❓", false, false));
                list.add(new Command("/clear", "Clear Chat", "Clear chat transcript and start a fresh session", "🧹", false, true));
                list.add(new Command("/terminal", "Open Terminal", "Switch to full interactive terminal session", "⌨️", false, true));
                break;

            case "antigravity":
                list.add(new Command("/plan", "Implementation Plan", "Create step-by-step architectural plan before coding", "📋", true, false));
                list.add(new Command("/goal", "Autonomous Goal", "Run agent in autonomous long-running goal mode", "🎯", true, false));
                list.add(new Command("/grill-me", "Design Interview", "Interactive interview to resolve design decisions", "🔥", true, false));
                list.add(new Command("/schedule", "Schedule Task", "Schedule recurring jobs or background execution timers", "⏰", true, false));
                list.add(new Command("/learn", "Save Skill", "Extract workflow learnings and persist as reusable skills", "🧠", true, false));
                list.add(new Command("/help", "Antigravity Help", "Display Antigravity CLI capabilities and options", "❓", false, false));
                list.add(new Command("/clear", "Clear Chat", "Clear chat transcript and start a fresh session", "🧹", false, true));
                list.add(new Command("/terminal", "Open Terminal", "Switch to full interactive terminal session", "⌨️", false, true));
                break;

            case "opencode":
                list.add(new Command("/connect", "Connect Plugins", "Configure plugins, MCP servers, and tool integrations", "🔌", true, false));
                list.add(new Command("/status", "Workspace Status", "Display current workspace, git, and agent status", "📊", false, false));
                list.add(new Command("/help", "OpenCode Help", "View OpenCode commands and documentation", "❓", false, false));
                list.add(new Command("/clear", "Clear Chat", "Clear chat transcript and start a fresh session", "🧹", false, true));
                list.add(new Command("/terminal", "Open Terminal", "Switch to full interactive terminal session", "⌨️", false, true));
                break;

            case "kiro":
                list.add(new Command("/spec", "Generate Spec", "Create or update spec-driven requirements document", "📝", true, false));
                list.add(new Command("/tasks", "Break Down Tasks", "Decompose specification into actionable tasks", "✅", true, false));
                list.add(new Command("/help", "Kiro Help", "Display Kiro CLI options and help", "❓", false, false));
                list.add(new Command("/clear", "Clear Chat", "Clear chat transcript and start a fresh session", "🧹", false, true));
                list.add(new Command("/terminal", "Open Terminal", "Switch to full interactive terminal session", "⌨️", false, true));
                break;

            case "codex":
                list.add(new Command("/review", "Review Code", "Review recent workspace changes and diffs", "🔍", true, false));
                list.add(new Command("/explain", "Explain Code", "Explain the selected function, file, or concept", "💡", true, false));
                list.add(new Command("/help", "Codex Help", "Display OpenAI Codex CLI options and help", "❓", false, false));
                list.add(new Command("/clear", "Clear Chat", "Clear chat transcript and start a fresh session", "🧹", false, true));
                list.add(new Command("/terminal", "Open Terminal", "Switch to full interactive terminal session", "⌨️", false, true));
                break;

            case "grok":
                list.add(new Command("/think", "Deep Reasoning", "Prompt Grok with deep reasoning and analysis mode", "🧠", true, false));
                list.add(new Command("/help", "Grok Help", "Display Grok CLI command help", "❓", false, false));
                list.add(new Command("/clear", "Clear Chat", "Clear chat transcript and start a fresh session", "🧹", false, true));
                list.add(new Command("/terminal", "Open Terminal", "Switch to full interactive terminal session", "⌨️", false, true));
                break;

            case "github_copilot":
                list.add(new Command("/explain", "Explain Code", "Explain code structure, methods, or algorithms", "📖", true, false));
                list.add(new Command("/suggest", "Suggest Changes", "Suggest optimizations, tests, or bug fixes", "💡", true, false));
                list.add(new Command("/help", "Copilot Help", "Show GitHub Copilot CLI help and manual", "❓", false, false));
                list.add(new Command("/clear", "Clear Chat", "Clear chat transcript and start a fresh session", "🧹", false, true));
                list.add(new Command("/terminal", "Open Terminal", "Switch to full interactive terminal session", "⌨️", false, true));
                break;

            case "kilo":
            case "custom":
            default:
                list.add(new Command("/help", "Agent Help", "Show available CLI commands and help", "❓", false, false));
                list.add(new Command("/clear", "Clear Chat", "Clear chat transcript and start a fresh session", "🧹", false, true));
                list.add(new Command("/terminal", "Open Terminal", "Switch to full interactive terminal session", "⌨️", false, true));
                break;
        }

        // Universal commands available for all agents
        list.add(new Command("/model", "Switch Model", "Change active LLM model (e.g. Claude 3.7, Gemini 2.5, o3-mini)", "🤖", false, true));
        list.add(new Command("/thinking", "Thinking Level", "Configure reasoning effort & thinking budget (Off, Low, High, Max)", "🧠", false, true));

        return Collections.unmodifiableList(list);
    }

    /**
     * Filters the agent's commands by a query string (case-insensitive prefix/contains search).
     */
    public static List<Command> filterCommands(String agentId, String query) {
        List<Command> all = getCommandsForAgent(agentId);
        if (query == null || query.trim().isEmpty()) {
            return all;
        }
        String clean = query.trim().toLowerCase();
        if (clean.startsWith("/")) clean = clean.substring(1);

        List<Command> filtered = new ArrayList<>();
        for (Command c : all) {
            String nameClean = c.name.startsWith("/") ? c.name.substring(1) : c.name;
            if (nameClean.toLowerCase().contains(clean)
                    || c.title.toLowerCase().contains(clean)
                    || c.description.toLowerCase().contains(clean)) {
                filtered.add(c);
            }
        }
        return filtered;
    }
}
