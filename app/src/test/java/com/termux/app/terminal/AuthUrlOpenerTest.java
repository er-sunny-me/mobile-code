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
}
