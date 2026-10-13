package com.termux.app.gui;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class AgentSlashCommandsTest {

    @Test
    public void testClaudeCodeCommands() {
        List<AgentSlashCommands.Command> commands = AgentSlashCommands.getCommandsForAgent("claude_code");
        Assert.assertFalse(commands.isEmpty());

        boolean hasCompact = false;
        boolean hasCost = false;
        boolean hasDoctor = false;
        boolean hasClear = false;

        for (AgentSlashCommands.Command cmd : commands) {
            if (cmd.name.equals("/compact")) hasCompact = true;
            if (cmd.name.equals("/cost")) hasCost = true;
            if (cmd.name.equals("/doctor")) hasDoctor = true;
            if (cmd.name.equals("/clear")) hasClear = true;
        }

        Assert.assertTrue(hasCompact);
        Assert.assertTrue(hasCost);
        Assert.assertTrue(hasDoctor);
        Assert.assertTrue(hasClear);
    }

    @Test
    public void testAntigravityCommands() {
        List<AgentSlashCommands.Command> commands = AgentSlashCommands.getCommandsForAgent("antigravity");
        Assert.assertFalse(commands.isEmpty());

        boolean hasPlan = false;
        boolean hasGoal = false;
        boolean hasSchedule = false;

        for (AgentSlashCommands.Command cmd : commands) {
            if (cmd.name.equals("/plan")) hasPlan = true;
            if (cmd.name.equals("/goal")) hasGoal = true;
            if (cmd.name.equals("/schedule")) hasSchedule = true;
        }

        Assert.assertTrue(hasPlan);
        Assert.assertTrue(hasGoal);
        Assert.assertTrue(hasSchedule);
    }

    @Test
    public void testFilterCommands() {
        List<AgentSlashCommands.Command> filtered = AgentSlashCommands.filterCommands("antigravity", "/plan");
        Assert.assertEquals(1, filtered.size());
        Assert.assertEquals("/plan", filtered.get(0).name);

        List<AgentSlashCommands.Command> emptyQuery = AgentSlashCommands.filterCommands("antigravity", "");
        Assert.assertEquals(AgentSlashCommands.getCommandsForAgent("antigravity").size(), emptyQuery.size());
    }

    @Test
    public void testAllAgentsHaveHelpAndClear() {
        String[] agentIds = {"claude_code", "antigravity", "opencode", "kiro", "codex", "grok", "github_copilot", "kilo", "custom"};
        for (String id : agentIds) {
            List<AgentSlashCommands.Command> cmds = AgentSlashCommands.getCommandsForAgent(id);
            boolean hasHelp = false;
            boolean hasClear = false;
            for (AgentSlashCommands.Command cmd : cmds) {
                if (cmd.name.equals("/help")) hasHelp = true;
                if (cmd.name.equals("/clear")) hasClear = true;
            }
            Assert.assertTrue("Agent " + id + " should have /help", hasHelp);
            Assert.assertTrue("Agent " + id + " should have /clear", hasClear);
        }
    }
}
