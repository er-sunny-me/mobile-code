package com.termux.app.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * Pure-Java tests for the agent output parser. No Android framework involved, so these run as
 * fast JVM tests.
 */
public class AgentOutputParserTest {

    @Test
    public void stripsAnsiColourCodes() {
        assertEquals("Error: boom", AgentOutputParser.stripAnsi("\u001B[31mError\u001B[0m: boom"));
    }

    @Test
    public void collapsesCarriageReturnProgressRewrites() {
        assertEquals("Done", AgentOutputParser.stripAnsi("Downloading 10%\rDownloading 90%\rDone"));
    }

    @Test
    public void keepsNormalNewlines() {
        assertEquals("line1\nline2\n", AgentOutputParser.stripAnsi("line1\nline2\n"));
    }

    @Test
    public void detectsPythonTracebackAsError() {
        String raw = "Traceback (most recent call last):\n  File \"a.py\", line 3\nValueError: bad";
        assertTrue(AgentOutputParser.looksLikeError(raw));
    }

    @Test
    public void detectsMissingCommandAsError() {
        assertTrue(AgentOutputParser.looksLikeError("bash: claudee: command not found"));
    }

    @Test
    public void detectsUnexpectedArgumentAsError() {
        assertTrue(AgentOutputParser.looksLikeError("Error: unexpected argument \"Hello\". Prompts are read only from -p/--print"));
    }

    @Test
    public void plainTextIsNotAnError() {
        assertFalse(AgentOutputParser.looksLikeError("Refactored the login handler; 3 files changed."));
        assertFalse(AgentOutputParser.looksLikeError(""));
        assertFalse(AgentOutputParser.looksLikeError(null));
    }

    @Test
    public void parseProducesAgentMessageForNormalOutput() {
        List<ChatMessage> messages =
                AgentOutputParser.parse("Refactored the login handler.", "Claude Code", "🟠");
        assertEquals(1, messages.size());
        assertEquals(ChatMessage.Type.AGENT, messages.get(0).type);
        assertEquals("Claude Code", messages.get(0).agentName);
        assertEquals("🟠", messages.get(0).icon);
    }

    @Test
    public void parseProducesErrorMessageForFailures() {
        List<ChatMessage> messages =
                AgentOutputParser.parse("bash: claudee: command not found", "Codex", "⚪");
        assertEquals(1, messages.size());
        assertEquals(ChatMessage.Type.ERROR, messages.get(0).type);
    }

    @Test
    public void parseDropsBlankOutput() {
        assertTrue(AgentOutputParser.parse("   \n\n", "A", "x").isEmpty());
        assertTrue(AgentOutputParser.parse(null, "A", "x").isEmpty());
    }

    @Test
    public void parseDropsSpinnerOnlyNoise() {
        assertTrue(AgentOutputParser.parse("⠋\n⠙\n⠹", "A", "x").isEmpty());
    }

    @Test
    public void parseChunksVeryLongOutput() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append("line ").append(i).append('\n');
        }
        List<ChatMessage> messages = AgentOutputParser.parse(sb.toString(), "A", "x");
        assertTrue("expected the output to be split into several chunks, got " + messages.size(),
                messages.size() > 1);
        for (ChatMessage message : messages) {
            assertTrue("chunk exceeded the 4000 char cap: " + message.text.length(),
                    message.text.length() <= 4000);
        }
    }

    @Test
    public void userMessagesAreNotStreamedInto() {
        ChatMessage user = new ChatMessage(ChatMessage.Type.USER, "hi");
        assertTrue(user.isUser());
        assertFalse(user.streaming);
    }
}
