package com.termux.app.terminal;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.util.LinkedHashSet;

/**
 * Automatic opening of agent sign-in URLs.
 *
 * <p>Several agent CLIs print an OAuth URL and wait ("Your browser should
 * open automatically. If not: https://accounts.google.com/o/oauth2/auth?…")
 * — in a phone terminal nothing opens by itself, and the user had to copy a
 * multi-line wrapped URL by hand. This helper watches agent output (the
 * terminal transcript and the GUI chat run output), recognises REAL
 * authentication endpoints, and opens the first sighting of each unique URL
 * in the device browser with an {@code ACTION_VIEW} intent.</p>
 *
 * <p>Detection is deliberately narrow: only known sign-in endpoints match
 * (see {@link #isAuthUrl}). Ordinary links an agent prints — docs, repos,
 * search results — never trigger a browser pop. Each exact URL opens at
 * most once per app run, so a transcript rescan or a repeated print of the
 * same URL cannot spam the browser.</p>
 *
 * <p>Note on Android background limits: {@code startActivity} from a
 * backgrounded app can be blocked/deferred by Android 10+. Both call sites
 * run while the app is in the foreground (the user is looking at the
 * terminal or the chat when the URL appears), which is exactly the case
 * that matters; if a launch is ever refused, the URL remains visible and
 * tappable/copyable in the output as before.</p>
 */
public class AuthUrlOpener {

    /** URLs already opened this app run (exact string match), bounded. */
    private static final LinkedHashSet<String> openedUrls = new LinkedHashSet<>();
    private static final int MAX_REMEMBERED_URLS = 32;

    private AuthUrlOpener() {
    }

    // ------------------------------------------------------------------
    // Detection (pure, unit-testable)
    // ------------------------------------------------------------------

    /**
     * Find the first COMPLETE authentication URL in {@code text}, or null.
     *
     * <p>Extraction is wrap-aware. The terminal emulator's joined-lines
     * transcript re-joins lines the TERMINAL wrapped, but a CLI that
     * hard-wraps its own print (real newlines at its own layout width,
     * narrower than the terminal) defeats that join — the old extractor
     * then stopped at the first newline and returned only the first
     * segment of a long OAuth URL ({@code …/auth?access_type=offline…}),
     * which the browser opened and Google rejected with "Required
     * parameter is missing: response_type" (reproduced on the JVM with
     * the real emulator classes + a live check of Google's error: it
     * reports response_type first, even when client_id is also missing).
     * So a candidate may now cross a newline when the crossing is
     * provably part of the same printed URL (see {@link #extractCandidate}),
     * and a Google OAuth candidate is only returned once it carries the
     * parameters the flow cannot work without — a partial print (the text
     * simply has not all arrived yet) yields null, so nothing broken is
     * opened and nothing is recorded in the once-per-URL set; the next
     * scan, with the full text, returns the whole URL.</p>
     */
    public static String findAuthUrl(String text) {
        if (text == null) return null;
        int from = 0;
        while (from < text.length()) {
            int start = indexOfScheme(text, from);
            if (start < 0) return null;
            Candidate c = extractCandidate(text, start);
            if (c == null) {
                from = start + 8;
                continue;
            }
            String candidate = stripTrailingJunk(c.text);
            if (isAuthUrl(candidate) && isCompleteAuthUrl(candidate)) return candidate;
            from = Math.max(c.endIndex, start + 8); // keep scanning after this candidate
        }
        return null;
    }

    /** One extracted URL candidate plus where its raw span ended. */
    static class Candidate {
        String text;
        int endIndex;
    }

    /**
     * Extract the URL candidate starting at {@code start}, crossing
     * newlines that are hard wraps inside the printed URL. A newline is
     * crossed only when the evidence says the URL continues on the next
     * line:
     *
     * <ul>
     *   <li>the candidate is a Google OAuth URL still missing one of its
     *       required parameters (the parameters arrive in order, so while
     *       one is missing the print cannot be finished), or</li>
     *   <li>the next line is a "pure" URL segment — at least 20 characters
     *       of nothing but URL characters (a wrapped segment looks like
     *       this; a prose line, which contains spaces or is short, does
     *       not), or</li>
     *   <li>the segment just ended is as long as the wrap width already
     *       established by earlier crossings (wrapped segments of one
     *       print share a width; the final segment is shorter, which is
     *       what stops the crossing at the true end of the URL).</li>
     * </ul>
     *
     * A blank line is never crossed unless required parameters are still
     * missing.
     */
    static Candidate extractCandidate(String text, int start) {
        StringBuilder sb = new StringBuilder();
        int i = start;
        int segStart = start;
        int wrapWidth = -1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isUrlChar(c)) {
                sb.append(c);
                i++;
                continue;
            }
            if (c == '\r' || c == '\n') {
                int j = i;
                int newlines = 0;
                while (j < text.length() && (text.charAt(j) == '\r' || text.charAt(j) == '\n')) {
                    if (text.charAt(j) == '\n') newlines++;
                    j++;
                }
                if (j >= text.length()) break;
                if (!isUrlChar(text.charAt(j))) break;
                boolean missingKeys = !hasRequiredKeys(sb);
                int segLen = i - segStart;
                boolean cross = missingKeys
                        || (newlines < 2 && nextLineIsPureUrlSegment(text, j))
                        || (newlines < 2 && wrapWidth > 0 && segLen >= wrapWidth - 1);
                if (!cross) break;
                if (segLen > wrapWidth) wrapWidth = segLen;
                i = j;
                segStart = j;
                continue;
            }
            break;
        }
        if (sb.length() == 0) return null;
        Candidate out = new Candidate();
        out.text = sb.toString();
        out.endIndex = i;
        return out;
    }

    /** True when the line starting at {@code from} is one solid run of at
     *  least 20 URL characters — the shape of a hard-wrapped URL segment. */
    private static boolean nextLineIsPureUrlSegment(String text, int from) {
        int n = 0;
        int i = from;
        while (i < text.length() && isUrlChar(text.charAt(i))) {
            n++;
            i++;
        }
        if (n < 20) return false;
        // The run must end the line (newline or end of text), not trail
        // into prose on the same line.
        return i >= text.length() || text.charAt(i) == '\n' || text.charAt(i) == '\r';
    }

    /**
     * Whether a Google OAuth candidate already carries every parameter
     * the Antigravity-style flow needs. Non-Google candidates always
     * count as "not missing" (their completeness is judged by
     * {@link #isCompleteAuthUrl} returning true for them).
     */
    private static boolean hasRequiredKeys(CharSequence candidate) {
        String s = candidate.toString();
        if (!s.startsWith("https://accounts.google.com/o/oauth2/")
                && !s.startsWith("http://accounts.google.com/o/oauth2/")) {
            return true;
        }
        return s.contains("response_type=") && s.contains("client_id=")
                && s.contains("redirect_uri=") && s.contains("state=");
    }

    /**
     * True when an auth URL is complete enough to open. For Google OAuth
     * a partial URL is worse than none at all — Google hard-fails the
     * sign-in ("Required parameter is missing: response_type") instead of
     * letting the user retry — so the required parameters must all be
     * present. Other endpoints' URLs are short and self-contained; the
     * host/path match is enough.
     */
    static boolean isCompleteAuthUrl(String url) {
        if (url == null) return false;
        if (url.startsWith("https://accounts.google.com/o/oauth2/")
                || url.startsWith("http://accounts.google.com/o/oauth2/")) {
            return hasRequiredKeys(url);
        }
        return true;
    }

    /** Prose punctuation glued to the end ("(see https://…)", a quoting
     *  apostrophe, "… .") is not part of the URL. */
    private static String stripTrailingJunk(String candidate) {
        while (candidate.length() > 8) {
            char last = candidate.charAt(candidate.length() - 1);
            if (last == '.' || last == ',' || last == ')' || last == ';'
                    || last == '\'' || last == '"' || last == '>') {
                candidate = candidate.substring(0, candidate.length() - 1);
            } else {
                break;
            }
        }
        return candidate;
    }

    private static int indexOfScheme(String text, int from) {
        int a = text.indexOf("https://", from);
        int b = text.indexOf("http://", from);
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    private static boolean isUrlChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "-._~:/?#[]@!$&'()*+,;=%".indexOf(c) >= 0;
    }

    /**
     * True only for known sign-in endpoints. The Google OAuth shape is the
     * one evidenced on a real device (Antigravity CLI prints
     * {@code accounts.google.com/o/oauth2/auth?…}); the rest are the other
     * agents' standard OAuth/device-login endpoints. Everything else —
     * including ordinary google.com / github.com links — returns false.
     */
    static boolean isAuthUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        int schemeEnd = lower.indexOf("://");
        if (schemeEnd < 0) return false;
        String rest = lower.substring(schemeEnd + 3);
        int slash = rest.indexOf('/');
        String host = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "" : rest.substring(slash);
        // Strip any port.
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);

        switch (host) {
            case "accounts.google.com":
                // Antigravity / Google sign-in (device-evidenced).
                return path.startsWith("/o/oauth2/");
            case "github.com":
                // GitHub device login (Copilot CLI / gh auth).
                return path.startsWith("/login/device");
            case "claude.ai":
            case "console.anthropic.com":
                // Claude Code OAuth.
                return path.contains("/oauth");
            case "auth.openai.com":
                // Codex / ChatGPT sign-in.
                return path.contains("/oauth") || path.contains("/authorize");
            case "chatgpt.com":
                return path.startsWith("/auth") || path.contains("/oauth");
            case "signin.aws.amazon.com":
                // AWS sign-in (Kiro).
                return true;
            case "accounts.x.ai":
            case "auth.x.ai":
                // Grok (xAI) sign-in.
                return true;
            default:
                // AWS-hosted OAuth endpoints (Kiro / IAM Identity Center).
                if (host.endsWith(".amazonaws.com")) {
                    return path.contains("oauth") || path.contains("authorize");
                }
                return false;
        }
    }

    // ------------------------------------------------------------------
    // Opening (once per unique URL)
    // ------------------------------------------------------------------

    /** Test/diagnostic hook: has this exact URL already been opened? */
    static synchronized boolean wasOpened(String url) {
        return openedUrls.contains(url);
    }

    /** Forget all remembered URLs (tests / explicit re-login flows). */
    static synchronized void resetForTest() {
        openedUrls.clear();
    }

    /**
     * If {@code text} contains an auth URL that has not been opened yet,
     * open it in the device browser and toast why. Returns true when a
     * browser launch was attempted.
     */
    public static synchronized boolean openAuthUrlIfPresent(Context context, String text,
                                                            String sourceLabel) {
        if (context == null) return false;
        String url = findAuthUrl(text);
        if (url == null) return false;
        if (openedUrls.contains(url)) return false;
        openedUrls.add(url);
        while (openedUrls.size() > MAX_REMEMBERED_URLS) {
            java.util.Iterator<String> it = openedUrls.iterator();
            it.next();
            it.remove();
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Toast.makeText(context,
                    "🔐 Opening " + (sourceLabel == null ? "agent" : sourceLabel)
                            + " sign-in in your browser…",
                    Toast.LENGTH_LONG).show();
            return true;
        } catch (Exception e) {
            // No browser / launch refused: the URL stays visible in the
            // output for manual copy, exactly as before this feature.
            return false;
        }
    }
}
