package com.termux.app.browser;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.build.AndroidBuildConfig;
import com.termux.app.build.AndroidBuildManager;
import com.termux.app.build.BuildLogDialog;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;

/**
 * Built-in Android WebView browser inside Xyz.IDE for human browsing and localhost preview,
 * paired with Ubuntu PRoot Playwright setup for AI agent browser automation.
 */
public class BrowserActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "url";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private WebView webView;
    private EditText etUrl;
    private ProgressBar progressBar;
    private View consolePanel;
    private ScrollView consoleScroll;
    private TextView tvConsole;
    private TextView btnConsoleToggle;
    private TextView tvPlaywrightPill;
    private ImageButton btnBack;
    private ImageButton btnForward;

    private final StringBuilder consoleBuffer = new StringBuilder();
    private int errorCount = 0;
    private boolean desktopMode = false;
    private String mobileUa;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);

        webView = findViewById(R.id.wv_browser);
        etUrl = findViewById(R.id.et_browser_url);
        progressBar = findViewById(R.id.pb_browser_load);
        consolePanel = findViewById(R.id.panel_browser_console);
        consoleScroll = findViewById(R.id.scroll_browser_console);
        tvConsole = findViewById(R.id.tv_browser_console);
        btnConsoleToggle = findViewById(R.id.btn_browser_console);
        tvPlaywrightPill = findViewById(R.id.tv_playwright_pill);
        btnBack = findViewById(R.id.btn_browser_back);
        btnForward = findViewById(R.id.btn_browser_forward);

        configureWebView();
        wireActions();
        refreshPlaywrightPill();

        String initial = getIntent() != null ? getIntent().getStringExtra(EXTRA_URL) : null;
        loadInput(initial != null && !initial.isEmpty() ? initial : "https://www.google.com");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        mobileUa = settings.getUserAgentString();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (etUrl != null && !etUrl.hasFocus()) {
                    etUrl.setText(url);
                }
                if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
                updateNavButtons();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (etUrl != null && !etUrl.hasFocus()) {
                    etUrl.setText(url);
                }
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                updateNavButtons();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame() && error != null) {
                    appendConsole("NET ERROR", error.getDescription() + " (" + request.getUrl() + ")", true);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme)) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                } catch (Exception ignored) {
                    return false;
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar == null) return;
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                if (consoleMessage != null) {
                    boolean isErr = consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR;
                    appendConsole(consoleMessage.messageLevel().name(),
                            consoleMessage.message() + " (L" + consoleMessage.lineNumber() + ")",
                            isErr);
                }
                return super.onConsoleMessage(consoleMessage);
            }
        });
    }

    private void wireActions() {
        btnBack.setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
        });
        btnForward.setOnClickListener(v -> {
            if (webView.canGoForward()) webView.goForward();
        });
        findViewById(R.id.btn_browser_reload).setOnClickListener(v -> webView.reload());
        findViewById(R.id.btn_browser_close).setOnClickListener(v -> finish());

        etUrl.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                loadInput(etUrl.getText().toString());
                etUrl.clearFocus();
                return true;
            }
            return false;
        });

        btnConsoleToggle.setOnClickListener(v -> {
            boolean show = consolePanel.getVisibility() != View.VISIBLE;
            consolePanel.setVisibility(show ? View.VISIBLE : View.GONE);
            btnConsoleToggle.setTextColor(show ? 0xFF38BDF8 : (errorCount > 0 ? 0xFFF87171 : 0xFF9CA3AF));
        });

        findViewById(R.id.btn_console_clear).setOnClickListener(v -> {
            consoleBuffer.setLength(0);
            errorCount = 0;
            tvConsole.setText("Console cleared.\n");
            btnConsoleToggle.setText(">_");
            btnConsoleToggle.setTextColor(0xFF9CA3AF);
        });

        tvPlaywrightPill.setOnClickListener(v -> {
            if (BrowserAutomationConfig.isPlaywrightReady(TermuxConstants.TERMUX_PREFIX_DIR_PATH)) {
                Toast.makeText(this,
                        "Playwright Ready inside Ubuntu PRoot (xyz-playwright check <url>)",
                        Toast.LENGTH_LONG).show();
            } else {
                startPlaywrightInstall();
            }
        });

        findViewById(R.id.btn_browser_more).setOnClickListener(this::showMoreMenu);

        bindQuickChip(R.id.chip_localhost_3000, "http://127.0.0.1:3000");
        bindQuickChip(R.id.chip_localhost_5173, "http://127.0.0.1:5173");
        bindQuickChip(R.id.chip_localhost_8080, "http://127.0.0.1:8080");
        bindQuickChip(R.id.chip_google, "https://www.google.com");
        bindQuickChip(R.id.chip_github, "https://github.com");
    }

    private void bindQuickChip(int id, String targetUrl) {
        View chip = findViewById(id);
        if (chip != null) {
            chip.setOnClickListener(v -> loadInput(targetUrl));
        }
    }

    private void showMoreMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, desktopMode ? "Switch to Mobile View" : "Switch to Desktop Site");
        popup.getMenu().add(0, 2, 1, "Open in External Chrome");
        popup.getMenu().add(0, 3, 2, "Reinstall / Update Playwright (AI)");
        popup.getMenu().add(0, 4, 3, "Clear Browser Cache");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    desktopMode = !desktopMode;
                    webView.getSettings().setUserAgentString(desktopMode ? DESKTOP_UA : mobileUa);
                    webView.reload();
                    return true;
                case 2:
                    String cur = webView.getUrl();
                    if (cur != null && !cur.isEmpty()) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(cur)));
                    }
                    return true;
                case 3:
                    startPlaywrightInstall();
                    return true;
                case 4:
                    webView.clearCache(true);
                    Toast.makeText(this, "Browser cache cleared", Toast.LENGTH_SHORT).show();
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    private void startPlaywrightInstall() {
        AndroidBuildManager mgr = new AndroidBuildManager(this);
        String cmd = BrowserAutomationConfig.buildPlaywrightInstallCommand(
                TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        BuildLogDialog.runPlaywrightSetup(
                this,
                mgr,
                cmd,
                new File(TermuxConstants.TERMUX_HOME_DIR_PATH),
                () -> {
                    refreshPlaywrightPill();
                    syncWorkspaceBrowserDocs(mgr);
                });
    }

    private void syncWorkspaceBrowserDocs(AndroidBuildManager mgr) {
        new Thread(() -> {
            try {
                SharedPreferences wsPrefs = getSharedPreferences("ZeroCodePrefs", MODE_PRIVATE);
                String ws = wsPrefs.getString("workspace_path", TermuxConstants.TERMUX_HOME_DIR_PATH);
                if (ws != null) {
                    AndroidBuildManager.ToolchainStatus local = mgr.checkLocalToolchain();
                    AndroidBuildConfig.syncWorkspaceAgentDocs(
                            new File(ws),
                            TermuxConstants.TERMUX_PREFIX_DIR_PATH,
                            mgr.getBuildMode(),
                            local.isReady(),
                            mgr.getCloudSummary());
                }
            } catch (Exception ignored) {
            }
        }, "sync-browser-docs").start();
    }

    private void refreshPlaywrightPill() {
        boolean ready = BrowserAutomationConfig.isPlaywrightReady(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        if (ready) {
            tvPlaywrightPill.setText("🤖 Playwright ✓");
            tvPlaywrightPill.setTextColor(0xFF4ADE80);
            tvPlaywrightPill.setBackgroundResource(R.drawable.bg_badge_installed);
        } else {
            tvPlaywrightPill.setText("🤖 Setup Playwright");
            tvPlaywrightPill.setTextColor(0xFFFFFFFF);
            tvPlaywrightPill.setBackgroundResource(R.drawable.bg_btn_install_pill);
        }
    }

    private void loadInput(String raw) {
        String normalized = BrowserAutomationConfig.normalizeUrl(raw);
        etUrl.setText(normalized);
        webView.loadUrl(normalized);
    }

    private void updateNavButtons() {
        if (btnBack != null) btnBack.setAlpha(webView.canGoBack() ? 1.0f : 0.35f);
        if (btnForward != null) btnForward.setAlpha(webView.canGoForward() ? 1.0f : 0.35f);
    }

    private void appendConsole(String level, String msg, boolean isError) {
        if (isError) errorCount++;
        consoleBuffer.append("[").append(level).append("] ").append(msg).append('\n');
        if (consoleBuffer.length() > 16_000) {
            consoleBuffer.delete(0, consoleBuffer.length() - 12_000);
        }
        runOnUiThread(() -> {
            tvConsole.setText(consoleBuffer.toString());
            if (errorCount > 0) {
                btnConsoleToggle.setText(">_ " + errorCount);
                btnConsoleToggle.setTextColor(0xFFF87171);
            }
            consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        refreshPlaywrightPill();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
