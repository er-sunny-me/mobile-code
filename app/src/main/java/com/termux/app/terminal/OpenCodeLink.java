package com.termux.app.terminal;

import android.os.Handler;
import android.os.Looper;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Linked live session for OpenCode: ONE long-running {@code opencode serve}
 * process (started once, kept alive in the background) that the GUI chat and
 * the terminal TUI both talk to.
 *
 * <p>Before this class, every GUI message spawned a fresh one-shot
 * {@code opencode run "<prompt>"} process (see
 * {@link CLIAgentManager#buildGuiPromptCommand}): a full CLI cold start —
 * proot login, config load, provider catalogue fetch — on every single
 * message, and the one-shot's stdout stayed silent until the model finished,
 * so the GUI showed a bare "thinking…" timer for a minute or more. OpenCode
 * is a client-server system, so the fix is to use its own server:</p>
 *
 * <ul>
 *   <li>The server is started once per workspace (workshop-wrapped, exactly
 *       like the terminal's environment) and reused by every GUI message.</li>
 *   <li>GUI messages go in over HTTP ({@code POST /session/:id/prompt_async})
 *       and the reply streams back over the server's SSE event bus
 *       ({@code GET /event}): {@code message.part.updated} events carry the
 *       live text and reasoning parts as they are produced.</li>
 *   <li>The terminal button launches {@code opencode attach
 *       http://127.0.0.1:4096 --session <id>} (see
 *       {@link CLIAgentManager#buildLaunchCommand}), so opening the CLI
 *       lands in the very same server-side session and conversation the
 *       GUI has been writing to — a bare attach would open a fresh,
 *       empty session instead.</li>
 *   <li>The server is a child process of the APP process (spawned via
 *       ProcessBuilder, not inside any terminal session), so activities
 *       and terminal sessions can come and go without touching it; it
 *       dies only with the app process itself, on a workspace change
 *       (deliberate restart), or if the serve process crashes — and the
 *       turn layer recovers from the last two (see {@link TurnWatchdog}).</li>
 *   <li>Stop aborts the current turn ({@code POST /session/:id/abort}); the
 *       server and the session stay alive for the next message.</li>
 * </ul>
 *
 * <p>All JSON handling goes through the tiny self-contained {@link MiniJson}
 * parser so the parsing/merging logic stays unit-testable on a plain JVM
 * (the codebase deliberately avoids org.json in tested code paths).</p>
 */
public class OpenCodeLink {

    public static final int PORT = 4096;
    public static final String BASE_URL = "http://127.0.0.1:" + PORT;

    /** How long to wait for a cold {@code opencode serve} to answer health checks. */
    private static final long SERVER_START_TIMEOUT_MS = 120_000;

    public interface Listener {
        /** Server is up and the shared session exists; the turn has been posted. */
        void onLinked(String sessionId);

        /** Latest cumulative snapshot of the assistant's text parts. */
        void onText(String snapshot);

        /** Latest cumulative snapshot of the assistant's reasoning parts. */
        void onReasoning(String snapshot);

        /** A tool part changed state (e.g. "edit running"). */
        void onTool(String label);

        /** The turn ended. {@code error} is null on success. */
        void onFinished(String error);

        /** The link itself is unavailable (server would not start, HTTP dead). */
        void onUnavailable(String reason);
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    // ---- Server process state (shared across activities) ----
    private static Process serverProcess;
    private static String serverWorkspace;
    private static volatile boolean serverUp;
    private static volatile long lastHealthOkMs;

    // ---- Session state ----
    private static String sessionId;
    private static String sessionWorkspace;

    // ---- Current turn state ----
    private volatile boolean turnCancelled;
    private volatile HttpURLConnection activeSse;
    private volatile String activeSessionId;

    /** True when the shared server answered a health check recently (cached, no I/O). */
    public static boolean isServerUp() {
        if (serverProcess != null && !serverProcess.isAlive()) {
            serverUp = false;
        }
        return serverUp;
    }

    /** The shared OpenCode session id, if one has been created. */
    public static String getSessionId() {
        return sessionId;
    }

    // ==================================================================
    // Server lifecycle
    // ==================================================================

    /**
     * The shell command that starts the shared server for {@code workspace}.
     * Pure string building (unit-testable); the caller wraps/runs it.
     */
    static String buildServeCommand(String workspace) {
        return "cd " + CLIAgentManager.singleQuote(workspace)
            + " && export PATH=\"$HOME/.opencode/bin:$PATH\""
            + " && export OPENCODE_CONFIG_CONTENT=\"{\\\"permission\\\":\\\"allow\\\"}\""
            + " && exec opencode serve --hostname 127.0.0.1 --port " + PORT;
    }

    /**
     * The host directory the server process starts in: the chat's selected
     * workspace when it exists (created if needed), else the fallback home —
     * the same selection {@code AgentRunner.run} makes for one-shot runs.
     * Pure and unit-testable.
     */
    static File chooseServerDir(String workspace, String fallbackHome) {
        File dir = new File(workspace == null ? fallbackHome : workspace);
        if (!dir.isDirectory()) dir.mkdirs();
        if (!dir.isDirectory()) {
            dir = new File(fallbackHome);
            if (!dir.isDirectory()) dir.mkdirs();
        }
        return dir;
    }

    private static synchronized boolean startServerLocked(String workspace) {
        killServerLocked();
        String bash = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(bash).exists()) return false;

        String inner = buildServeCommand(workspace);
        boolean inWorkshop = CLIAgentManager.isWorkshopAvailable();
        String effective = inWorkshop ? CLIAgentManager.wrapForWorkshop(inner) : inner;

        ProcessBuilder pb = new ProcessBuilder(bash, "-lc", effective);
        // Working directory, determined EXACTLY like the one-shot path
        // (AgentRunner.run): the host process starts IN the chat's selected
        // workspace, and proot-distro login carries that cwd into the
        // workshop (the terminal lands in the picked folder the same way —
        // its session cwd is the folder the user opened). This used to hard-
        // code TERMUX_HOME here, so the linked server always ran in the app
        // home no matter which folder the chat had selected, and files the
        // agent created landed in files/home instead of the user's folder.
        File dir = chooseServerDir(workspace, TermuxConstants.TERMUX_HOME_DIR_PATH);
        if (dir.isDirectory()) pb.directory(dir);
        pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/"
            + ":" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/applets");
        pb.environment().put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/lib");
        pb.environment().put("TMPDIR", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/tmp");
        pb.environment().put("TERM", "xterm-256color");
        pb.environment().put("LANG", "en_US.UTF-8");
        pb.redirectErrorStream(true);
        try {
            serverProcess = pb.start();
            serverWorkspace = workspace;
        } catch (Exception e) {
            serverProcess = null;
            return false;
        }
        // Drain server output so its pipe can never block it.
        final Process p = serverProcess;
        Thread drain = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                while (br.readLine() != null) {
                    // Discard: server logs are not chat content.
                }
            } catch (Exception ignored) {
            }
        }, "opencode-serve-drain");
        drain.setDaemon(true);
        drain.start();
        return true;
    }

    private static synchronized void killServerLocked() {
        if (serverProcess != null) {
            try {
                serverProcess.destroy();
            } catch (Exception ignored) {
            }
            serverProcess = null;
        }
        serverUp = false;
        sessionId = null;
        sessionWorkspace = null;
    }

    /** Synchronous health probe (GET /session answers 200 when the server is up). */
    static boolean probeHealthy() {
        try {
            HttpURLConnection c = open("GET", "/session", 4000, 4000);
            int code = c.getResponseCode();
            c.disconnect();
            if (code == 200) {
                serverUp = true;
                lastHealthOkMs = System.currentTimeMillis();
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Cached health: fresh probe result reused for a few seconds. */
    private static boolean healthyCached() {
        if (serverUp && System.currentTimeMillis() - lastHealthOkMs < 5000) return true;
        return probeHealthy();
    }

    // ==================================================================
    // HTTP plumbing
    // ==================================================================

    private static HttpURLConnection open(String method, String path,
                                          int connectTimeoutMs, int readTimeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(connectTimeoutMs);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    private static String requestBody(HttpURLConnection c, String jsonBody) throws Exception {
        if (jsonBody != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        java.io.InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
        }
        c.disconnect();
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + " " + sb.toString().trim());
        }
        return sb.toString();
    }

    private static String get(String path) throws Exception {
        return requestBody(open("GET", path, 8000, 30000), null);
    }

    private static String post(String path, String jsonBody) throws Exception {
        return requestBody(open("POST", path, 8000, 30000), jsonBody == null ? "{}" : jsonBody);
    }

    // ==================================================================
    // Session + turn
    // ==================================================================

    private String ensureSessionLocked() throws Exception {
        if (sessionId != null) {
            try {
                get("/session/" + sessionId);
                return sessionId;
            } catch (Exception e) {
                sessionId = null; // stale (server restarted) — create fresh below
            }
        }
        String resp = post("/session", "{}");
        Object parsed = MiniJson.parse(resp);
        String id = MiniJson.str(MiniJson.asMap(parsed), "id");
        if (id == null || id.isEmpty()) throw new Exception("Server did not return a session id");
        sessionId = id;
        return id;
    }

    /**
     * Send one GUI message into the shared live session, streaming the reply.
     * Runs entirely on a background thread; callbacks arrive on the main thread.
     *
     * @param modelId full {@code provider/model} id, or null for the CLI default
     */
    public void sendPrompt(final String workspace, final String prompt,
                           final String modelId, final Listener listener) {
        sendPrompt(workspace, prompt, modelId, null, listener);
    }

    /**
     * Variant-carrying form of {@link #sendPrompt(String, String, String, Listener)}.
     *
     * @param variant the model's own server variant name to request for this
     *                prompt (already resolved against the model's defined
     *                variants by the caller — see {@link #resolveVariant}),
     *                or null to send no variant field at all
     */
    public void sendPrompt(final String workspace, final String prompt,
                           final String modelId, final String variant,
                           final Listener listener) {
        turnCancelled = false;
        new Thread(() -> {
            // 1) Server up? (restart when the workspace changed under it)
            boolean workspaceChanged = serverWorkspace != null && !serverWorkspace.equals(workspace);
            if (workspaceChanged || !healthyCached()) {
                synchronized (OpenCodeLink.class) {
                    if (workspaceChanged || serverProcess == null || !serverProcess.isAlive()) {
                        if (!startServerLocked(workspace)) {
                            main.post(() -> listener.onUnavailable(
                                    "OpenCode server could not be started (bootstrap missing?)."));
                            return;
                        }
                    }
                }
                long deadline = System.currentTimeMillis() + SERVER_START_TIMEOUT_MS;
                boolean up = false;
                while (System.currentTimeMillis() < deadline) {
                    if (turnCancelled) return;
                    if (probeHealthy()) {
                        up = true;
                        break;
                    }
                    if (serverProcess != null && !serverProcess.isAlive()) break;
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!up) {
                    main.post(() -> listener.onUnavailable(
                            "OpenCode server did not come up in time."));
                    return;
                }
            }

            // 2) Shared session
            final String sid;
            try {
                synchronized (OpenCodeLink.class) {
                    sid = ensureSessionLocked();
                    sessionWorkspace = workspace;
                }
            } catch (Exception e) {
                main.post(() -> listener.onUnavailable("Could not create OpenCode session: " + e.getMessage()));
                return;
            }
            activeSessionId = sid;
            main.post(() -> listener.onLinked(sid));

            // 3) Stream the turn
            streamTurn(workspace, sid, prompt, modelId, variant, listener);
        }, "opencode-link").start();
    }

    /**
     * Duplicate-send guard (pure): an identical prompt re-sent while its
     * turn is still in flight, within {@code windowMs} of the original
     * dispatch, is a double-send (double-tap, keyboard + button double
     * fire), not a new message. After the turn finishes — or once the
     * window has passed — the same text is legitimate again.
     */
    public static boolean isDuplicateSend(String lastText, long lastMs, String newText, long nowMs,
                                          boolean turnInFlight, long windowMs) {
        if (!turnInFlight || lastText == null || newText == null) return false;
        if (!lastText.equals(newText)) return false;
        long dt = nowMs - lastMs;
        return dt >= 0 && dt <= windowMs;
    }

    /**
     * The terminal attach command for the shared server. {@code opencode
     * attach} accepts {@code --session <id>} ("Session ID to continue" —
     * OpenCode CLI docs, cli.mdx) — without it the TUI opens a brand-new
     * session and the GUI conversation is nowhere to be seen, which is
     * exactly what a phone test showed. The caller passes the CURRENT
     * linked session id at terminal-open time (it changes when the server
     * restarts and a new session is created).
     */
    static String buildAttachCommand(String sessionId) {
        String cmd = "opencode attach " + BASE_URL;
        if (sessionId != null && !sessionId.isEmpty()) {
            cmd += " --session " + sessionId;
        }
        return cmd;
    }

    /**
     * Turn-resilience policy (pure, unit-testable): what to do when the
     * SSE event stream ends without a finish event.
     *
     * <p>The previous watcher polled the health endpoint every 4 s DURING
     * the turn and declared "OpenCode server stopped responding" on the
     * FIRST failed probe — a single 4 s timeout against a busy proot
     * server in the middle of a tool-using turn killed the turn and threw
     * the reply away, even though the stream (and server) were fine. The
     * new policy: while the event stream is alive, the turn is never
     * timed out from the client side. Death is only concluded after the
     * stream has ended AND repeated probes have failed; then the server
     * is restarted ONCE and the turn recovered from the server's own
     * persisted session history.</p>
     */
    static class TurnWatchdog {
        enum Action {FINISH_FETCHED, RECONNECT, RESTART_AND_RECOVER, FAIL}

        static final int MAX_RECONNECTS = 10;

        /**
         * @param probeOk       health probe result after retries
         * @param sessionIdle   session status: TRUE idle, FALSE busy,
         *                      null when the status could not be read
         * @param processAlive  whether the spawned server process exists
         *                      and is alive (corroborating evidence only —
         *                      after repeated failed probes, a live-but-
         *                      wedged process gets the same one restart as
         *                      a dead one)
         * @param restartsUsed  server restarts already spent on this turn
         * @param reconnectsUsed SSE reconnects already spent on this turn
         */
        static Action decide(boolean probeOk, Boolean sessionIdle, boolean processAlive,
                             int restartsUsed, int reconnectsUsed) {
            if (probeOk) {
                if (sessionIdle != null && sessionIdle) return Action.FINISH_FETCHED;
                // Server answers; the session is busy (or its status is
                // unreadable): re-attach the event stream and keep going.
                // After many reconnects, settle for whatever the session
                // history holds rather than looping forever.
                return reconnectsUsed < MAX_RECONNECTS ? Action.RECONNECT : Action.FINISH_FETCHED;
            }
            // Repeated probes failed: the server is dead or wedged. One
            // automatic restart per turn, with recovery from the persisted
            // session; after that, tell the truth and stop.
            if (restartsUsed < 1) return Action.RESTART_AND_RECOVER;
            return Action.FAIL;
        }
    }

    private void streamTurn(final String workspace, final String sid, final String prompt,
                            final String modelId, final String variant,
                            final Listener listener) {
        final TurnState state = new TurnState();
        state.promptText = prompt;
        final Object finishLock = new Object();
        final boolean[] finished = {false};
        int restartsUsed = 0;
        int reconnectsUsed = 0;
        boolean promptPosted = false;

        while (true) {
            if (turnCancelled) {
                closeSse();
                return;
            }
            SseRun run = runSseOnce(sid, state, listener, finishLock, finished,
                    promptPosted ? null : prompt, modelId, variant);
            if (run == SseRun.PROMPT_FAILED) {
                closeSse();
                return; // finishOnce already delivered the error
            }
            promptPosted = true;
            synchronized (finishLock) {
                if (finished[0]) break;
            }
            if (turnCancelled) {
                closeSse();
                return;
            }

            // The event stream ended without a finish event. That alone
            // proves nothing (streams drop, idle events get lost): probe
            // for real, then let the watchdog policy decide.
            boolean probeOk = probeWithRetries();
            Boolean idle = probeOk ? isSessionIdleTri(sid) : null;
            boolean processAlive = serverProcess != null && serverProcess.isAlive();
            TurnWatchdog.Action action = TurnWatchdog.decide(
                    probeOk, idle, processAlive, restartsUsed, reconnectsUsed);
            switch (action) {
                case FINISH_FETCHED: {
                    emitRecoveredMessages(sid, state, listener);
                    finishOnce(listener, finishLock, finished, state.errorText);
                    closeSse();
                    return;
                }
                case RECONNECT: {
                    reconnectsUsed++;
                    // Heal any part updates missed while the stream was down.
                    emitRecoveredMessages(sid, state, listener);
                    sleepQuietly(1000);
                    continue;
                }
                case RESTART_AND_RECOVER: {
                    restartsUsed++;
                    boolean recovered = restartServerAndRecover(workspace, sid, state, listener);
                    if (recovered) {
                        main.post(() -> listener.onTool("♻️ server restarted — turn recovered"));
                        finishOnce(listener, finishLock, finished, null);
                    } else {
                        finishOnce(listener, finishLock, finished,
                                "OpenCode server stopped and was restarted, but that turn"
                                        + " could not be recovered — please send it again.");
                    }
                    closeSse();
                    return;
                }
                default: { // FAIL
                    finishOnce(listener, finishLock, finished,
                            state.errorText != null ? state.errorText
                                    : "Connection to OpenCode server was lost.");
                    closeSse();
                    return;
                }
            }
        }
        closeSse();
    }

    private enum SseRun {FINISHED, STREAM_ENDED, PROMPT_FAILED}

    /**
     * One lifetime of the SSE event stream: connect, (optionally) post the
     * prompt once the stream is attached, and relay events until the turn
     * finishes, the stream ends, or the turn is cancelled. While the
     * stream is alive this method NEVER gives up on the turn — a long
     * tool-using turn produces events at its own pace, and silence is not
     * death. The only side check is a lost-idle-event safety net: after
     * two minutes of total silence the session status is polled, and only
     * an explicit "idle" answer ends the run (the caller then finishes
     * from the fetched session history).
     */
    private SseRun runSseOnce(final String sid, final TurnState state, final Listener listener,
                              final Object finishLock, final boolean[] finished,
                              final String promptToPost, final String modelId,
                              final String variant) {
        final long[] lastEventMs = {System.currentTimeMillis()};
        Thread sseThread = new Thread(() -> {
            HttpURLConnection sse = null;
            try {
                sse = open("GET", "/event", 8000, 0);
                sse.setRequestProperty("Accept", "text/event-stream");
                activeSse = sse;
                int code = sse.getResponseCode();
                if (code != 200) throw new Exception("SSE HTTP " + code);
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(sse.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    StringBuilder data = new StringBuilder();
                    while ((line = br.readLine()) != null) {
                        if (turnCancelled) break;
                        if (line.isEmpty()) {
                            if (data.length() > 0) {
                                lastEventMs[0] = System.currentTimeMillis();
                                handleEvent(data.toString(), sid, state, listener, finishLock, finished);
                                data.setLength(0);
                                synchronized (finishLock) {
                                    if (finished[0]) break;
                                }
                            }
                        } else if (line.startsWith("data:")) {
                            data.append(line.substring(5).trim());
                        }
                    }
                }
            } catch (Exception ignored) {
                // Stream closed/errored: the caller decides the outcome
                // via probes + the watchdog, never from this alone.
            } finally {
                if (sse != null) sse.disconnect();
                if (activeSse == sse) activeSse = null;
            }
        }, "opencode-sse");
        sseThread.setDaemon(true);
        sseThread.start();

        if (promptToPost != null) {
            // Give the event stream a moment to attach before the prompt
            // lands, so the first streamed parts are not missed.
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return SseRun.STREAM_ENDED;
            }
            try {
                post("/session/" + sid + "/prompt_async",
                        buildPromptBody(promptToPost, modelId, variant));
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                    // Older server build without prompt_async: the
                    // synchronous message endpoint blocks until the turn
                    // completes — no streaming, but the turn still runs in
                    // the shared session.
                    syncSendFallback(sid, promptToPost, modelId, variant, listener, finishLock, finished);
                    return SseRun.FINISHED;
                }
                finishOnce(listener, finishLock, finished, "Failed to send message: " + e.getMessage());
                return SseRun.PROMPT_FAILED;
            }
        }

        long lastIdlePoll = 0;
        while (true) {
            synchronized (finishLock) {
                if (finished[0]) return SseRun.FINISHED;
            }
            if (turnCancelled) return SseRun.STREAM_ENDED;
            if (!sseThread.isAlive()) return SseRun.STREAM_ENDED;
            long now = System.currentTimeMillis();
            if (now - lastEventMs[0] > 120_000 && now - lastIdlePoll > 15_000) {
                lastIdlePoll = now;
                Boolean idle = isSessionIdleTri(sid);
                if (idle != null && idle) return SseRun.STREAM_ENDED;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return SseRun.STREAM_ENDED;
            }
        }
    }

    /** Health probe with retries: one failed probe proves nothing (the
     *  server is a proot process on a phone and can be briefly busy), so
     *  death is only ever concluded after several failures in a row. */
    private boolean probeWithRetries() {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (probeHealthy()) return true;
            if (attempt < 2) sleepQuietly(1500);
        }
        return false;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Restart the server once and try to recover the turn from the
     * session's persisted history. OpenCode stores sessions on disk (in
     * its data dir under the workshop home), so after a restart the same
     * session id normally still resolves and its messages — including the
     * interrupted turn's outcome, as far as it got — can be fetched.
     *
     * @return true when the session still exists and an assistant reply
     *         was recovered into the chat
     */
    private boolean restartServerAndRecover(String workspace, String sid,
                                            TurnState state, Listener listener) {
        synchronized (OpenCodeLink.class) {
            if (!startServerLocked(workspace)) return false;
        }
        long deadline = System.currentTimeMillis() + SERVER_START_TIMEOUT_MS;
        boolean up = false;
        while (System.currentTimeMillis() < deadline) {
            if (turnCancelled) return false;
            if (probeHealthy()) {
                up = true;
                break;
            }
            if (serverProcess != null && !serverProcess.isAlive()) break;
            sleepQuietly(1500);
        }
        if (!up) return false;
        try {
            get("/session/" + sid);
        } catch (Exception e) {
            return false; // session gone — the turn is unrecoverable
        }
        synchronized (OpenCodeLink.class) {
            sessionId = sid;
            sessionWorkspace = workspace;
        }
        return emitRecoveredMessages(sid, state, listener);
    }

    /**
     * Fetch the session's messages and emit the last turn's assistant
     * reply into the chat (used after a stream drop, a reconnect, and a
     * server restart). Only emits content that adds to what the stream
     * already showed, so a healthy turn is never double-rendered.
     *
     * @return true when an assistant reply was found in the history
     */
    private boolean emitRecoveredMessages(String sid, TurnState state, Listener listener) {
        String[] recovered;
        try {
            recovered = parseSessionMessages(get("/session/" + sid + "/message"));
        } catch (Exception e) {
            return false;
        }
        if (recovered == null) return false;
        String fetchedText = recovered[0];
        String fetchedReasoning = recovered[1];
        if (fetchedReasoning != null && !fetchedReasoning.isEmpty()
                && fetchedReasoning.length() >= state.reasoningSnapshot().length()
                && !fetchedReasoning.equals(state.reasoningSnapshot())) {
            final String s = fetchedReasoning;
            main.post(() -> listener.onReasoning(s));
        }
        String displayText = stripReasoningFromText(fetchedText, fetchedReasoning);
        if (displayText != null && !displayText.trim().isEmpty()
                && displayText.length() >= state.textSnapshot().length()
                && !displayText.equals(state.textSnapshot())) {
            final String s = displayText;
            main.post(() -> listener.onText(s));
        }
        return (fetchedText != null && !fetchedText.trim().isEmpty())
                || (fetchedReasoning != null && !fetchedReasoning.trim().isEmpty());
    }

    /**
     * Parse a {@code GET /session/{id}/message} response: an array of
     * {@code {info:{role,…}, parts:[…]}} messages. Returns
     * {@code {text, reasoning}} concatenating every assistant message
     * after the last user message (one turn can span several assistant
     * messages when tools are involved), or null when the history holds
     * no assistant content. Pure and unit-testable.
     */
    static String[] parseSessionMessages(String json) {
        if (json == null) return null;
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (Exception e) {
            return null;
        }
        List<Object> messages = MiniJson.asList(root);
        if (messages == null) return null;
        int start = 0;
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> m = MiniJson.asMap(messages.get(i));
            Map<String, Object> info = m == null ? null : MiniJson.asMap(m.get("info"));
            if (info != null && "user".equals(MiniJson.str(info, "role"))) {
                start = i + 1;
            }
        }
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        for (int i = start; i < messages.size(); i++) {
            Map<String, Object> m = MiniJson.asMap(messages.get(i));
            if (m == null) continue;
            Map<String, Object> info = MiniJson.asMap(m.get("info"));
            String role = info == null ? null : MiniJson.str(info, "role");
            if (role != null && !"assistant".equals(role)) continue;
            List<Object> parts = MiniJson.asList(m.get("parts"));
            if (parts == null) continue;
            for (Object o : parts) {
                Map<String, Object> p = MiniJson.asMap(o);
                if (p == null) continue;
                String txt = MiniJson.str(p, "text");
                if (txt == null) continue;
                String type = MiniJson.str(p, "type");
                if ("text".equals(type)) {
                    if (text.length() > 0) text.append('\n');
                    text.append(txt);
                } else if ("reasoning".equals(type)) {
                    if (reasoning.length() > 0) reasoning.append('\n');
                    reasoning.append(txt);
                }
            }
        }
        if (text.length() == 0 && reasoning.length() == 0) return null;
        return new String[]{text.toString(), reasoning.toString()};
    }

    /**
     * The JSON body for a prompt POST (shared by prompt_async and the sync
     * message endpoint). The {@code variant} field is the server API's
     * per-prompt reasoning-variant selector (SDK v2 SessionPromptAsyncData);
     * it is included ONLY when the caller resolved a variant name the
     * selected model actually defines — an unknown variant name is a
     * model-resolution error server-side, never something to guess at.
     */
    static String buildPromptBody(String prompt, String modelId, String variant) {
        StringBuilder body = new StringBuilder();
        body.append("{\"parts\":[{\"type\":\"text\",\"text\":")
                .append(MiniJson.quote(prompt)).append("}]");
        if (modelId != null && modelId.contains("/")) {
            int slash = modelId.indexOf('/');
            body.append(",\"model\":{\"providerID\":")
                    .append(MiniJson.quote(modelId.substring(0, slash)))
                    .append(",\"modelID\":")
                    .append(MiniJson.quote(modelId.substring(slash + 1)))
                    .append("}");
        }
        if (variant != null && !variant.isEmpty()) {
            body.append(",\"variant\":").append(MiniJson.quote(variant));
        }
        body.append("}");
        return body.toString();
    }

    /** Synchronous-turn fallback for servers without prompt_async. */
    private void syncSendFallback(String sid, String prompt, String modelId, String variant,
                                  Listener listener, Object finishLock, boolean[] finished) {
        try {
            HttpURLConnection c = open("POST", "/session/" + sid + "/message", 8000, 0);
            String resp = requestBody(c, buildPromptBody(prompt, modelId, variant));
            Object parsed = MiniJson.parse(resp);
            Map<String, Object> r = MiniJson.asMap(parsed);
            List<Object> parts = r == null ? null : MiniJson.asList(r.get("parts"));
            StringBuilder text = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            if (parts != null) {
                for (Object o : parts) {
                    Map<String, Object> p = MiniJson.asMap(o);
                    if (p == null) continue;
                    String t = MiniJson.str(p, "type");
                    String txt = MiniJson.str(p, "text");
                    if (txt == null) continue;
                    if ("text".equals(t)) {
                        if (text.length() > 0) text.append('\n');
                        text.append(txt);
                    } else if ("reasoning".equals(t)) {
                        if (reasoning.length() > 0) reasoning.append('\n');
                        reasoning.append(txt);
                    }
                }
            }
            if (reasoning.length() > 0) {
                final String s = reasoning.toString();
                main.post(() -> listener.onReasoning(s));
            }
            // Same dedupe as the streaming path: interleaved-reasoning
            // models can mirror their monologue into the text parts too.
            String displayText = stripReasoningFromText(
                    text.toString(), reasoning.toString());
            if (displayText != null && !displayText.trim().isEmpty()) {
                final String s = displayText;
                main.post(() -> listener.onText(s));
            }
            Map<String, Object> info = r == null ? null : MiniJson.asMap(r.get("info"));
            Object err = info == null ? null : info.get("error");
            finishOnce(listener, finishLock, finished,
                    err != null ? extractErrorText(err) : null);
        } catch (Exception e) {
            finishOnce(listener, finishLock, finished, "Failed to send message: " + e.getMessage());
        }
    }

    /** Session status as a tri-state: TRUE idle, FALSE busy, null when
     *  the status could not be read at all (server unreachable) — callers
     *  must not treat "unknown" as either of the other two. */
    private Boolean isSessionIdleTri(String sid) {
        try {
            String resp = get("/session/status");
            Object parsed = MiniJson.parse(resp);
            Map<String, Object> byId = MiniJson.asMap(parsed);
            Map<String, Object> st = byId == null ? null : MiniJson.asMap(byId.get(sid));
            if (st == null) return Boolean.TRUE; // not listed = not busy
            String type = MiniJson.str(st, "type");
            return type == null || type.equals("idle") ? Boolean.TRUE : Boolean.FALSE;
        } catch (Exception e) {
            return null;
        }
    }

    private void handleEvent(String json, String sid, TurnState state, Listener listener,
                             Object finishLock, boolean[] finished) {
        Event ev = parseEvent(json);
        if (ev == null || ev.type == null) return;
        if (ev.sessionID != null && !ev.sessionID.equals(sid)) return;
        switch (ev.type) {
            case "message.updated": {
                // Role bookkeeping: parts belonging to the USER message (the
                // prompt itself streams back over the same bus) must never
                // land in the agent's reply bubble.
                if (ev.messageID != null && ev.messageRole != null) {
                    emitSnapshots(state.classifyMessage(ev.messageID, ev.messageRole), listener);
                }
                break;
            }
            case "message.part.updated":
            case "message.part.delta": {
                if (ev.partType == null || ev.partID == null) break;
                if (ev.partType.equals("tool")) {
                    final String label = ev.toolLabel;
                    if (label != null && !label.equals(state.lastToolLabel)) {
                        state.lastToolLabel = label;
                        main.post(() -> listener.onTool(label));
                    }
                } else if (ev.partType.equals("text") || ev.partType.equals("reasoning")) {
                    emitSnapshots(state.applyPartEvent(ev), listener);
                }
                break;
            }
            case "permission.asked": {
                if (ev.permissionID != null) {
                    final String pid = ev.permissionID;
                    new Thread(() -> replyPermission(sid, pid), "opencode-perm").start();
                }
                break;
            }
            case "session.error": {
                state.errorText = ev.errorText != null ? ev.errorText : "OpenCode reported an error.";
                emitSnapshots(state.flushPendingAsAssistant(), listener);
                finishOnce(listener, finishLock, finished, state.errorText);
                break;
            }
            case "session.status": {
                if ("idle".equals(ev.statusType)) {
                    emitSnapshots(state.flushPendingAsAssistant(), listener);
                    finishOnce(listener, finishLock, finished, state.errorText);
                }
                break;
            }
            case "session.idle": {
                emitSnapshots(state.flushPendingAsAssistant(), listener);
                finishOnce(listener, finishLock, finished, state.errorText);
                break;
            }
            default:
                break;
        }
    }

    /** Post whichever of the text/reasoning snapshots changed (if any). */
    private void emitSnapshots(String[] snaps, Listener listener) {
        if (snaps == null) return;
        if (snaps[0] != null) {
            final String s = snaps[0];
            main.post(() -> listener.onText(s));
        }
        if (snaps[1] != null) {
            final String s = snaps[1];
            main.post(() -> listener.onReasoning(s));
        }
    }

    private void finishOnce(Listener listener, Object finishLock, boolean[] finished, String error) {
        synchronized (finishLock) {
            if (finished[0]) return;
            finished[0] = true;
        }
        if (!turnCancelled) {
            main.post(() -> listener.onFinished(error));
        }
    }

    private void replyPermission(String sid, String permissionId) {
        try {
            post("/session/" + sid + "/permissions/" + permissionId, "{\"response\":\"always\"}");
        } catch (Exception e1) {
            try {
                post("/permission/" + permissionId + "/reply", "{\"reply\":\"always\"}");
            } catch (Exception ignored) {
                // Server was also started with permission:"allow" config, so a
                // failed reply should not stall the turn in practice.
            }
        }
    }

    /** Abort the current turn (Stop button). Server + session stay alive. */
    public void abortTurn() {
        turnCancelled = true;
        final String sid = activeSessionId;
        closeSse();
        if (sid != null) {
            new Thread(() -> {
                try {
                    post("/session/" + sid + "/abort", "{}");
                } catch (Exception ignored) {
                }
            }, "opencode-abort").start();
        }
    }

    private void closeSse() {
        HttpURLConnection sse = activeSse;
        if (sse != null) {
            try {
                sse.disconnect();
            } catch (Exception ignored) {
            }
            activeSse = null;
        }
    }

    /**
     * Remove reasoning content from the reply text. Interleaved-reasoning
     * models (big-pickle among them: models.dev lists it with
     * {@code interleaved.field = "reasoning_content"}) can deliver the same
     * monologue twice — once as a proper reasoning part and once mirrored
     * inside the text parts — which is how the reply bubble ended up
     * containing the full Thought Process verbatim on a real device, with
     * the Thought bubble showing it a second time. The reply must be the
     * final text only, so any block of the reasoning that also appears in
     * the text is cut out of the text:
     *
     * <ul>
     *   <li>the whole reasoning block as a prefix of the text (the observed
     *       big-pickle shape: text = monologue + final answer),</li>
     *   <li>the whole reasoning block contained anywhere in the text,</li>
     *   <li>a leading run of text lines that duplicate reasoning lines
     *       (partial overlap while both are still streaming).</li>
     * </ul>
     */
    static String stripReasoningFromText(String text, String reasoning) {
        if (text == null) return null;
        if (reasoning == null) return text;
        String r = reasoning.trim();
        if (r.isEmpty()) return text;
        String t = text;
        int idx = t.indexOf(r);
        if (idx >= 0) {
            String cut = (t.substring(0, idx) + t.substring(idx + r.length()))
                    .replaceAll("(?m)^[ \\t]+$", "");
            // Tidy the seam: collapse 3+ newlines left by the removal.
            cut = cut.replaceAll("\\n{3,}", "\n\n");
            return cut.trim();
        }
        // Partial overlap: drop leading text lines that duplicate a
        // reasoning line (order-independent set match, non-empty lines only).
        java.util.Set<String> reasoningLines = new java.util.HashSet<>();
        for (String line : r.split("\n")) {
            String lt = line.trim();
            if (!lt.isEmpty()) reasoningLines.add(lt);
        }
        if (reasoningLines.isEmpty()) return t;
        String[] lines = t.split("\n", -1);
        int start = 0;
        while (start < lines.length) {
            String lt = lines[start].trim();
            if (lt.isEmpty() || reasoningLines.contains(lt)) {
                start++;
            } else {
                break;
            }
        }
        if (start == 0) return t;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /** Mutable per-turn streaming state. */
    static class TurnState {
        final LinkedHashMap<String, String> textParts = new LinkedHashMap<>();
        final LinkedHashMap<String, String> reasoningParts = new LinkedHashMap<>();
        /** messageID → "user" / "assistant", learned from message.updated. */
        final Map<String, String> messageRoles = new LinkedHashMap<>();
        /** partID → "text" / "reasoning", learned from snapshot events. */
        final Map<String, String> partTypes = new LinkedHashMap<>();
        /** Part events that cannot be routed yet (unclassified message, or
         *  a delta whose part's type has not been seen). */
        final List<Event> pendingParts = new ArrayList<>();
        String lastToolLabel;
        String errorText;
        /** The prompt sent this turn (user-part echo guard). */
        String promptText;
        private String lastTextSnapshot = "";
        private String lastReasoningSnapshot = "";

        /**
         * Record a message's role and replay/drop any buffered parts that
         * belong to it. Returns {textSnapshot, reasoningSnapshot} with null
         * for whichever did not change (same convention as
         * {@link #applyPartEvent}).
         */
        String[] classifyMessage(String messageID, String role) {
            if (messageID == null || role == null) return null;
            String prev = messageRoles.put(messageID, role);
            if (role.equals(prev)) return null;
            if (pendingParts.isEmpty()) return null;
            return drainPending() ? changedSnapshots() : null;
        }

        /**
         * Apply one text/reasoning part event, honouring message roles:
         * parts of the USER message (the prompt streams back over the same
         * event bus) are dropped, parts of an as-yet-unclassified message
         * are buffered until its message.updated arrives, and a text part
         * that is verbatim the prompt is dropped as a belt-and-braces echo
         * guard. Returns {textSnapshot, reasoningSnapshot} (null = unchanged).
         */
        String[] applyPartEvent(Event ev) {
            if (ev == null || ev.partID == null || ev.partType == null) return null;
            if (ev.messageID != null) {
                String role = messageRoles.get(ev.messageID);
                if ("user".equals(role)) return null;
                if (role == null) {
                    pendingParts.add(ev);
                    return null;
                }
            }
            if (isPromptEcho(ev)) return null;
            if (!tryApplyPart(ev)) {
                // A delta whose part type is not known yet: buffer it; the
                // part's snapshot event (which carries the real type) will
                // let drainPending route it. Routing it by the delta's own
                // "field" would be wrong — the field is "text" even for
                // reasoning parts, which is one way reasoning used to leak
                // into the reply bubble.
                pendingParts.add(ev);
                return null;
            }
            drainPending();
            return changedSnapshots();
        }

        /** Flush still-unclassified parts as assistant content (turn end). */
        String[] flushPendingAsAssistant() {
            if (pendingParts.isEmpty()) return null;
            for (Event e : pendingParts) {
                if (e.messageID != null && !messageRoles.containsKey(e.messageID)) {
                    messageRoles.put(e.messageID, "assistant");
                }
            }
            boolean changed = drainPending();
            // Anything still pending is a delta whose part type never
            // arrived; its part's snapshot carries the full text anyway,
            // so dropping it loses nothing.
            pendingParts.clear();
            return changed ? changedSnapshots() : null;
        }

        private boolean isPromptEcho(Event ev) {
            return "text".equals(ev.partType) && ev.text != null && promptText != null
                    && ev.text.trim().equals(promptText.trim());
        }

        /** Replay buffered events until no more of them can be routed. */
        private boolean drainPending() {
            boolean anyChanged = false;
            boolean progress = true;
            while (progress) {
                progress = false;
                java.util.Iterator<Event> it = pendingParts.iterator();
                while (it.hasNext()) {
                    Event e = it.next();
                    String role = e.messageID == null ? "assistant" : messageRoles.get(e.messageID);
                    if ("user".equals(role) || isPromptEcho(e)) {
                        it.remove();
                        progress = true;
                        continue;
                    }
                    if (role == null) continue; // message still unclassified
                    if (tryApplyPart(e)) {
                        it.remove();
                        anyChanged = true;
                        progress = true;
                    }
                }
            }
            return anyChanged;
        }

        private boolean tryApplyPart(Event ev) {
            if (ev.text != null) {
                // Snapshot event: the part object carries the REAL type.
                if (!"text".equals(ev.partType) && !"reasoning".equals(ev.partType)) return false;
                partTypes.put(ev.partID, ev.partType);
                mapFor(ev.partType).put(ev.partID, ev.text);
                return true;
            }
            if (ev.delta != null) {
                String type = partTypes.get(ev.partID);
                if (type == null) return false;
                LinkedHashMap<String, String> map = mapFor(type);
                String prevText = map.get(ev.partID);
                map.put(ev.partID, (prevText == null ? "" : prevText) + ev.delta);
                return true;
            }
            return false;
        }

        private LinkedHashMap<String, String> mapFor(String type) {
            return "reasoning".equals(type) ? reasoningParts : textParts;
        }

        /** Current display text: text parts with reasoning overlap removed. */
        String textSnapshot() {
            return stripReasoningFromText(
                    joinParts(textParts), joinParts(reasoningParts));
        }

        String reasoningSnapshot() {
            return joinParts(reasoningParts);
        }

        private String[] changedSnapshots() {
            String t = textSnapshot();
            String r = reasoningSnapshot();
            String[] out = new String[2];
            if (!t.equals(lastTextSnapshot)) {
                out[0] = t;
                lastTextSnapshot = t;
            }
            if (!r.equals(lastReasoningSnapshot)) {
                out[1] = r;
                lastReasoningSnapshot = r;
            }
            return (out[0] == null && out[1] == null) ? null : out;
        }

        static String joinParts(LinkedHashMap<String, String> parts) {
            StringBuilder sb = new StringBuilder();
            for (String v : parts.values()) {
                if (v == null || v.isEmpty()) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(v);
            }
            return sb.toString();
        }
    }

    // ==================================================================
    // Event parsing (pure, unit-testable)
    // ==================================================================

    static class Event {
        String type;
        String sessionID;
        String partID;
        String partType;
        String text;
        String delta;
        String toolLabel;
        String statusType;
        String errorText;
        String permissionID;
        /** Owning message id (part.messageID / delta properties / message.updated info). */
        String messageID;
        /** For message.updated: the message's role ("user" / "assistant"). */
        String messageRole;
    }

    static Event parseEvent(String json) {
        if (json == null) return null;
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (Exception e) {
            return null;
        }
        Map<String, Object> r = MiniJson.asMap(root);
        if (r == null) return null;
        Event ev = new Event();
        ev.type = MiniJson.str(r, "type");
        Map<String, Object> props = MiniJson.asMap(r.get("properties"));
        if (props == null) return ev;
        ev.sessionID = MiniJson.str(props, "sessionID");
        ev.delta = MiniJson.str(props, "delta");
        ev.messageID = MiniJson.str(props, "messageID");
        // message.updated carries the message itself under "info".
        Map<String, Object> info = MiniJson.asMap(props.get("info"));
        if (info != null) {
            ev.messageID = MiniJson.str(info, "id");
            ev.messageRole = MiniJson.str(info, "role");
            if (ev.sessionID == null) ev.sessionID = MiniJson.str(info, "sessionID");
        }
        // message.part.delta carries partID (and field) at the properties
        // level instead of inside a part object.
        ev.partID = MiniJson.str(props, "partID");
        if ("text".equals(MiniJson.str(props, "field"))) {
            ev.partType = "text";
        }
        Map<String, Object> part = MiniJson.asMap(props.get("part"));
        if (part != null) {
            ev.partID = MiniJson.str(part, "id");
            ev.partType = MiniJson.str(part, "type");
            ev.text = MiniJson.str(part, "text");
            String partMessage = MiniJson.str(part, "messageID");
            if (partMessage != null) ev.messageID = partMessage;
            if (ev.sessionID == null) ev.sessionID = MiniJson.str(part, "sessionID");
            if ("tool".equals(ev.partType)) {
                String tool = MiniJson.str(part, "tool");
                Map<String, Object> st = MiniJson.asMap(part.get("state"));
                String status = st == null ? null : MiniJson.str(st, "status");
                ev.toolLabel = tool == null ? null : (status == null ? tool : tool + " " + status);
            }
        }
        Map<String, Object> status = MiniJson.asMap(props.get("status"));
        if (status != null) ev.statusType = MiniJson.str(status, "type");
        if (ev.type != null && ev.type.equals("permission.asked")) {
            ev.permissionID = MiniJson.str(props, "id");
        }
        Object err = props.get("error");
        if (err != null) {
            ev.errorText = extractErrorText(err);
        }
        return ev;
    }

    private static String extractErrorText(Object err) {
        if (err instanceof String) return (String) err;
        Map<String, Object> e = MiniJson.asMap(err);
        if (e == null) return null;
        String direct = MiniJson.str(e, "message");
        if (direct != null) return direct;
        Map<String, Object> data = MiniJson.asMap(e.get("data"));
        if (data != null) {
            String m = MiniJson.str(data, "message");
            if (m != null) return m;
        }
        String name = MiniJson.str(e, "name");
        return name != null ? name : "Unknown error";
    }

    // ==================================================================
    // Full model catalogue (Workstream C)
    // ==================================================================

    /**
     * The fullest OpenCode model list available: the connected providers'
     * models first (the CLI's own live list), then the complete models.dev
     * catalogue — the same public catalogue OpenCode's TUI "View all
     * providers" list is built from. Best-effort: returns whatever could be
     * fetched (possibly just the connected list, possibly empty).
     *
     * @param connectedIds ids from {@code opencode models} (may be empty)
     */
    public static List<String> fetchFullCatalogueSync(List<String> connectedIds) {
        List<String> catalogue = Collections.emptyList();
        // Source 1: the linked server's /provider (CLI's own view of models.dev + connections)
        if (healthyCached()) {
            try {
                catalogue = parseProviderResponse(get("/provider"));
            } catch (Exception ignored) {
            }
        }
        // Source 2: models.dev public catalogue (what the TUI itself reads)
        if (catalogue.isEmpty()) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://models.dev/api.json").openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(30000);
                c.setRequestProperty("Accept", "application/json");
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                c.disconnect();
                catalogue = parseModelsDevCatalogue(sb.toString());
            } catch (Exception ignored) {
            }
        }
        return mergeModelLists(connectedIds, catalogue);
    }

    /**
     * Parse the server's {@code GET /provider} response:
     * {@code { "all": [ { "id": "anthropic", "models": { "claude-…": {...} } }, … ],
     *          "connected": [ "opencode", … ] }}.
     * Connected providers come first, then the rest, ids as {@code provider/model}.
     */
    static List<String> parseProviderResponse(String json) {
        List<String> out = new ArrayList<>();
        if (json == null) return out;
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (Exception e) {
            return out;
        }
        Map<String, Object> r = MiniJson.asMap(root);
        if (r == null) return out;
        List<Object> all = MiniJson.asList(r.get("all"));
        if (all == null) return out;
        List<Object> connectedRaw = MiniJson.asList(r.get("connected"));
        List<String> connected = new ArrayList<>();
        if (connectedRaw != null) {
            for (Object o : connectedRaw) {
                if (o instanceof String) connected.add((String) o);
            }
        }
        // Connected providers first, then the rest — stable within each group.
        List<Map<String, Object>> providers = new ArrayList<>();
        for (Object o : all) {
            Map<String, Object> p = MiniJson.asMap(o);
            if (p != null) providers.add(p);
        }
        providers.sort((a, b) -> {
            String ia = MiniJson.str(a, "id");
            String ib = MiniJson.str(b, "id");
            boolean ca = connected.contains(ia);
            boolean cb = connected.contains(ib);
            if (ca != cb) return ca ? -1 : 1;
            return String.valueOf(ia).compareTo(String.valueOf(ib));
        });
        for (Map<String, Object> p : providers) {
            String pid = MiniJson.str(p, "id");
            Map<String, Object> models = MiniJson.asMap(p.get("models"));
            if (pid == null || models == null) continue;
            List<String> modelIds = new ArrayList<>(models.keySet());
            Collections.sort(modelIds);
            for (String mid : modelIds) {
                Map<String, Object> model = MiniJson.asMap(models.get(mid));
                if (model != null && "deprecated".equals(MiniJson.str(model, "status"))) continue;
                String full = pid + "/" + mid;
                if (!out.contains(full)) out.add(full);
            }
        }
        return out;
    }

    /**
     * Parse https://models.dev/api.json: a top-level object keyed by provider
     * id, each with a {@code models} object keyed by model id. Deprecated
     * models are skipped (OpenCode's own lists leave them out too).
     */
    static List<String> parseModelsDevCatalogue(String json) {
        List<String> out = new ArrayList<>();
        if (json == null) return out;
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (Exception e) {
            return out;
        }
        Map<String, Object> providers = MiniJson.asMap(root);
        if (providers == null) return out;
        List<String> providerIds = new ArrayList<>(providers.keySet());
        Collections.sort(providerIds);
        for (String pid : providerIds) {
            Map<String, Object> p = MiniJson.asMap(providers.get(pid));
            if (p == null) continue;
            Map<String, Object> models = MiniJson.asMap(p.get("models"));
            if (models == null) continue;
            List<String> modelIds = new ArrayList<>(models.keySet());
            Collections.sort(modelIds);
            for (String mid : modelIds) {
                Map<String, Object> model = MiniJson.asMap(models.get(mid));
                if (model != null && "deprecated".equals(MiniJson.str(model, "status"))) continue;
                out.add(pid + "/" + mid);
            }
        }
        return out;
    }

    // ==================================================================
    // Thinking variants (Bug: OpenCode thinking levels in linked mode)
    // ==================================================================

    /**
     * Map an app thinking level ("Off", "Low (2k)", "Medium (8k)",
     * "High (16k)", "Max (32k)") to the server variant name to send, given
     * the variant names the selected model actually defines.
     *
     * <p>Returns null — send NO variant field — when the level is Off, when
     * the model's variants are unknown ({@code modelVariants == null}), or
     * when the model does not define the mapped name. An unknown variant
     * name is a model-resolution error in OpenCode, so guessing (e.g.
     * sending "medium" to a model that only defines low/high/max) is never
     * an option; the caller surfaces the not-applied case to the user.</p>
     */
    static String resolveVariant(String level, List<String> modelVariants) {
        if (level == null || modelVariants == null) return null;
        String name;
        if (level.startsWith("Low")) name = "low";
        else if (level.startsWith("Medium")) name = "medium";
        else if (level.startsWith("High")) name = "high";
        else if (level.startsWith("Max")) name = "max";
        else return null; // Off / unrecognised
        return modelVariants.contains(name) ? name : null;
    }

    /**
     * Parse per-model variant names out of the server's {@code GET /provider}
     * response: each model object may carry a {@code variants} map whose
     * keys are the variant names the server will accept for that model
     * (entries flagged {@code "disabled": true} are excluded). Keys of the
     * returned map are full {@code provider/model} ids; a model with no
     * variants maps to an empty list (known-none, distinct from absent =
     * not present in the response at all).
     */
    static Map<String, List<String>> parseProviderVariants(String json) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (json == null) return out;
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (Exception e) {
            return out;
        }
        Map<String, Object> r = MiniJson.asMap(root);
        if (r == null) return out;
        List<Object> all = MiniJson.asList(r.get("all"));
        if (all == null) return out;
        for (Object o : all) {
            Map<String, Object> p = MiniJson.asMap(o);
            if (p == null) continue;
            String pid = MiniJson.str(p, "id");
            Map<String, Object> models = MiniJson.asMap(p.get("models"));
            if (pid == null || models == null) continue;
            for (Map.Entry<String, Object> e : models.entrySet()) {
                Map<String, Object> model = MiniJson.asMap(e.getValue());
                List<String> names = new ArrayList<>();
                Map<String, Object> variants = model == null ? null : MiniJson.asMap(model.get("variants"));
                if (variants != null) {
                    for (Map.Entry<String, Object> v : variants.entrySet()) {
                        Map<String, Object> vo = MiniJson.asMap(v.getValue());
                        if (vo != null && Boolean.TRUE.equals(vo.get("disabled"))) continue;
                        names.add(v.getKey());
                    }
                }
                Collections.sort(names);
                out.put(pid + "/" + e.getKey(), names);
            }
        }
        return out;
    }

    /**
     * Derive per-model variant names from the models.dev catalogue — the
     * same derivation the server documents for itself ("Reasoning controls
     * the model accepts, used to build its variants"): an
     * {@code effort}-type reasoning option contributes its values as
     * variant names. A model whose reasoning_options contain no effort
     * values (empty list, toggle-only, budget-only) maps to an empty list:
     * no effort variant names can be derived for it. big-pickle is exactly
     * this case (reasoning_options: [] in models.dev).
     */
    static Map<String, List<String>> parseModelsDevVariants(String json) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (json == null) return out;
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (Exception e) {
            return out;
        }
        Map<String, Object> providers = MiniJson.asMap(root);
        if (providers == null) return out;
        for (Map.Entry<String, Object> pe : providers.entrySet()) {
            Map<String, Object> p = MiniJson.asMap(pe.getValue());
            if (p == null) continue;
            Map<String, Object> models = MiniJson.asMap(p.get("models"));
            if (models == null) continue;
            for (Map.Entry<String, Object> me : models.entrySet()) {
                Map<String, Object> model = MiniJson.asMap(me.getValue());
                if (model == null) continue;
                List<String> names = new ArrayList<>();
                List<Object> options = MiniJson.asList(model.get("reasoning_options"));
                if (options != null) {
                    for (Object o : options) {
                        Map<String, Object> opt = MiniJson.asMap(o);
                        if (opt == null) continue;
                        if (!"effort".equals(MiniJson.str(opt, "type"))) continue;
                        List<Object> values = MiniJson.asList(opt.get("values"));
                        if (values == null) continue;
                        for (Object v : values) {
                            if (v instanceof String && !names.contains(v)) names.add((String) v);
                        }
                    }
                }
                Collections.sort(names);
                out.put(pe.getKey() + "/" + me.getKey(), names);
            }
        }
        return out;
    }

    /**
     * Best-effort fetch of the per-model variant map: the linked server's
     * {@code /provider} first (the names the server itself will accept),
     * else the models.dev derivation. Empty map when neither is reachable.
     */
    public static Map<String, List<String>> fetchVariantMapSync() {
        if (healthyCached()) {
            try {
                Map<String, List<String>> fromServer = parseProviderVariants(get("/provider"));
                if (!fromServer.isEmpty()) return fromServer;
            } catch (Exception ignored) {
            }
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://models.dev/api.json").openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Accept", "application/json");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            c.disconnect();
            return parseModelsDevVariants(sb.toString());
        } catch (Exception ignored) {
        }
        return Collections.emptyMap();
    }

    /** Connected ids first (in their own order), then catalogue ids not already present. */
    static List<String> mergeModelLists(List<String> connected, List<String> catalogue) {
        List<String> out = new ArrayList<>();
        if (connected != null) {
            for (String id : connected) {
                if (id != null && !id.isEmpty() && !out.contains(id)) out.add(id);
            }
        }
        if (catalogue != null) {
            for (String id : catalogue) {
                if (id != null && !id.isEmpty() && !out.contains(id)) out.add(id);
            }
        }
        return out;
    }

    // ==================================================================
    // MiniJson — tiny self-contained JSON parser (pure Java, testable)
    // ==================================================================

    static class MiniJson {
        private final String s;
        private int i;

        private MiniJson(String s) {
            this.s = s == null ? "" : s;
        }

        static Object parse(String text) {
            MiniJson p = new MiniJson(text);
            p.ws();
            Object v = p.value();
            p.ws();
            return v;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> asMap(Object o) {
            return o instanceof Map ? (Map<String, Object>) o : null;
        }

        @SuppressWarnings("unchecked")
        static List<Object> asList(Object o) {
            return o instanceof List ? (List<Object>) o : null;
        }

        static String str(Map<String, Object> map, String key) {
            if (map == null) return null;
            Object v = map.get(key);
            return v instanceof String ? (String) v : null;
        }

        static String quote(String value) {
            if (value == null) return "\"\"";
            StringBuilder sb = new StringBuilder(value.length() + 2);
            sb.append('"');
            for (int k = 0; k < value.length(); k++) {
                char c = value.charAt(k);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            sb.append('"');
            return sb.toString();
        }

        private void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        private Object value() {
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        private void expect(String literal) {
            if (!s.startsWith(literal, i)) throw new IllegalArgumentException("bad literal at " + i);
            i += literal.length();
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++; // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return map;
            }
            while (true) {
                ws();
                String key = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw new IllegalArgumentException("expected :");
                i++;
                ws();
                map.put(key, value());
                ws();
                if (i >= s.length()) throw new IllegalArgumentException("unterminated object");
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                } else if (c == '}') {
                    i++;
                    return map;
                } else {
                    throw new IllegalArgumentException("expected , or }");
                }
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            i++; // [
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return list;
            }
            while (true) {
                ws();
                list.add(value());
                ws();
                if (i >= s.length()) throw new IllegalArgumentException("unterminated array");
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                } else if (c == ']') {
                    i++;
                    return list;
                } else {
                    throw new IllegalArgumentException("expected , or ]");
                }
            }
        }

        private String string() {
            if (i >= s.length() || s.charAt(i) != '"') throw new IllegalArgumentException("expected string");
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (i + 4 <= s.length()) {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            }
                            break;
                        default: sb.append(e); break;
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw new IllegalArgumentException("bad value at " + i);
            String raw = s.substring(start, i);
            try {
                return Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                return raw;
            }
        }
    }
}
