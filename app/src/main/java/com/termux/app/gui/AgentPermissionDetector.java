package com.termux.app.gui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses streaming agent CLI output to detect interactive permission requests,
 * tool approvals, file changes, and command execution prompts (IDE / Cursor style).
 */
public class AgentPermissionDetector {

    public static class Request {
        public String action;       // "write_file", "edit_file", "command", "tool"
        public String target;       // "CLIAgentManager.java", "git commit", etc.
        public String diff;         // Buffered or associated diff
        public String promptText;   // Human-readable title
        public String icon;         // "📝", "⚡", "🛡️"
    }

    private static final Pattern FILE_PATTERN = Pattern.compile(
            "([a-zA-Z0-9_\\-\\./\\\\]+\\.(?:java|kt|xml|json|js|ts|tsx|jsx|py|cpp|c|h|hpp|sh|bash|html|css|scss|md|gradle|properties|yaml|yml|toml))",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "(?:command|bash|run|exec(?:ute)?)\\s*[:\\(]?\\s*([`'\"]?)([^`'\"]+)\\1",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Determines whether an incoming line is an interactive confirmation/permission prompt.
     */
    public static boolean isPermissionPrompt(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return false;
        String lower = trimmed.toLowerCase();

        boolean hasYn = lower.contains("(y/n)") || lower.contains("[y/n]")
                || lower.contains("(y/N)") || lower.contains("[y/n]")
                || lower.contains("(Y/n)") || lower.contains("[Y/n]")
                || lower.contains("(yes/no)") || lower.contains("[yes/no]")
                || lower.contains("[y/n/a]") || lower.contains("[Y/n/a]");

        boolean hasPromptKeywords = lower.contains("allow write_file")
                || lower.contains("allow edit_file")
                || lower.contains("allow replace_file")
                || lower.contains("allow command")
                || lower.contains("allow bash")
                || lower.contains("wants to edit")
                || lower.contains("wants to write")
                || lower.contains("wants to run")
                || lower.contains("wants to execute")
                || lower.contains("apply diff")
                || lower.contains("apply changes")
                || lower.contains("approve execution")
                || (lower.startsWith("allow ") && (lower.endsWith("?") || lower.endsWith(":")));

        return hasYn || hasPromptKeywords;
    }

    /**
     * Parse the prompt line into an IDE-style Request object.
     */
    public static Request parse(String line, String bufferedDiff, String agentName) {
        Request req = new Request();
        String trimmed = line.trim();
        String lower = trimmed.toLowerCase();

        req.icon = "📝";
        req.action = "edit_file";
        req.diff = (bufferedDiff != null && !bufferedDiff.trim().isEmpty()) ? bufferedDiff : null;

        // Detect if this is a command execution request
        if (lower.contains("command") || lower.contains("bash") || lower.contains("run ")
                || lower.contains("exec")) {
            req.action = "command";
            req.icon = "⚡";

            Matcher cmdMatcher = COMMAND_PATTERN.matcher(trimmed);
            if (cmdMatcher.find()) {
                req.target = cmdMatcher.group(2).trim();
            } else {
                req.target = "Shell Command";
            }
            req.promptText = (agentName != null ? agentName : "Agent") + " wants to run command: " + req.target;
            return req;
        }

        // Detect file target
        Matcher fileMatcher = FILE_PATTERN.matcher(trimmed);
        if (fileMatcher.find()) {
            req.target = fileMatcher.group(1).trim();
            // Simplify long absolute path to basename or short relative path
            if (req.target.contains("/")) {
                String[] parts = req.target.split("/");
                req.target = parts[parts.length - 1];
            } else if (req.target.contains("\\")) {
                String[] parts = req.target.split("\\\\");
                req.target = parts[parts.length - 1];
            }
        } else {
            req.target = "Project File";
        }

        if (lower.contains("write") || lower.contains("create")) {
            req.action = "write_file";
            req.promptText = (agentName != null ? agentName : "Agent") + " wants to write " + req.target;
        } else if (lower.contains("replace") || lower.contains("patch")) {
            req.action = "patch_file";
            req.promptText = (agentName != null ? agentName : "Agent") + " wants to patch " + req.target;
        } else {
            req.action = "edit_file";
            req.promptText = (agentName != null ? agentName : "Agent") + " wants to edit " + req.target;
        }

        return req;
    }
}
