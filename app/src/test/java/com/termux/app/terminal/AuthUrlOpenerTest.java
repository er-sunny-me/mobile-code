package com.termux.app.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link AuthUrlOpener}'s detector: real sign-in endpoints must
 * be found (including the exact Google OAuth shape an Antigravity login
 * printed on a real device), and ordinary links in agent output must never
 * trigger a browser pop.
 */
public class AuthUrlOpenerTest {

    @Test
    public void detectsGoogleOAuthUrlFromAntigravityOutput() {
        // Shape copied from the real device: agy prints the intro line, then
        // the bare OAuth URL (redirect_uri points back at antigravity).
        String out = "Your browser should open automatically. If not:\n"
                + "https://accounts.google.com/o/oauth2/auth?access_type=offline"
                + "&client_id=1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
                + "&code_challenge=t7m6mwSad1OTuKmiSmt3Ua7CozVzdjjFuZ6IrIjuy1I"
                + "&code_challenge_method=S256&prompt=consent"
                + "&redirect_uri=https%3A%2F%2Fantigravity.google%2Foauth-callback"
                + "&response_type=code&scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fcloud-platform"
                + "&state=zxZTDKozPwohPwQtWrZdeg\n"
                + "\nCopy and paste the URL or click on the link below:";
        String found = AuthUrlOpener.findAuthUrl(out);
        assertEquals("https://accounts.google.com/o/oauth2/auth?access_type=offline"
                + "&client_id=1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
                + "&code_challenge=t7m6mwSad1OTuKmiSmt3Ua7CozVzdjjFuZ6IrIjuy1I"
                + "&code_challenge_method=S256&prompt=consent"
                + "&redirect_uri=https%3A%2F%2Fantigravity.google%2Foauth-callback"
                + "&response_type=code&scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fcloud-platform"
                + "&state=zxZTDKozPwohPwQtWrZdeg", found);
    }

    @Test
    public void detectsOtherAgentsLoginEndpoints() {
        assertEquals("https://github.com/login/device",
                AuthUrlOpener.findAuthUrl("Open https://github.com/login/device and enter the code"));
        assertEquals("https://claude.ai/oauth/authorize?code=true&client_id=abc",
                AuthUrlOpener.findAuthUrl("Visit: https://claude.ai/oauth/authorize?code=true&client_id=abc"));
        assertTrue(AuthUrlOpener.findAuthUrl(
                "https://auth.openai.com/oauth/authorize?client_id=x") != null);
        assertTrue(AuthUrlOpener.findAuthUrl(
                "https://signin.aws.amazon.com/signin?redirect_uri=x") != null);
    }

    @Test
    public void ordinaryUrlsNeverTrigger() {
        assertNull(AuthUrlOpener.findAuthUrl("See https://example.com/docs for details"));
        assertNull(AuthUrlOpener.findAuthUrl("Search: https://www.google.com/search?q=opencode"));
        assertNull(AuthUrlOpener.findAuthUrl("Repo: https://github.com/sst/opencode"));
        assertNull(AuthUrlOpener.findAuthUrl("Catalogue: https://models.dev/api.json"));
        assertNull(AuthUrlOpener.findAuthUrl("Homepage: https://accounts.google.com/"));
        assertNull(AuthUrlOpener.findAuthUrl("No links in this output at all."));
        assertNull(AuthUrlOpener.findAuthUrl(""));
        assertNull(AuthUrlOpener.findAuthUrl(null));
    }

    @Test
    public void skipsNonAuthUrlsAndFindsTheAuthOne() {
        String url = "https://accounts.google.com/o/oauth2/auth?client_id=x&response_type=code"
                + "&redirect_uri=https%3A%2F%2Fexample.com%2Fcb&state=abc";
        String out = "Docs at https://opencode.ai/docs and then sign in: " + url + " please";
        assertEquals(url, AuthUrlOpener.findAuthUrl(out));
    }

    // ------------------------------------------------------------------
    // Hard-wrapped prints (the 2026-11-25 phone failure)
    //
    // A CLI that hard-wraps its own print (real newlines at its own
    // layout width) defeats the terminal transcript's line join, and the
    // old extractor returned just the first segment — which the browser
    // opened and Google rejected with "Required parameter is missing:
    // response_type" (that error fires even when client_id is ALSO
    // missing; verified live). The full Antigravity URL below is the
    // exact shape from the device screenshot, multi-scope and all.
    // ------------------------------------------------------------------

    private static final String FULL_OAUTH_URL =
            "https://accounts.google.com/o/oauth2/auth?access_type=offline"
            + "&client_id=1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
            + "&code_challenge=t7m6mwSad1OTuKmiSmt3Ua7CozVzdjjFuZ6IrIjuy1I"
            + "&code_challenge_method=S256&prompt=consent"
            + "&redirect_uri=https%3A%2F%2Fantigravity.google%2Foauth-callback"
            + "&response_type=code"
            + "&scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fcloud-platform"
            + "+https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fuserinfo.email"
            + "+https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fuserinfo.profile"
            + "+https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fcclog"
            + "+https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fexperimentsandconfigs"
            + "+https%3A%2F%2Fwww.googleapis.com%2Fauth%2Faicode+openid"
            + "&state=zxZTDKozPwohPwQtWrZdeg";

    /** Insert a newline every {@code width} chars, like a hard-wrapping CLI. */
    private static String hardWrap(String s, int width) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i += width) {
            if (i > 0) sb.append('\n');
            sb.append(s, i, Math.min(s.length(), i + width));
        }
        return sb.toString();
    }

    private static String agyOutput(String urlBlock) {
        return "Your browser should open automatically. If not:\n"
                + urlBlock
                + "\n\nCopy and paste the URL or click on the link below:\n"
                + "-> Click here to authenticate\n";
    }

    @Test
    public void hardWrappedAt48ReconstructsByteIdentical() {
        String found = AuthUrlOpener.findAuthUrl(agyOutput(hardWrap(FULL_OAUTH_URL, 48)));
        assertEquals(FULL_OAUTH_URL, found);
    }

    @Test
    public void hardWrappedAt57ReconstructsByteIdentical() {
        // 57 = the wrap width in the device screenshot, where the state
        // value itself is split across two lines.
        String found = AuthUrlOpener.findAuthUrl(agyOutput(hardWrap(FULL_OAUTH_URL, 57)));
        assertEquals(FULL_OAUTH_URL, found);
    }

    @Test
    public void partialPrintIsNotReturned() {
        // Only the first chunk of the wrapped print has arrived (the scan
        // runs while the CLI is still printing): the candidate is missing
        // response_type & friends, so NOTHING may be returned — opening
        // this is exactly what produced Google's 400 on the device.
        String partial = agyOutput(hardWrap(FULL_OAUTH_URL, 48)).substring(0, 320);
        assertNull(AuthUrlOpener.findAuthUrl(partial));
        // …and once the full print is there, the complete URL is found
        // (a partial was never recorded anywhere, so nothing blocks it).
        assertEquals(FULL_OAUTH_URL,
                AuthUrlOpener.findAuthUrl(agyOutput(hardWrap(FULL_OAUTH_URL, 48))));
    }

    @Test
    public void completeUrlFollowedByProseIsNotGlued() {
        // The line after a complete single-line URL starts with a letter;
        // extraction must stop at the newline, not glue the prose on.
        assertEquals(FULL_OAUTH_URL, AuthUrlOpener.findAuthUrl(agyOutput(FULL_OAUTH_URL)));
    }

    @Test
    public void quotingApostropheIsStripped() {
        assertEquals("https://github.com/login/device",
                AuthUrlOpener.findAuthUrl("Open 'https://github.com/login/device' to continue"));
    }

    @Test
    public void googleUrlMissingRequiredParamsIsNotReturned() {
        // The exact truncated shape the old code opened on the device.
        assertNull(AuthUrlOpener.findAuthUrl(
                "Sign in: https://accounts.google.com/o/oauth2/auth?access_type=offline"));
        assertNull(AuthUrlOpener.findAuthUrl(
                "Sign in: https://accounts.google.com/o/oauth2/auth?access_type=offline"
                        + "&client_id=1071006060591-x.apps.googleusercontent.com&prompt=consent"));
    }

    // ---------------- Assessment (Sign-in button states) ----------------

    @Test
    public void assessCompleteWhenFullUrlPresent() {
        AuthUrlOpener.AuthAssessment a = AuthUrlOpener.assessAuthText(agyOutput(FULL_OAUTH_URL));
        assertEquals(AuthUrlOpener.AuthTextState.COMPLETE, a.state);
        assertEquals(FULL_OAUTH_URL, a.url);
    }

    @Test
    public void assessPartialWhenUrlStillPrinting() {
        // Only the first half of the OAuth print has arrived: an auth
        // candidate exists but fails the completeness gate.
        String partial = "Your browser should open automatically. If not:\n"
                + "https://accounts.google.com/o/oauth2/auth?access_type=offline"
                + "&client_id=1071006060591-x.apps.googleusercontent.com";
        AuthUrlOpener.AuthAssessment a = AuthUrlOpener.assessAuthText(partial);
        assertEquals(AuthUrlOpener.AuthTextState.PARTIAL, a.state);
        assertNull(a.url);
    }

    @Test
    public void assessPartialOnBareAuthHostMention() {
        // The host shows up (e.g. inside a sentence the CLI printed)
        // without any extractable candidate: still PARTIAL, so the
        // button reports "incomplete" instead of "nothing there".
        AuthUrlOpener.AuthAssessment a = AuthUrlOpener.assessAuthText(
                "Redirecting via accounts.google.com for sign-in…");
        assertEquals(AuthUrlOpener.AuthTextState.PARTIAL, a.state);
    }

    @Test
    public void assessNoneForOrdinaryOutput() {
        assertEquals(AuthUrlOpener.AuthTextState.NONE,
                AuthUrlOpener.assessAuthText("Build finished. See https://example.com/docs").state);
        assertEquals(AuthUrlOpener.AuthTextState.NONE,
                AuthUrlOpener.assessAuthText("").state);
        assertEquals(AuthUrlOpener.AuthTextState.NONE,
                AuthUrlOpener.assessAuthText(null).state);
    }

    @Test
    public void authHostHintDetection() {
        assertTrue(AuthUrlOpener.containsAuthHostHint("go to accounts.google.com now"));
        assertTrue(AuthUrlOpener.containsAuthHostHint("open github.com/login/device please"));
        assertTrue(AuthUrlOpener.containsAuthHostHint("auth.openai.com redirect"));
        assertTrue(!AuthUrlOpener.containsAuthHostHint("just github.com and google.com links"));
        assertTrue(!AuthUrlOpener.containsAuthHostHint(null));
    }

    @Test
    public void findAuthUrlWorksBeyond12kOfFiller() {
        // The manual Sign-in path scans the FULL transcript: a complete
        // URL sitting behind more than the old 12000-char auto-scan tail
        // must still be found by the opener itself (no internal cut).
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append("workshop boot log line ").append(i)
                    .append(" — installing packages, please wait…\n");
        }
        assertTrue(sb.length() > 12000);
        sb.append("Your browser should open automatically. If not:\n")
                .append(FULL_OAUTH_URL).append('\n');
        assertEquals(FULL_OAUTH_URL, AuthUrlOpener.findAuthUrl(sb.toString()));
        assertEquals(AuthUrlOpener.AuthTextState.COMPLETE,
                AuthUrlOpener.assessAuthText(sb.toString()).state);
    }

    @Test
    public void hardWrappedWithIndentationReconstructsFullUrl() {
        StringBuilder indented = new StringBuilder();
        for (int i = 0; i < FULL_OAUTH_URL.length(); i += 52) {
            if (i > 0) indented.append("\n  ");
            indented.append(FULL_OAUTH_URL, i, Math.min(FULL_OAUTH_URL.length(), i + 52));
        }
        assertEquals(FULL_OAUTH_URL, AuthUrlOpener.findAuthUrl(agyOutput(indented.toString())));
    }
}
