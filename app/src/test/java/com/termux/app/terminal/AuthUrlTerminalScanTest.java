package com.termux.app.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Reproduction/regression tests for the terminal auth-URL scan: the URL is
 * fed through the REAL terminal emulator (the same classes the app ships),
 * rendered the ways a TUI login screen renders it, and then scanned with
 * the EXACT pipeline {@code TermuxTerminalSessionActivityClient} uses —
 * {@code getTranscriptTextWithFullLinesJoined()} → last-12000 tail →
 * {@link AuthUrlOpener#findAuthUrl}.
 *
 * <p>The URL is the one from the 2026-11-25 20:16 device screenshot
 * (Antigravity CLI login screen): all parameters present, including
 * {@code response_type=code} and the long {@code scope} list, ending in
 * {@code state=SW5zDpAKAORPhLzs7nv_2Q}. On the device the browser never
 * opened on this screen.</p>
 */
public class AuthUrlTerminalScanTest {

    static final String SCREENSHOT_URL =
            "https://accounts.google.com/o/oauth2/auth?access_type=offline"
            + "&client_id=1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
            + "&code_challenge=v3yo6oSxz2logxWvFy5SCtC9rkIJNMuiJng0UFx2kJII"
            + "&code_challenge_method=S256&prompt=consent"
            + "&redirect_uri=https%3A%2F%2Fantigravity.google%2Foauth-callback"
            + "&response_type=code"
            + "&scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fcloud-platform"
            + "+https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fuserinfo.email"
            + "+https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fuserinfo.profile"
            + "+https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fcclog"
            + "+https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fexperimentsandconfigs"
            + "+https%3A%2F%2Fwww.googleapis.com%2Fauth%2Faicode+openid"
            + "&state=SW5zDpAKAORPhLzs7nv_2Q";

    private static class StubOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) { }
        @Override public void titleChanged(String oldTitle, String newTitle) { }
        @Override public void onCopyTextToClipboard(String text) { }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { }
        @Override public void onColorsChanged() { }
    }

    private static TerminalEmulator emulator(int cols, int rows) {
        return new TerminalEmulator(new StubOutput(), cols, rows, 10, 10, rows * 4, null);
    }

    private static void feed(TerminalEmulator emu, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        emu.append(bytes, bytes.length);
    }

    /** The exact AUTO-scan pipeline of TermuxTerminalSessionActivityClient
     *  (60000-char tail since the session-sync/login fix round — the old
     *  12000-char tail could sever a URL printed into a long session). */
    private static String scanLikeTheApp(TerminalEmulator emu) {
        String transcript = emu.getScreen().getTranscriptTextWithFullLinesJoined();
        if (transcript == null || transcript.isEmpty()) return null;
        String tail = transcript.length() > 60000
                ? transcript.substring(transcript.length() - 60000) : transcript;
        return AuthUrlOpener.findAuthUrl(tail);
    }

    /** The manual Sign-in button's pipeline: FULL transcript, no tail cut. */
    private static String scanFullTranscript(TerminalEmulator emu) {
        String transcript = emu.getScreen().getTranscriptTextWithFullLinesJoined();
        if (transcript == null || transcript.isEmpty()) return null;
        return AuthUrlOpener.findAuthUrl(transcript);
    }

    private static String loginPageText() {
        return "Your browser should open automatically. If not:\r\n"
                + SCREENSHOT_URL + "\r\n"
                + "\r\n"
                + "Copy and paste the URL or click on the link below:\r\n"
                + "-> Click here to authenticate\r\n"
                + "\r\n"
                + "If you aren't automatically redirected, paste the authorization code below:\r\n"
                + "Authorization code...\r\n"
                + "shift+up/down  Navigate\r\n";
    }

    /** Alt screen (a TUI login page) with the page printed as flowing text;
     *  the emulator itself wraps the long URL at the screen width. */
    @Test
    public void altScreenFlowingPrintYieldsFullUrl() {
        for (int cols : new int[]{100, 140}) {
            TerminalEmulator emu = emulator(cols, 50);
            feed(emu, "\u001b[?1049h" + loginPageText());
            assertEquals("cols=" + cols, SCREENSHOT_URL, scanLikeTheApp(emu));
        }
    }

    /** Main screen, same flowing print. */
    @Test
    public void mainScreenFlowingPrintYieldsFullUrl() {
        for (int cols : new int[]{100, 140}) {
            TerminalEmulator emu = emulator(cols, 50);
            feed(emu, loginPageText());
            assertEquals("cols=" + cols, SCREENSHOT_URL, scanLikeTheApp(emu));
        }
    }

    /** Alt screen, but the TUI paints each visual row with explicit cursor
     *  addressing (no emulator wrap flags): prose rows and URL chunks are
     *  written row by row, exactly how an Ink-style renderer repaints. */
    @Test
    public void altScreenCursorAddressedRowsYieldFullUrl() {
        for (int cols : new int[]{100, 140}) {
            TerminalEmulator emu = emulator(cols, 50);
            StringBuilder sb = new StringBuilder("\u001b[?1049h");
            int row = 1;
            sb.append("\u001b[").append(row++).append(";1H")
                    .append("Your browser should open automatically. If not:");
            // URL painted in full-width chunks, one cursor move per chunk.
            for (int i = 0; i < SCREENSHOT_URL.length(); i += cols) {
                String chunk = SCREENSHOT_URL.substring(i, Math.min(SCREENSHOT_URL.length(), i + cols));
                sb.append("\u001b[").append(row++).append(";1H").append(chunk);
            }
            sb.append("\u001b[").append(row++).append(";1H")
                    .append("Copy and paste the URL or click on the link below:");
            sb.append("\u001b[").append(row++).append(";1H")
                    .append("-> Click here to authenticate");
            sb.append("\u001b[").append(row).append(";1H")
                    .append("Authorization code...");
            feed(emu, sb.toString());
            assertEquals("cols=" + cols, SCREENSHOT_URL, scanLikeTheApp(emu));
        }
    }

    /** The severing shape the old pipeline missed on real devices: the
     *  login print lands in the transcript, and THEN more than 12000
     *  chars of further output scroll past it (a busy TUI / long session
     *  history). Under the old 12000-char tail the URL's start was cut
     *  off; the widened 60000-char auto-scan tail and the manual
     *  full-transcript scan must both still find the complete URL. */
    @Test
    public void loginPrintBuriedUnderLaterOutputYieldsFullUrl() {
        // Generous transcript capacity so nothing scrolls out of the buffer.
        TerminalEmulator emu = new TerminalEmulator(new StubOutput(), 100, 50, 10, 10, 4000, null);
        feed(emu, loginPageText());
        StringBuilder filler = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            filler.append("agent session output line ").append(i)
                    .append(" — working, streaming, redrawing the working screen…\r\n");
        }
        assertTrue(filler.length() > 12000);
        feed(emu, filler.toString());
        assertEquals(SCREENSHOT_URL, scanLikeTheApp(emu));
        assertEquals(SCREENSHOT_URL, scanFullTranscript(emu));
    }
}
