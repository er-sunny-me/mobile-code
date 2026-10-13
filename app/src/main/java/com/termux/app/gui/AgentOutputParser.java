package com.termux.app.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns raw CLI-agent output into {@link ChatMessage} entries that the GUI can render.
 *
 * <p>CLI agents write plain text (often with ANSI colour codes and progress spinner
 * noise) to stdout. This class:</p>
 * <ol>
 *   <li>strips ANSI escape sequences and carriage-return based progress rewrites,</li>
 *   <li>classifies the cleaned text as normal output vs. an error block,</li>
 *   <li>splits it into manageable chunks so one huge blob does not create a
 *       multi-megabyte TextView.</li>
 * </ol>
 *
 * <p>Classification reuses the project's existing {@code ErrorDetector} heuristics so
 * the GUI and the terminal's "Fix with AI" feature agree on what an error looks like.</p>
 */
public class AgentOutputParser {

    /** CSI / OSC / single-character escape sequences. */
    private static final Pattern ANSI = Pattern.compile(
            "\u001B\\[[0-9;?]*[ -/]*[@-~]" +      // CSI ... final byte
            "|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)" + // OSC ... BEL/ST
            "|\u001B[@-Z\\\\-_]");                 // two-byte escapes

    /** Progress-spinner characters some agents emit on a single line. */
    private static final Pattern SPINNER = Pattern.compile("[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏⣾⣽⣻⢿⡿⣟⣯⣷|/\\\\-]");

    /** Keep any single bubble under this many characters. */
    private static final int MAX_CHUNK = 4000;

    /** Remove ANSI escape sequences and normalise line endings. */
    public static String stripAnsi(String raw) {
        if (raw == null) return "";
        String out = ANSI.matcher(raw).replaceAll("");
        // Collapse CR-based progress rewrites ("downloading 10%\rdownloading 90%")
        out = out.replace("\r\n", "\n");
        StringBuilder sb = new StringBuilder(out.length());
        for (String line : out.split("\n", -1)) {
            int lastCr = line.lastIndexOf('\r');
            sb.append(lastCr >= 0 ? line.substring(lastCr + 1) : line).append('\n');
        }
        // Drop trailing blank run introduced by the loop above
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == '\n') sb.setLength(len - 1);
        return sb.toString();
    }

    /**
     * True when the text looks like a failure the user should see highlighted.
     * Delegates to the shared {@code ErrorDetector} patterns.
     */
    public static boolean looksLikeError(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        try {
            return com.termux.app.terminal.ErrorDetector.detect(text, null) != null;
        } catch (Throwable t) {
            // ErrorDetector must never be able to break the GUI.
            return false;
        }
    }

    /**
     * Split a raw output chunk into renderable messages.
     *
     * @param raw        raw bytes decoded from the agent process
     * @param agentName  display name shown on the bubble
     * @param icon       emoji shown on the bubble
     */
    public static List<ChatMessage> parse(String raw, String agentName, String icon) {
        List<ChatMessage> messages = new ArrayList<>();
        String clean = stripAnsi(raw);
        if (clean.trim().isEmpty()) return messages;

        // Remove spinner-only lines: they are pure noise in a chat view.
        List<String> kept = new ArrayList<>();
        for (String line : clean.split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && trimmed.length() <= 2 && SPINNER.matcher(trimmed).matches()) {
                continue;
            }
            kept.add(line);
        }
        String body = String.join("\n", kept);
        if (body.trim().isEmpty()) return messages;

        // Check for XML thinking tags: <thinking>...</thinking> or <think>...</think>
        int thinkStart = -1;
        String openTag = null;
        String closeTag = null;

        if (body.contains("<thinking>")) {
            thinkStart = body.indexOf("<thinking>");
            openTag = "<thinking>";
            closeTag = "</thinking>";
        } else if (body.contains("<think>")) {
            thinkStart = body.indexOf("<think>");
            openTag = "<think>";
            closeTag = "</think>";
        }

        if (thinkStart != -1) {
            String before = body.substring(0, thinkStart).trim();
            if (!before.isEmpty()) {
                addChunked(messages, before, agentName, icon, false);
            }

            int contentStart = thinkStart + openTag.length();
            int thinkEnd = body.indexOf(closeTag, contentStart);

            if (thinkEnd != -1) {
                String thinkingText = body.substring(contentStart, thinkEnd).trim();
                if (!thinkingText.isEmpty()) {
                    ChatMessage thinkMsg = new ChatMessage(ChatMessage.Type.THINKING, thinkingText, agentName, icon);
                    thinkMsg.streaming = false;
                    messages.add(thinkMsg);
                }
                String after = body.substring(thinkEnd + closeTag.length()).trim();
                if (!after.isEmpty()) {
                    addChunked(messages, after, agentName, icon, false);
                }
            } else {
                String thinkingText = body.substring(contentStart).trim();
                if (!thinkingText.isEmpty()) {
                    ChatMessage thinkMsg = new ChatMessage(ChatMessage.Type.THINKING, thinkingText, agentName, icon);
                    thinkMsg.streaming = true;
                    messages.add(thinkMsg);
                }
            }
            return messages;
        }

        addChunked(messages, body, agentName, icon, false);
        return messages;
    }

    private static void addChunked(List<ChatMessage> messages, String body, String agentName, String icon, boolean isThinking) {
        if (body == null || body.trim().isEmpty()) return;
        ChatMessage.Type type;
        if (isThinking) {
            type = ChatMessage.Type.THINKING;
        } else {
            type = looksLikeError(body) ? ChatMessage.Type.ERROR : ChatMessage.Type.AGENT;
        }

        for (int start = 0; start < body.length(); start += MAX_CHUNK) {
            int end = Math.min(body.length(), start + MAX_CHUNK);
            String chunk = body.substring(start, end);
            if (!chunk.trim().isEmpty()) {
                messages.add(new ChatMessage(type, chunk, agentName, icon));
            }
        }
    }
}
