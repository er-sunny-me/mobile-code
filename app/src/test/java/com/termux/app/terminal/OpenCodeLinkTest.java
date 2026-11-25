package com.termux.app.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Tests for {@link OpenCodeLink}'s pure logic: SSE event parsing, the
 * models.dev / server catalogue parsers, and catalogue merging — the parts
 * of the linked-session work that can be proven on a JVM.
 */
public class OpenCodeLinkTest {

    // ---------------- Serve command ----------------

    @Test
    public void serveCommandStartsServerInWorkspace() {
        String cmd = OpenCodeLink.buildServeCommand("/storage/emulated/0/proj");
        assertTrue(cmd.contains("cd '/storage/emulated/0/proj'"));
        assertTrue(cmd.contains("opencode serve"));
        assertTrue(cmd.contains("--port 4096"));
        assertTrue(cmd.contains("--hostname 127.0.0.1"));
    }

    // ---------------- SSE event parsing ----------------

    @Test
    public void parsesTextPartSnapshot() {
        String json = "{\"type\":\"message.part.updated\",\"properties\":{"
                + "\"sessionID\":\"ses_1\","
                + "\"part\":{\"id\":\"prt_1\",\"sessionID\":\"ses_1\",\"type\":\"text\","
                + "\"text\":\"Hello world\"}}}";
        OpenCodeLink.Event ev = OpenCodeLink.parseEvent(json);
        assertNotNull(ev);
        assertEquals("message.part.updated", ev.type);
        assertEquals("ses_1", ev.sessionID);
        assertEquals("prt_1", ev.partID);
        assertEquals("text", ev.partType);
        assertEquals("Hello world", ev.text);
    }

    @Test
    public void parsesReasoningPartWithEscapes() {
        String json = "{\"type\":\"message.part.updated\",\"properties\":{"
                + "\"sessionID\":\"ses_1\","
                + "\"part\":{\"id\":\"prt_2\",\"type\":\"reasoning\","
                + "\"text\":\"line one\\nline \\\"two\\\"\"}}}";
        OpenCodeLink.Event ev = OpenCodeLink.parseEvent(json);
        assertNotNull(ev);
        assertEquals("reasoning", ev.partType);
        assertEquals("line one\nline \"two\"", ev.text);
    }

    @Test
    public void parsesDeltaEvent() {
        String json = "{\"type\":\"message.part.delta\",\"properties\":{"
                + "\"sessionID\":\"ses_1\",\"partID\":\"prt_9\",\"delta\":\"chunk\"}}";
        OpenCodeLink.Event ev = OpenCodeLink.parseEvent(json);
        assertNotNull(ev);
        assertEquals("chunk", ev.delta);
        // Delta events carry partID at the properties level in newer builds;
        // the parser must surface it so deltas join the right part.
        assertEquals("prt_9", ev.partID);
    }

    @Test
    public void parsesToolPartLabel() {
        String json = "{\"type\":\"message.part.updated\",\"properties\":{"
                + "\"sessionID\":\"ses_1\","
                + "\"part\":{\"id\":\"prt_3\",\"type\":\"tool\",\"tool\":\"edit\","
                + "\"state\":{\"status\":\"running\",\"input\":{}}}}}";
        OpenCodeLink.Event ev = OpenCodeLink.parseEvent(json);
        assertNotNull(ev);
        assertEquals("tool", ev.partType);
        assertEquals("edit running", ev.toolLabel);
    }

    @Test
    public void parsesSessionStatusIdle() {
        String json = "{\"type\":\"session.status\",\"properties\":{"
                + "\"sessionID\":\"ses_1\",\"status\":{\"type\":\"idle\"}}}";
        OpenCodeLink.Event ev = OpenCodeLink.parseEvent(json);
        assertNotNull(ev);
        assertEquals("idle", ev.statusType);
    }

    @Test
    public void parsesSessionErrorMessage() {
        String json = "{\"type\":\"session.error\",\"properties\":{"
                + "\"sessionID\":\"ses_1\","
                + "\"error\":{\"name\":\"ProviderAuthError\","
                + "\"data\":{\"message\":\"No API key\"}}}}";
        OpenCodeLink.Event ev = OpenCodeLink.parseEvent(json);
        assertNotNull(ev);
        assertEquals("No API key", ev.errorText);
    }

    @Test
    public void parsesPermissionAsked() {
        String json = "{\"type\":\"permission.asked\",\"properties\":{"
                + "\"id\":\"per_1\",\"sessionID\":\"ses_1\",\"permission\":\"edit\"}}";
        OpenCodeLink.Event ev = OpenCodeLink.parseEvent(json);
        assertNotNull(ev);
        assertEquals("per_1", ev.permissionID);
        assertEquals("ses_1", ev.sessionID);
    }

    @Test
    public void badJsonYieldsNullEvent() {
        assertNull(OpenCodeLink.parseEvent("not json"));
        assertNull(OpenCodeLink.parseEvent(null));
    }

    // ---------------- Catalogue parsing ----------------

    @Test
    public void parsesModelsDevCatalogueSkippingDeprecated() {
        String json = "{"
                + "\"opencode\":{\"id\":\"opencode\",\"models\":{"
                + "\"big-pickle\":{\"id\":\"big-pickle\"},"
                + "\"old-one\":{\"id\":\"old-one\",\"status\":\"deprecated\"}}},"
                + "\"anthropic\":{\"id\":\"anthropic\",\"models\":{"
                + "\"claude-sonnet-4-5\":{\"id\":\"claude-sonnet-4-5\"}}}"
                + "}";
        List<String> out = OpenCodeLink.parseModelsDevCatalogue(json);
        assertEquals(Arrays.asList(
                "anthropic/claude-sonnet-4-5",
                "opencode/big-pickle"), out);
    }

    @Test
    public void parsesProviderResponseConnectedFirst() {
        String json = "{"
                + "\"all\":["
                + "{\"id\":\"openai\",\"models\":{\"gpt-5\":{\"id\":\"gpt-5\"}}},"
                + "{\"id\":\"opencode\",\"models\":{\"big-pickle\":{\"id\":\"big-pickle\"},"
                + "\"beam-free\":{\"id\":\"beam-free\"}}}"
                + "],"
                + "\"connected\":[\"opencode\"]"
                + "}";
        List<String> out = OpenCodeLink.parseProviderResponse(json);
        assertEquals(Arrays.asList(
                "opencode/beam-free",
                "opencode/big-pickle",
                "openai/gpt-5"), out);
    }

    @Test
    public void mergeKeepsConnectedOrderThenCatalogueExtras() {
        List<String> connected = Arrays.asList("opencode/big-pickle", "opencode/beam-free");
        List<String> catalogue = Arrays.asList(
                "anthropic/claude-sonnet-4-5", "opencode/big-pickle", "openai/gpt-5");
        List<String> merged = OpenCodeLink.mergeModelLists(connected, catalogue);
        assertEquals(Arrays.asList(
                "opencode/big-pickle",
                "opencode/beam-free",
                "anthropic/claude-sonnet-4-5",
                "openai/gpt-5"), merged);
    }

    @Test
    public void catalogueParsersTolerateGarbage() {
        assertTrue(OpenCodeLink.parseModelsDevCatalogue("").isEmpty());
        assertTrue(OpenCodeLink.parseModelsDevCatalogue(null).isEmpty());
        assertTrue(OpenCodeLink.parseProviderResponse("{}").isEmpty());
        assertTrue(OpenCodeLink.parseProviderResponse(null).isEmpty());
    }

    // ---------------- Prompt body / variants ----------------

    @Test
    public void promptBodyIncludesVariantOnlyWhenResolved() {
        String with = OpenCodeLink.buildPromptBody("hi", "opencode/big-pickle", "high");
        assertTrue(with.contains("\"variant\":\"high\""));
        assertTrue(with.contains("\"providerID\":\"opencode\""));
        assertTrue(with.contains("\"modelID\":\"big-pickle\""));
        String without = OpenCodeLink.buildPromptBody("hi", "opencode/big-pickle", null);
        assertTrue(!without.contains("variant"));
        String noModel = OpenCodeLink.buildPromptBody("hi", null, null);
        assertTrue(!noModel.contains("model"));
        assertTrue(noModel.contains("\"text\":\"hi\""));
    }

    @Test
    public void resolveVariantMapsLevelsToModelDefinedNamesOnly() {
        java.util.List<String> lowHighMax = Arrays.asList("low", "high", "max");
        assertEquals("low", OpenCodeLink.resolveVariant("Low (2k)", lowHighMax));
        assertEquals("high", OpenCodeLink.resolveVariant("High (16k)", lowHighMax));
        assertEquals("max", OpenCodeLink.resolveVariant("Max (32k)", lowHighMax));
        // "medium" is NOT defined by this model: never guessed, never sent.
        assertNull(OpenCodeLink.resolveVariant("Medium (8k)", lowHighMax));
        assertNull(OpenCodeLink.resolveVariant("Off", lowHighMax));
        // Unknown variant support and known-none both resolve to no-send.
        assertNull(OpenCodeLink.resolveVariant("High (16k)", null));
        assertNull(OpenCodeLink.resolveVariant("High (16k)", java.util.Collections.emptyList()));
        assertNull(OpenCodeLink.resolveVariant(null, lowHighMax));
    }

    @Test
    public void parsesProviderVariantsExcludingDisabled() {
        String json = "{"
                + "\"all\":[{\"id\":\"opencode\",\"models\":{"
                + "\"big-pickle\":{\"id\":\"big-pickle\"},"
                + "\"deepseek-v4.1-flash-free\":{\"id\":\"deepseek-v4.1-flash-free\","
                + "\"variants\":{\"low\":{},\"high\":{},\"max\":{},\"medium\":{\"disabled\":true}}}"
                + "}}],"
                + "\"connected\":[\"opencode\"]"
                + "}";
        java.util.Map<String, java.util.List<String>> out = OpenCodeLink.parseProviderVariants(json);
        assertEquals(Arrays.asList("high", "low", "max"), out.get("opencode/deepseek-v4.1-flash-free"));
        // Model present but with no variants key: known-none (empty list).
        assertEquals(java.util.Collections.emptyList(), out.get("opencode/big-pickle"));
    }

    @Test
    public void derivesVariantsFromModelsDevReasoningOptions() {
        String json = "{"
                + "\"opencode\":{\"id\":\"opencode\",\"models\":{"
                + "\"big-pickle\":{\"id\":\"big-pickle\",\"reasoning\":true,\"reasoning_options\":[]},"
                + "\"deepseek-v4.1-flash-free\":{\"id\":\"deepseek-v4.1-flash-free\",\"reasoning\":true,"
                + "\"reasoning_options\":[{\"type\":\"toggle\"},{\"type\":\"effort\",\"values\":[\"low\",\"high\",\"max\"]}]},"
                + "\"hy4-flash-free\":{\"id\":\"hy4-flash-free\",\"reasoning\":true,"
                + "\"reasoning_options\":[{\"type\":\"effort\",\"values\":[\"none\",\"high\"]}]}"
                + "}}"
                + "}";
        java.util.Map<String, java.util.List<String>> out = OpenCodeLink.parseModelsDevVariants(json);
        // big-pickle: reasoning_options is EMPTY — the honest answer is that
        // it exposes no effort variants, not an invented "high".
        assertEquals(java.util.Collections.emptyList(), out.get("opencode/big-pickle"));
        assertEquals(Arrays.asList("high", "low", "max"), out.get("opencode/deepseek-v4.1-flash-free"));
        assertEquals(Arrays.asList("high", "none"), out.get("opencode/hy4-flash-free"));
    }

    // ---------------- Turn streaming: role filter + dedupe ----------------

    private static OpenCodeLink.Event partEvent(String messageId, String partId,
                                                String type, String text) {
        String json = "{\"type\":\"message.part.updated\",\"properties\":{"
                + "\"sessionID\":\"ses_1\","
                + "\"part\":{\"id\":\"" + partId + "\",\"messageID\":\"" + messageId
                + "\",\"sessionID\":\"ses_1\",\"type\":\"" + type + "\","
                + "\"text\":" + OpenCodeLink.MiniJson.quote(text) + "}}}";
        return OpenCodeLink.parseEvent(json);
    }

    private static OpenCodeLink.Event messageEvent(String messageId, String role) {
        String json = "{\"type\":\"message.updated\",\"properties\":{"
                + "\"sessionID\":\"ses_1\","
                + "\"info\":{\"id\":\"" + messageId + "\",\"sessionID\":\"ses_1\","
                + "\"role\":\"" + role + "\"}}}";
        return OpenCodeLink.parseEvent(json);
    }

    @Test
    public void userMessagePartsNeverReachTheReply() {
        OpenCodeLink.TurnState state = new OpenCodeLink.TurnState();
        state.promptText = "Create a html file";
        // The user's own prompt streams back over the same event bus.
        state.classifyMessage("msg_user", "user");
        assertNull(state.applyPartEvent(partEvent("msg_user", "prt_u1", "text", "Create a html file")));
        assertEquals("", state.textSnapshot());
        // The assistant's reply applies normally.
        state.classifyMessage("msg_asst", "assistant");
        String[] snaps = state.applyPartEvent(partEvent("msg_asst", "prt_a1", "text", "Done."));
        assertNotNull(snaps);
        assertEquals("Done.", state.textSnapshot());
    }

    @Test
    public void partsArrivingBeforeClassificationAreBuffered() {
        OpenCodeLink.TurnState state = new OpenCodeLink.TurnState();
        // Part first, message.updated second (out-of-order safety).
        assertNull(state.applyPartEvent(partEvent("msg_asst", "prt_a1", "text", "Hello")));
        assertEquals("", state.textSnapshot());
        String[] snaps = state.classifyMessage("msg_asst", "assistant");
        assertNotNull(snaps);
        assertEquals("Hello", state.textSnapshot());
        // And a buffered USER part is dropped on classification.
        OpenCodeLink.TurnState state2 = new OpenCodeLink.TurnState();
        assertNull(state2.applyPartEvent(partEvent("msg_user", "prt_u1", "text", "prompt")));
        assertNull(state2.classifyMessage("msg_user", "user"));
        assertEquals("", state2.textSnapshot());
    }

    @Test
    public void reasoningMirroredIntoTextIsShownOnlyInTheThoughtBubble() {
        // The big-pickle shape from the real device: the text part carries
        // the full monologue PLUS the final answer, and the same monologue
        // also arrives as a reasoning part.
        String monologue = "The user wants an HTML file and a website.\n"
                + "Let me check the working directory first.\n"
                + "Empty directory. Let me create a simple HTML file.";
        String finalLine = "Created `/home/index.html` — open it in a browser.";
        OpenCodeLink.TurnState state = new OpenCodeLink.TurnState();
        state.promptText = "Create a html file";
        state.classifyMessage("msg_asst", "assistant");
        state.applyPartEvent(partEvent("msg_asst", "prt_r1", "reasoning", monologue));
        state.applyPartEvent(partEvent("msg_asst", "prt_t1", "text", monologue + "\n" + finalLine));
        assertEquals(monologue, state.reasoningSnapshot());
        assertEquals(finalLine, state.textSnapshot());
    }

    @Test
    public void stripReasoningHandlesPrefixContainmentAndPartialOverlap() {
        assertEquals("Final answer.",
                OpenCodeLink.stripReasoningFromText("thinking out loud\nFinal answer.", "thinking out loud"));
        // Reasoning block contained at the END of the text is cut too.
        assertEquals("Final answer first.",
                OpenCodeLink.stripReasoningFromText("Final answer first.\nthinking out loud", "thinking out loud"));
        // Line-level partial overlap while streaming.
        assertEquals("Real reply line.",
                OpenCodeLink.stripReasoningFromText(
                        "reason line one\nreason line two\nReal reply line.",
                        "reason line one\nreason line two\nreason line three"));
        // No reasoning / no overlap: text untouched.
        assertEquals("Just text.", OpenCodeLink.stripReasoningFromText("Just text.", ""));
        assertEquals("Just text.", OpenCodeLink.stripReasoningFromText("Just text.", "unrelated"));
    }

    @Test
    public void reasoningDeltasRouteToReasoningNotText() {
        // message.part.delta reports field:"text" even for reasoning parts;
        // routing must follow the part's learned type, not the field.
        OpenCodeLink.TurnState state = new OpenCodeLink.TurnState();
        state.classifyMessage("msg_asst", "assistant");
        state.applyPartEvent(partEvent("msg_asst", "prt_r1", "reasoning", "think"));
        String deltaJson = "{\"type\":\"message.part.delta\",\"properties\":{"
                + "\"sessionID\":\"ses_1\",\"messageID\":\"msg_asst\","
                + "\"partID\":\"prt_r1\",\"field\":\"text\",\"delta\":\" more\"}}";
        state.applyPartEvent(OpenCodeLink.parseEvent(deltaJson));
        assertEquals("think more", state.reasoningSnapshot());
        assertEquals("", state.textSnapshot());
    }

    @Test
    public void promptEchoTextPartIsDropped() {
        OpenCodeLink.TurnState state = new OpenCodeLink.TurnState();
        state.promptText = "Hello";
        state.classifyMessage("msg_asst", "assistant");
        // Even if a text part equal to the prompt shows up under an
        // assistant-classified message, it is the echo, not a reply.
        assertNull(state.applyPartEvent(partEvent("msg_asst", "prt_x", "text", "Hello")));
        assertEquals("", state.textSnapshot());
    }

    // ---------------- Server working directory ----------------

    @Test
    public void chooseServerDirPrefersWorkspaceThenFallback() throws Exception {
        java.io.File ws = java.nio.file.Files.createTempDirectory("ws").toFile();
        java.io.File home = java.nio.file.Files.createTempDirectory("home").toFile();
        assertEquals(ws.getAbsolutePath(),
                OpenCodeLink.chooseServerDir(ws.getAbsolutePath(), home.getAbsolutePath()).getAbsolutePath());
        // Missing workspace path is created (AgentRunner's behaviour).
        java.io.File fresh = new java.io.File(ws, "new-folder");
        assertEquals(fresh.getAbsolutePath(),
                OpenCodeLink.chooseServerDir(fresh.getAbsolutePath(), home.getAbsolutePath()).getAbsolutePath());
        assertTrue(fresh.isDirectory());
    }

    // ---------------- Attach command (terminal shows the GUI session) ----------------

    @Test
    public void attachCommandContinuesTheGuiSession() {
        // opencode attach supports --session <id> ("Session ID to
        // continue", CLI docs). Without it the TUI opens a fresh empty
        // session — the phone failure this guards against.
        assertEquals("opencode attach http://127.0.0.1:4096 --session ses_abc123",
                OpenCodeLink.buildAttachCommand("ses_abc123"));
    }

    @Test
    public void attachCommandWithoutSessionStaysBare() {
        assertEquals("opencode attach http://127.0.0.1:4096",
                OpenCodeLink.buildAttachCommand(null));
        assertEquals("opencode attach http://127.0.0.1:4096",
                OpenCodeLink.buildAttachCommand(""));
    }

    // ---------------- Turn watchdog (server-death policy) ----------------

    @Test
    public void watchdogFinishesFromHistoryWhenServerUpAndSessionIdle() {
        assertEquals(OpenCodeLink.TurnWatchdog.Action.FINISH_FETCHED,
                OpenCodeLink.TurnWatchdog.decide(true, Boolean.TRUE, true, 0, 0));
    }

    @Test
    public void watchdogReconnectsWhenServerUpAndSessionBusyOrUnknown() {
        assertEquals(OpenCodeLink.TurnWatchdog.Action.RECONNECT,
                OpenCodeLink.TurnWatchdog.decide(true, Boolean.FALSE, true, 0, 0));
        assertEquals(OpenCodeLink.TurnWatchdog.Action.RECONNECT,
                OpenCodeLink.TurnWatchdog.decide(true, null, true, 0, 3));
        // Reconnect budget exhausted: settle for the fetched history.
        assertEquals(OpenCodeLink.TurnWatchdog.Action.FINISH_FETCHED,
                OpenCodeLink.TurnWatchdog.decide(true, Boolean.FALSE, true, 0,
                        OpenCodeLink.TurnWatchdog.MAX_RECONNECTS));
    }

    @Test
    public void watchdogRestartsOnceOnDeadOrWedgedServerThenFails() {
        assertEquals(OpenCodeLink.TurnWatchdog.Action.RESTART_AND_RECOVER,
                OpenCodeLink.TurnWatchdog.decide(false, null, false, 0, 0));
        // Process claims alive but probes keep failing = wedged: same
        // single restart (the restart kills it), never a second one.
        assertEquals(OpenCodeLink.TurnWatchdog.Action.RESTART_AND_RECOVER,
                OpenCodeLink.TurnWatchdog.decide(false, null, true, 0, 0));
        assertEquals(OpenCodeLink.TurnWatchdog.Action.FAIL,
                OpenCodeLink.TurnWatchdog.decide(false, null, false, 1, 0));
    }

    // ---------------- Duplicate-send guard ----------------

    @Test
    public void duplicateSendOnlyWhenSameTextInFlightAndFresh() {
        long now = 100_000;
        assertTrue(OpenCodeLink.isDuplicateSend("hello", now - 5_000, "hello", now, true, 30_000));
        // Turn finished: resending the same text is legitimate.
        assertTrue(!OpenCodeLink.isDuplicateSend("hello", now - 5_000, "hello", now, false, 30_000));
        // Different text is a new message, even mid-turn.
        assertTrue(!OpenCodeLink.isDuplicateSend("hello", now - 5_000, "hello again", now, true, 30_000));
        // Window passed: no longer treated as a double-send.
        assertTrue(!OpenCodeLink.isDuplicateSend("hello", now - 60_000, "hello", now, true, 30_000));
        // Nothing sent yet.
        assertTrue(!OpenCodeLink.isDuplicateSend(null, 0, "hello", now, true, 30_000));
    }

    // ---------------- Session-history recovery parsing ----------------

    @Test
    public void parsesLastTurnFromSessionMessages() {
        String json = "["
                + "{\"info\":{\"id\":\"m1\",\"role\":\"user\"},\"parts\":[{\"type\":\"text\",\"text\":\"old ask\"}]},"
                + "{\"info\":{\"id\":\"m2\",\"role\":\"assistant\"},\"parts\":[{\"type\":\"text\",\"text\":\"old reply\"}]},"
                + "{\"info\":{\"id\":\"m3\",\"role\":\"user\"},\"parts\":[{\"type\":\"text\",\"text\":\"create a website\"}]},"
                + "{\"info\":{\"id\":\"m4\",\"role\":\"assistant\"},\"parts\":["
                + "{\"type\":\"reasoning\",\"text\":\"Let me list files.\"},"
                + "{\"type\":\"tool\",\"tool\":\"write\"},"
                + "{\"type\":\"text\",\"text\":\"Created index.html\"}]},"
                + "{\"info\":{\"id\":\"m5\",\"role\":\"assistant\"},\"parts\":["
                + "{\"type\":\"text\",\"text\":\"Anything else?\"}]}"
                + "]";
        String[] recovered = OpenCodeLink.parseSessionMessages(json);
        assertNotNull(recovered);
        // Only the LAST turn's assistant messages, text and reasoning apart.
        assertEquals("Created index.html\nAnything else?", recovered[0]);
        assertEquals("Let me list files.", recovered[1]);
    }

    @Test
    public void sessionMessagesWithoutAssistantReplyYieldNull() {
        assertNull(OpenCodeLink.parseSessionMessages(
                "[{\"info\":{\"role\":\"user\"},\"parts\":[{\"type\":\"text\",\"text\":\"hi\"}]}]"));
        assertNull(OpenCodeLink.parseSessionMessages("[]"));
        assertNull(OpenCodeLink.parseSessionMessages("not json"));
        assertNull(OpenCodeLink.parseSessionMessages(null));
    }

    // ---------------- Config reasoning effort ----------------

    @Test
    public void configContentWithoutOverrideIsPermissionOnly() {
        assertEquals("{\"permission\":\"allow\"}",
                OpenCodeLink.buildConfigContent(null, null));
        assertEquals("{\"permission\":\"allow\"}",
                OpenCodeLink.buildConfigContent("opencode/big-pickle", null));
        assertEquals("{\"permission\":\"allow\"}",
                OpenCodeLink.buildConfigContent(null, "high"));
        // A model id without a provider part cannot be aimed — no override.
        assertEquals("{\"permission\":\"allow\"}",
                OpenCodeLink.buildConfigContent("big-pickle", "high"));
    }

    @Test
    public void configContentWritesReasoningEffortForProviderModel() {
        // The exact shape the official config schema defines:
        // provider.<pid>.models.<mid>.options.reasoningEffort.
        assertEquals("{\"permission\":\"allow\",\"provider\":{\"opencode\":{\"models\":"
                        + "{\"big-pickle\":{\"options\":{\"reasoningEffort\":\"high\"}}}}}}",
                OpenCodeLink.buildConfigContent("opencode/big-pickle", "high"));
        assertEquals("{\"permission\":\"allow\",\"provider\":{\"anthropic\":{\"models\":"
                        + "{\"claude-sonnet-4-5\":{\"options\":{\"reasoningEffort\":\"low\"}}}}}}",
                OpenCodeLink.buildConfigContent("anthropic/claude-sonnet-4-5", "low"));
    }

    @Test
    public void effortForLevelMapsAppLevels() {
        assertEquals("low", OpenCodeLink.effortForLevel("Low (2k)"));
        assertEquals("medium", OpenCodeLink.effortForLevel("Medium (8k)"));
        assertEquals("high", OpenCodeLink.effortForLevel("High (16k)"));
        // Max has no stronger standard effort word — it maps to high.
        assertEquals("high", OpenCodeLink.effortForLevel("Max (32k)"));
        assertNull(OpenCodeLink.effortForLevel("Off"));
        assertNull(OpenCodeLink.effortForLevel(null));
    }

    @Test
    public void applyConfigOverrideTracksStateAndServeCommandCarriesIt() {
        try {
            assertTrue(OpenCodeLink.applyConfigReasoningEffort("opencode/big-pickle", "high"));
            // Same state again — no change, no restart.
            assertTrue(!OpenCodeLink.applyConfigReasoningEffort("opencode/big-pickle", "high"));
            assertEquals("opencode/big-pickle", OpenCodeLink.getConfigEffortModel());
            assertEquals("high", OpenCodeLink.getConfigEffort());
            String cmd = OpenCodeLink.buildServeCommand("/root");
            assertTrue(cmd.contains("reasoningEffort"));
            assertTrue(cmd.contains("big-pickle"));
        } finally {
            OpenCodeLink.applyConfigReasoningEffort(null, null);
        }
        assertNull(OpenCodeLink.getConfigEffort());
        assertEquals("{\"permission\":\"allow\"}", OpenCodeLink.currentConfigContent());
    }

    // ---------------- Terminal ensure + attach ----------------

    @Test
    public void terminalCommandEnsuresServerThenAttaches() {
        String cmd = OpenCodeLink.buildTerminalCommand("ses_gui");
        // Ensure step: port probe + same serve invocation, started only
        // when the port does not answer.
        assertTrue(cmd.contains("/dev/tcp/127.0.0.1/4096"));
        assertTrue(cmd.contains("opencode serve --hostname 127.0.0.1 --port 4096"));
        assertTrue(cmd.contains("OPENCODE_CONFIG_CONTENT"));
        // Then attach, naming the shared session.
        assertTrue(cmd.contains("opencode attach http://127.0.0.1:4096"));
        assertTrue(cmd.contains("--session ses_gui"));
        assertTrue(cmd.indexOf("opencode serve") < cmd.indexOf("opencode attach"));
    }

    @Test
    public void terminalCommandWithoutSessionOmitsSessionFlag() {
        String cmd = OpenCodeLink.buildTerminalCommand(null);
        assertTrue(cmd.contains("opencode attach http://127.0.0.1:4096"));
        assertTrue(!cmd.contains("--session"));
    }

    // ---------------- Session adoption ----------------

    @Test
    public void directoryCandidatesMapHostPathsToWorkshopPaths() {
        java.util.Set<String> c = OpenCodeLink.sessionDirectoryCandidates(
                "/storage/emulated/0/13.11.2026", "/data/data/com.xyzide/files/home");
        assertTrue(c.contains("/storage/emulated/0/13.11.2026"));
        // Device-evidenced binding: shared storage appears at /mnt/sdcard.
        assertTrue(c.contains("/mnt/sdcard/13.11.2026"));

        java.util.Set<String> home = OpenCodeLink.sessionDirectoryCandidates(
                "/data/data/com.xyzide/files/home", "/data/data/com.xyzide/files/home");
        assertTrue(home.contains("/root"));
    }

    @Test
    public void adoptionPicksMostRecentlyUpdatedSessionForWorkspace() {
        String json = "["
                + "{\"id\":\"ses_old\",\"directory\":\"/mnt/sdcard/proj\",\"time\":{\"updated\":100}},"
                + "{\"id\":\"ses_new\",\"directory\":\"/mnt/sdcard/proj\",\"time\":{\"updated\":300}},"
                + "{\"id\":\"ses_elsewhere\",\"directory\":\"/other\",\"time\":{\"updated\":999}}"
                + "]";
        java.util.Set<String> dirs = OpenCodeLink.sessionDirectoryCandidates(
                "/storage/emulated/0/proj", "/home");
        assertEquals("ses_new", OpenCodeLink.pickSessionToAdopt(json, dirs));
    }

    @Test
    public void adoptionFallsBackToTheOnlySession() {
        // One session in the world, directory spelled in a way the
        // candidates did not foresee — still unambiguous, adopt it.
        String json = "[{\"id\":\"ses_only\",\"directory\":\"/weird/path\",\"time\":{\"updated\":5}}]";
        assertEquals("ses_only", OpenCodeLink.pickSessionToAdopt(json,
                OpenCodeLink.sessionDirectoryCandidates("/storage/emulated/0/proj", "/home")));
    }

    @Test
    public void adoptionRefusesAmbiguousOrEmptyLists() {
        String json = "["
                + "{\"id\":\"ses_a\",\"directory\":\"/x\",\"time\":{\"updated\":1}},"
                + "{\"id\":\"ses_b\",\"directory\":\"/y\",\"time\":{\"updated\":2}}"
                + "]";
        assertNull(OpenCodeLink.pickSessionToAdopt(json,
                OpenCodeLink.sessionDirectoryCandidates("/storage/emulated/0/proj", "/home")));
        assertNull(OpenCodeLink.pickSessionToAdopt("[]", java.util.Collections.emptySet()));
        assertNull(OpenCodeLink.pickSessionToAdopt(null, java.util.Collections.emptySet()));
        assertNull(OpenCodeLink.pickSessionToAdopt("not json", java.util.Collections.emptySet()));
    }

    @Test
    public void adoptionHandlesDataWrappedList() {
        String json = "{\"data\":[{\"id\":\"ses_w\",\"directory\":\"/root\",\"time\":{\"updated\":7}}]}";
        java.util.Set<String> dirs = OpenCodeLink.sessionDirectoryCandidates("/home", "/home");
        dirs.add("/root");
        assertEquals("ses_w", OpenCodeLink.pickSessionToAdopt(json, dirs));
    }

    // ---------------- Session resolution preference ----------------

    @Test
    public void resolverPrefersNewerServerSessionOverStoredId() {
        // The sync bug: the GUI stored ses_gui long ago; the user then
        // worked in the terminal, whose session is NEWER. Resolution must
        // pick the server's most recent workspace session, NOT the still-
        // valid stored id — otherwise the two never converge.
        String json = "["
                + "{\"id\":\"ses_gui\",\"directory\":\"/mnt/sdcard/proj\",\"time\":{\"updated\":100}},"
                + "{\"id\":\"ses_tui\",\"directory\":\"/mnt/sdcard/proj\",\"time\":{\"updated\":200}}"
                + "]";
        java.util.Set<String> dirs =
                OpenCodeLink.sessionDirectoryCandidates("/storage/emulated/0/proj", "/home");
        assertEquals("ses_tui", OpenCodeLink.chooseResolvedSessionId(json, dirs, "ses_gui"));
    }

    @Test
    public void resolverKeepsStoredIdWhenServerListHasNoMatch() {
        // Several sessions, none for this workspace, and more than one in
        // total (so the single-session adoption fallback must not fire):
        // the stored id is the only sane answer.
        String json = "["
                + "{\"id\":\"ses_a\",\"directory\":\"/x\",\"time\":{\"updated\":300}},"
                + "{\"id\":\"ses_b\",\"directory\":\"/y\",\"time\":{\"updated\":400}}"
                + "]";
        java.util.Set<String> dirs =
                OpenCodeLink.sessionDirectoryCandidates("/storage/emulated/0/proj", "/home");
        assertEquals("ses_gui", OpenCodeLink.chooseResolvedSessionId(json, dirs, "ses_gui"));
        assertNull(OpenCodeLink.chooseResolvedSessionId(json, dirs, null));
        assertNull(OpenCodeLink.chooseResolvedSessionId("[]", dirs, null));
    }

    @Test
    public void resolverAdoptsTheOnlySessionEvenIfStoredDiffers() {
        String json = "[{\"id\":\"ses_only\",\"directory\":\"/weird\",\"time\":{\"updated\":5}}]";
        java.util.Set<String> dirs =
                OpenCodeLink.sessionDirectoryCandidates("/storage/emulated/0/proj", "/home");
        assertEquals("ses_only", OpenCodeLink.chooseResolvedSessionId(json, dirs, "ses_gui"));
    }

    // ---------------- Session history ----------------

    private static String historyJson() {
        return "["
                + "{\"info\":{\"id\":\"msg_1\",\"role\":\"user\"},\"parts\":["
                + "{\"id\":\"p1\",\"type\":\"text\",\"text\":\"Build me a site\"}]},"
                + "{\"info\":{\"id\":\"msg_2\",\"role\":\"assistant\"},\"parts\":["
                + "{\"id\":\"p2\",\"type\":\"reasoning\",\"text\":\"Let me think\"},"
                + "{\"id\":\"p3\",\"type\":\"text\",\"text\":\"Done — index.html created\"},"
                + "{\"id\":\"p4\",\"type\":\"tool\",\"tool\":\"write\",\"state\":{\"status\":\"completed\"}}]},"
                + "{\"info\":{\"id\":\"msg_3\",\"role\":\"user\"},\"parts\":["
                + "{\"id\":\"p5\",\"type\":\"text\",\"text\":\"Now make it red\"}]},"
                + "{\"info\":{\"role\":\"assistant\"},\"parts\":["
                + "{\"id\":\"p6\",\"type\":\"text\",\"text\":\"id-less, must be skipped\"}]},"
                + "{\"info\":{\"id\":\"msg_4\",\"role\":\"assistant\"},\"parts\":["
                + "{\"id\":\"p7\",\"type\":\"step-start\"}]}"
                + "]";
    }

    @Test
    public void parseSessionHistoryExtractsAllMessagesInOrder() {
        java.util.List<OpenCodeLink.HistoryMessage> history =
                OpenCodeLink.parseSessionHistory(historyJson());
        // msg_4 has neither text nor reasoning (step part only) and the
        // id-less message is skipped: 3 messages remain, in order.
        assertEquals(3, history.size());
        assertEquals("msg_1", history.get(0).id);
        assertEquals("user", history.get(0).role);
        assertEquals("Build me a site", history.get(0).text);
        assertEquals("", history.get(0).reasoning);
        assertEquals("msg_2", history.get(1).id);
        assertEquals("assistant", history.get(1).role);
        assertEquals("Done — index.html created", history.get(1).text);
        assertEquals("Let me think", history.get(1).reasoning);
        assertEquals("msg_3", history.get(2).id);
        assertEquals("user", history.get(2).role);
    }

    @Test
    public void parseSessionHistoryToleratesGarbage() {
        assertTrue(OpenCodeLink.parseSessionHistory(null).isEmpty());
        assertTrue(OpenCodeLink.parseSessionHistory("not json").isEmpty());
        assertTrue(OpenCodeLink.parseSessionHistory("{}").isEmpty());
    }

    @Test
    public void filterUnseenHistoryDedupesByIdAndRenderedKey() {
        java.util.List<OpenCodeLink.HistoryMessage> all =
                OpenCodeLink.parseSessionHistory(historyJson());
        // msg_1 already delivered by id; msg_2's content is already on
        // screen (restored from the chat's local store, keyed by content).
        java.util.Set<String> shownIds = new java.util.HashSet<>(java.util.Collections.singleton("msg_1"));
        java.util.Set<String> renderedKeys = new java.util.HashSet<>(
                java.util.Collections.singleton(OpenCodeLink.historyKey(all.get(1))));
        java.util.List<OpenCodeLink.HistoryMessage> unseen =
                OpenCodeLink.filterUnseenHistory(all, shownIds, renderedKeys);
        assertEquals(1, unseen.size());
        assertEquals("msg_3", unseen.get(0).id);
        // Nothing filtered when both sets are empty/null.
        assertEquals(3, OpenCodeLink.filterUnseenHistory(all, null, null).size());
    }

    @Test
    public void historyKeyNormalisesWhitespace() {
        OpenCodeLink.HistoryMessage a =
                new OpenCodeLink.HistoryMessage("x", "user", "hello   world\nagain", "");
        OpenCodeLink.HistoryMessage b =
                new OpenCodeLink.HistoryMessage("y", "user", "hello world again", "");
        assertEquals(OpenCodeLink.historyKey(a), OpenCodeLink.historyKey(b));
    }

    // ---------------- MiniJson ----------------

    @Test
    public void miniJsonRoundTripsThroughQuote() {
        String original = "a \"quoted\" \\ path\nnewline\ttab";
        Object parsed = OpenCodeLink.MiniJson.parse(
                "{\"v\":" + OpenCodeLink.MiniJson.quote(original) + "}");
        assertEquals(original, OpenCodeLink.MiniJson.str(
                OpenCodeLink.MiniJson.asMap(parsed), "v"));
    }

    @Test
    public void terminalCommandIncludesLocalBinAndSession() {
        String cmd = OpenCodeLink.buildTerminalCommand("ses_abc");
        assertTrue(cmd.contains("$HOME/.local/bin"));
        assertTrue(cmd.contains("$HOME/.opencode/bin"));
        assertTrue(cmd.contains("opencode attach http://127.0.0.1:4096 --session ses_abc"));
    }

    @Test
    public void watchEventStreamsBackgroundTurnAndResetsOnIdle() {
        OpenCodeLink link = new OpenCodeLink();
        final java.util.List<String> events = new java.util.ArrayList<>();
        OpenCodeLink.Listener listener = new OpenCodeLink.Listener() {
            @Override public void onLinked(String sessionId) { events.add("linked:" + sessionId); }
            @Override public void onText(String snapshot) { events.add("text:" + snapshot); }
            @Override public void onReasoning(String snapshot) { events.add("reasoning:" + snapshot); }
            @Override public void onTool(String label) { events.add("tool:" + label); }
            @Override public void onFinished(String error) { events.add("finished:" + error); }
            @Override public void onUnavailable(String reason) { events.add("unavail:" + reason); }
        };

        OpenCodeLink.TurnState state = new OpenCodeLink.TurnState();
        boolean[] turnActive = {false};

        state = link.handleWatchEvent(
                "{\"type\":\"message.updated\",\"properties\":{\"sessionID\":\"ses_1\",\"info\":{\"id\":\"m_u\",\"role\":\"user\"}}}",
                "ses_1", state, listener, turnActive, false);
        state = link.handleWatchEvent(
                "{\"type\":\"message.updated\",\"properties\":{\"sessionID\":\"ses_1\",\"info\":{\"id\":\"m_a\",\"role\":\"assistant\"}}}",
                "ses_1", state, listener, turnActive, false);
        state = link.handleWatchEvent(
                "{\"type\":\"message.part.updated\",\"properties\":{\"sessionID\":\"ses_1\",\"part\":{\"id\":\"p_r\",\"messageID\":\"m_a\",\"type\":\"reasoning\",\"text\":\"Thinking\"}}}",
                "ses_1", state, listener, turnActive, false);
        state = link.handleWatchEvent(
                "{\"type\":\"message.part.updated\",\"properties\":{\"sessionID\":\"ses_1\",\"part\":{\"id\":\"p_t\",\"messageID\":\"m_a\",\"type\":\"tool\",\"tool\":\"read\",\"state\":{\"status\":\"running\"}}}}",
                "ses_1", state, listener, turnActive, false);
        state = link.handleWatchEvent(
                "{\"type\":\"message.part.updated\",\"properties\":{\"sessionID\":\"ses_1\",\"part\":{\"id\":\"p_x\",\"messageID\":\"m_a\",\"type\":\"text\",\"text\":\"Done\"}}}",
                "ses_1", state, listener, turnActive, false);
        state = link.handleWatchEvent(
                "{\"type\":\"session.idle\",\"properties\":{\"sessionID\":\"ses_1\"}}",
                "ses_1", state, listener, turnActive, false);

        assertTrue(events.contains("linked:ses_1"));
        assertTrue(events.contains("reasoning:Thinking"));
        assertTrue(events.contains("tool:read running"));
        assertTrue(events.contains("text:Done"));
        assertTrue(events.contains("finished:null"));
        assertTrue(!turnActive[0]);
    }
}
