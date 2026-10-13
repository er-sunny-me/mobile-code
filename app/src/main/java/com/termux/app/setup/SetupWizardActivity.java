package com.termux.app.setup;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.app.TermuxInstaller;
import com.termux.app.terminal.CLIAgentManager;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * First-run setup wizard. On the very first app open it downloads and sets up everything
 * coding agents need, with visible per-step progress:
 *
 * <ol>
 *   <li>Storage access (workspaces live on shared storage),</li>
 *   <li>Termux base system (bootstrap),</li>
 *   <li>Linux system — Ubuntu (bundled in the APK), Debian or Alpine,</li>
 *   <li>Coding agents — agy, opencode, grok, kiro from the APK bundle when present,
 *       the rest (npm-based) via their normal installers.</li>
 * </ol>
 *
 * <p>Every step degrades to its network installer when the bundle is absent, so lean builds
 * without {@code assets/workshop} work exactly like before — just slower.</p>
 */
public class SetupWizardActivity extends AppCompatActivity {

    private static final String PREFS = "ZeroCodePrefs";
    private static final String KEY_SETUP_DONE = "setup_completed";

    private static final String STEP_STORAGE = "storage";
    private static final String STEP_BOOTSTRAP = "bootstrap";
    private static final String STEP_DISTRO = "distro";
    private static final String STEP_AGENTS = "agents";

    private final List<SetupStep> steps = new ArrayList<>();
    private SetupStepAdapter adapter;
    private SetupAgentAdapter agentAdapter;
    private CLIAgentManager cliManager;
    private TextView logView;
    private ScrollView logScroll;
    private TextView btnStart;
    private TextView btnSkip;
    private TextView tvDistroDetail;
    private View distroUbuntu;
    private View distroDebian;
    private View distroAlpine;

    private String selectedDistro = "ubuntu";
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Process currentProcess;
    private volatile CountDownLatch storageLatch;
    private volatile boolean waitingForStorage;

    private final StringBuilder logBuffer = new StringBuilder();

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup_wizard);

        selectedDistro = CLIAgentManager.getWorkshopDistro(this);
        cliManager = new CLIAgentManager(this);

        RecyclerView recyclerView = findViewById(R.id.recycler_setup_steps);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SetupStepAdapter();
        recyclerView.setAdapter(adapter);

        // Coding-agent picker: every available agent, checkbox syncs with the
        // dashboard/chat enabled flags so the choice sticks everywhere.
        RecyclerView agentList = findViewById(R.id.recycler_setup_agents);
        agentList.setLayoutManager(new LinearLayoutManager(this));
        agentAdapter = new SetupAgentAdapter((agent, checked) -> {
            cliManager.setAgentEnabled(agent.id, checked);
            appendLog(agent.name + (checked ? " selected." : " deselected."));
        });
        agentList.setAdapter(agentAdapter);
        buildAgentRows();

        logView = findViewById(R.id.tv_setup_log);
        logScroll = (ScrollView) logView.getParent();
        btnStart = findViewById(R.id.btn_setup_start);
        btnSkip = findViewById(R.id.btn_setup_skip);
        tvDistroDetail = findViewById(R.id.tv_distro_detail);
        distroUbuntu = findViewById(R.id.distro_ubuntu);
        distroDebian = findViewById(R.id.distro_debian);
        distroAlpine = findViewById(R.id.distro_alpine);

        findViewById(R.id.btn_copy_log).setOnClickListener(v -> {
            try {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                String text = logBuffer.toString();
                if (text.trim().isEmpty()) text = "(log is empty)";
                clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("Mobile Code setup log", text));
                Toast.makeText(this, "Setup log copied — paste it anywhere.", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Copy failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        steps.add(new SetupStep(STEP_STORAGE, "Storage access",
                "Lets agents read and write your project files."));
        steps.add(new SetupStep(STEP_BOOTSTRAP, "Termux base system",
                "Core shell, pkg manager and tools."));
        steps.add(new SetupStep(STEP_DISTRO, "Linux system",
                "Ubuntu is bundled in the app — instant. Debian/Alpine download on demand."));
        steps.add(new SetupStep(STEP_AGENTS, "Coding agents",
                "agy, opencode, grok, kiro + npm agents (Claude, Codex, Copilot, Kilo)."));
        adapter.setItems(steps);
        refreshDistroButtons();

        View.OnClickListener distroTap = v -> {
            if (running.get()) return;
            if (v == distroUbuntu) selectedDistro = "ubuntu";
            else if (v == distroDebian) selectedDistro = "debian";
            else if (v == distroAlpine) selectedDistro = "alpine";
            CLIAgentManager.setWorkshopDistro(this, selectedDistro);
            refreshDistroButtons();
            appendLog("Linux system: " + selectedDistro + " selected.");
        };
        distroUbuntu.setOnClickListener(distroTap);
        distroDebian.setOnClickListener(distroTap);
        distroAlpine.setOnClickListener(distroTap);

        btnStart.setOnClickListener(v -> {
            if (running.get()) return;
            if (allDone()) {
                finish();
                return;
            }
            startSetup();
        });
        btnSkip.setOnClickListener(v -> {
            if (running.get()) {
                cancelled.set(true);
                Process p = currentProcess;
                if (p != null) {
                    try {
                        p.destroy();
                    } catch (Exception ignored) {
                    }
                }
                appendLog("Cancelled by user.");
            } else {
                markRemainingSkipped();
                completeWizard(false);
            }
        });

        boolean hasBundle = WorkshopBundleInstaller.hasBundle(this);
        appendLog(hasBundle
                ? "Offline bundle found in APK — setup will be fast."
                : "No offline bundle in this build — everything downloads (slower).");
        String installed = CLIAgentManager.installedWorkshopDistro();
        if (installed != null) {
            selectedDistro = installed;
            CLIAgentManager.setWorkshopDistro(this, installed);
            refreshDistroButtons();
            appendLog("Found existing Linux system: " + installed + ".");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForStorage && storageLatch != null) {
            waitingForStorage = false;
            storageLatch.countDown();
        }
    }

    @Override
    protected void onDestroy() {
        cancelled.set(true);
        Process p = currentProcess;
        if (p != null) {
            try {
                p.destroy();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    /** True when the wizard was fully completed before (first-run hook uses this). */
    public static boolean isSetupCompleted(android.content.Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_SETUP_DONE, false);
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    private void refreshDistroButtons() {
        paintDistro((TextView) distroUbuntu, "ubuntu".equals(selectedDistro), "Ubuntu ★ bundled");
        paintDistro((TextView) distroDebian, "debian".equals(selectedDistro), "Debian");
        paintDistro((TextView) distroAlpine, "alpine".equals(selectedDistro), "Alpine");
        String detail;
        switch (selectedDistro) {
            case "debian":
                detail = "Debian 13 • ~120MB • downloads on demand (needs internet once)";
                break;
            case "alpine":
                detail = "Alpine 3.x • ~5MB • downloads on demand (needs internet once)";
                break;
            case "ubuntu":
            default:
                detail = WorkshopBundleInstaller.hasBundle(this)
                        ? "Ubuntu 24.04 • 29MB • inside the app (offline, instant)"
                        : "Ubuntu 24.04 • ~30MB • downloads on demand (this build has no bundle)";
                break;
        }
        String installed = CLIAgentManager.installedWorkshopDistro();
        if (selectedDistro.equals(installed)) {
            detail += " — already installed ✓";
        }
        final String text = detail;
        runOnUiThread(() -> {
            if (tvDistroDetail != null) tvDistroDetail.setText(text);
            adapter.refresh();
        });
        SetupStep distroStep = findStep(STEP_DISTRO);
        if (distroStep != null && distroStep.status == SetupStep.Status.PENDING) {
            distroStep.detail = "Selected: " + selectedDistro + ".";
        }
    }

    /** Build the visible coding-agent checklist (all available agents, bundled tags). */
    private void buildAgentRows() {
        java.util.Properties manifest = WorkshopBundleInstaller.loadManifest(this);
        String arch = WorkshopBundleInstaller.deviceBundleArch();
        List<SetupAgentAdapter.Row> rows = new ArrayList<>();
        for (CLIAgentManager.CLIAgent agent : CLIAgentManager.AGENTS) {
            if (agent.id.equals("custom")) continue;
            String key = arch == null ? null
                    : WorkshopBundleInstaller.bundledAgentKey(agent.id, arch);
            boolean bundled = manifest != null && key != null
                    && manifest.getProperty(key + ".file") != null;
            rows.add(new SetupAgentAdapter.Row(agent,
                    cliManager.isAgentEnabled(agent.id), bundled));
        }
        agentAdapter.setItems(rows);
        // Fill in already-installed ticks off the main thread (shell probe).
        new Thread(() -> {
            try {
                CLIAgentManager probe = new CLIAgentManager(this);
                java.util.Set<String> ready = new java.util.HashSet<>();
                for (CLIAgentManager.AgentStatus status : probe.getAgentStatuses()) {
                    if (status.installed) ready.add(status.agent.id);
                }
                runOnUiThread(() -> agentAdapter.refreshStates(ready));
            } catch (Exception ignored) {
            }
        }, "setup-agent-states").start();
    }

    private void paintDistro(TextView view, boolean selected, String label) {
        view.setText(selected ? label + " ✓" : label);
        view.setBackgroundResource(selected ? R.drawable.bg_red_button : R.drawable.bg_fab_dark);
    }

    private SetupStep findStep(String id) {
        for (SetupStep step : steps) {
            if (step.id.equals(id)) return step;
        }
        return null;
    }

    private boolean allDone() {
        for (SetupStep step : steps) {
            if (step.status != SetupStep.Status.DONE) return false;
        }
        return true;
    }

    private void setStepStatus(SetupStep step, SetupStep.Status status, String detail) {
        step.status = status;
        if (detail != null) step.detail = detail;
        runOnUiThread(() -> adapter.refresh());
    }

    private void appendLog(final String line) {
        runOnUiThread(() -> {
            logBuffer.append(line).append('\n');
            if (logBuffer.length() > 6000) {
                logBuffer.delete(0, logBuffer.length() - 6000);
            }
            logView.setText(logBuffer.toString());
            if (logScroll != null) {
                logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void markRemainingSkipped() {
        for (SetupStep step : steps) {
            if (step.status == SetupStep.Status.PENDING
                    || step.status == SetupStep.Status.RUNNING
                    || step.status == SetupStep.Status.FAILED) {
                step.status = SetupStep.Status.SKIPPED;
            }
        }
        runOnUiThread(() -> adapter.refresh());
    }

    private void completeWizard(boolean full) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SETUP_DONE, true).apply();
        runOnUiThread(() -> {
            btnStart.setText(full ? "Start coding ✓" : "Finish");
            btnStart.setEnabled(true);
            btnSkip.setVisibility(View.GONE);
            Toast.makeText(this,
                    full ? "Setup complete — happy coding!" : "Setup skipped — you can install later from Coding Agents.",
                    Toast.LENGTH_LONG).show();
        });
    }

    // ------------------------------------------------------------------
    // Engine
    // ------------------------------------------------------------------

    private void startSetup() {
        running.set(true);
        cancelled.set(false);
        runOnUiThread(() -> {
            btnStart.setText("Working…");
            btnStart.setEnabled(false);
            btnSkip.setText("Cancel");
        });
        appendLog("Starting setup…");
        new Thread(() -> {
            try {
                for (SetupStep step : steps) {
                    if (cancelled.get()) break;
                    if (step.status == SetupStep.Status.DONE) continue;
                    runStep(step);
                }
            } finally {
                running.set(false);
                boolean full = allDone();
                if (cancelled.get()) {
                    markRemainingSkipped();
                    completeWizard(false);
                } else if (full) {
                    completeWizard(true);
                } else {
                    runOnUiThread(() -> {
                        btnStart.setText("Retry");
                        btnStart.setEnabled(true);
                        btnSkip.setText("Finish anyway");
                    });
                    appendLog("Some steps failed — tap Retry, or Finish anyway.");
                }
            }
        }, "setup-wizard").start();
    }

    private void runStep(SetupStep step) {
        setStepStatus(step, SetupStep.Status.RUNNING, step.detail);
        boolean ok = false;
        try {
            switch (step.id) {
                case STEP_STORAGE:
                    ok = doStorageStep(step);
                    break;
                case STEP_BOOTSTRAP:
                    ok = doBootstrapStep(step);
                    break;
                case STEP_DISTRO:
                    ok = doDistroStep(step);
                    break;
                case STEP_AGENTS:
                    ok = doAgentsStep(step);
                    break;
                default:
                    ok = false;
                    break;
            }
        } catch (Exception e) {
            appendLog("Error: " + e.getMessage());
            ok = false;
        }
        if (cancelled.get()) {
            setStepStatus(step, SetupStep.Status.SKIPPED, step.detail);
        } else {
            setStepStatus(step, ok ? SetupStep.Status.DONE : SetupStep.Status.FAILED, step.detail);
        }
    }

    // ------------------------------------------------------------------
    // Steps
    // ------------------------------------------------------------------

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean doStorageStep(SetupStep step) {
        if (hasStorageAccess()) {
            step.detail = "Already granted.";
            appendLog("Storage: already granted.");
            return true;
        }
        appendLog("Storage: opening system settings — grant All files access, then come back.");
        runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception ignored) {
                }
            }
            Toast.makeText(this, "Grant All files access, then return here", Toast.LENGTH_LONG).show();
        });
        storageLatch = new CountDownLatch(1);
        waitingForStorage = true;
        try {
            if (!storageLatch.await(10, TimeUnit.MINUTES)) {
                waitingForStorage = false;
                step.detail = "Timed out waiting. Tap Retry.";
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (cancelled.get()) return false;
        if (hasStorageAccess()) {
            step.detail = "Granted.";
            appendLog("Storage: granted.");
            return true;
        }
        step.detail = "Not granted yet. Grant it or Skip.";
        appendLog("Storage: still not granted.");
        return false;
    }

    private boolean isBootstrapReady() {
        // A stale prefix directory can exist without a working shell (partial/old
        // install reusing the app data dir). The shell binary must really be there.
        File bash = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash");
        File prefix = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        String[] children = prefix.list();
        return bash.isFile() && bash.canExecute()
                && prefix.isDirectory() && children != null && children.length > 2;
    }

    /**
     * Wipe a broken prefix (shell missing) and reinstall the bootstrap, then verify.
     * Safe: only wipes when the shell is actually unusable; never touches $HOME.
     */
    private boolean repairBootstrapIfBroken() {
        if (isBootstrapReady()) return true;
        appendLog("Base system is incomplete (shell missing) — wiping and reinstalling…");
        try {
            com.termux.shared.errors.Error wipeError = com.termux.shared.file.FileUtils.deleteFile(
                    "broken termux prefix", TermuxConstants.TERMUX_PREFIX_DIR_PATH, true);
            if (wipeError != null) appendLog("Wipe warning: " + wipeError.getMessage());
            com.termux.shared.errors.Error stagingError = com.termux.shared.file.FileUtils.deleteFile(
                    "broken termux staging prefix",
                    TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH, true);
            if (stagingError != null) appendLog("Wipe warning: " + stagingError.getMessage());
        } catch (Exception e) {
            appendLog("Wipe failed: " + e.getMessage());
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        runOnUiThread(() -> TermuxInstaller.setupBootstrapIfNeeded(this, () -> {
            ok.set(isBootstrapReady());
            latch.countDown();
        }));
        try {
            if (!latch.await(20, TimeUnit.MINUTES)) {
                appendLog("Base system reinstall timed out.");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (cancelled.get()) return false;
        boolean ready = ok.get() || isBootstrapReady();
        appendLog(ready ? "Base system: repaired and verified."
                : "Base system reinstall failed — check internet and retry.");
        return ready;
    }

    private boolean doBootstrapStep(SetupStep step) {
        if (isBootstrapReady()) {
            step.detail = "Already installed.";
            appendLog("Base system: already installed.");
            return true;
        }
        appendLog("Base system: installing (~30MB, bundled in app)…");
        // Repair wipes a shell-less stale prefix first: TermuxInstaller on its own
        // trusts any non-empty prefix dir and would "succeed" without fixing it.
        if (repairBootstrapIfBroken()) {
            step.detail = "Installed.";
            return true;
        }
        step.detail = "Failed — check internet, then tap Retry.";
        return false;
    }

    private boolean doDistroStep(SetupStep step) {
        // The shell may have broken after the bootstrap step (or the wizard was
        // re-run) — never attempt shell work without a verified shell.
        if (!isBootstrapReady() && !repairBootstrapIfBroken()) {
            step.detail = "Base system is broken. Fix it above, then Retry.";
            return false;
        }
        String installed = CLIAgentManager.installedWorkshopDistro();
        if (selectedDistro.equals(installed)) {
            CLIAgentManager.setWorkshopDistro(this, selectedDistro);
            step.detail = selectedDistro + " is already installed.";
            appendLog("Linux system: " + selectedDistro + " already installed.");
            return true;
        }
        CLIAgentManager.setWorkshopDistro(this, selectedDistro);

        // 1. proot-distro package itself (small, needs network once).
        appendLog("Linux system: ensuring proot-distro…");
        ShellResult pkg = execTermux(
                "command -v proot-distro >/dev/null 2>&1 || pkg install -y proot-distro",
                homePath());
        if (pkg.exit != 0 || cancelled.get()) {
            step.detail = "Could not install proot-distro (network needed). Tap Retry.";
            appendLog("proot-distro package install failed. Check internet and retry.");
            return false;
        }

        // 2a. Bundled Ubuntu rootfs — fully offline. Installed through proot-distro
        // itself (local archive support, new v5+): its extractor handles the
        // hardlinks/symlinks a raw `tar -xpf` chokes on (Permission denied), and it
        // lands in the layout the app checks (containers/ or legacy). --name pins it.
        if ("ubuntu".equals(selectedDistro)) {
            File staged = WorkshopBundleInstaller.stageBundledRootfs(this, selectedDistro);
            if (staged != null) {
                appendLog("Linux system: installing bundled Ubuntu (offline)…");
                ShellResult local = execTermux("proot-distro install "
                        + WorkshopBundleInstaller.shellQuote(staged.getAbsolutePath())
                        + " --name " + selectedDistro, homePath());
                String nowInstalled = CLIAgentManager.installedWorkshopDistro();
                if (!cancelled.get() && local.exit == 0 && selectedDistro.equals(nowInstalled)) {
                    step.detail = "Ubuntu installed from app bundle (offline).";
                    appendLog("Linux system: Ubuntu ready (bundled).");
                    return true;
                }
                appendLog("Bundled rootfs install failed — trying download instead.");
            } else {
                appendLog("No Ubuntu bundle for this device — downloading…");
            }
        }

        // 2b. Network install (any distro).
        appendLog("Linux system: downloading " + selectedDistro + " (one-time, ~30-100MB)…");
        ShellResult out = execTermux("proot-distro install " + selectedDistro, homePath());
        if (cancelled.get()) return false;
        installed = CLIAgentManager.installedWorkshopDistro();
        if (out.exit == 0 && selectedDistro.equals(installed)) {
            step.detail = selectedDistro + " installed.";
            appendLog("Linux system: " + selectedDistro + " ready.");
            return true;
        }
        step.detail = "Install failed. Check internet and tap Retry.";
        appendLog("Linux system install failed (exit " + out.exit + ").");
        return false;
    }

    private boolean doAgentsStep(SetupStep step) {
        if (!isBootstrapReady() && !repairBootstrapIfBroken()) {
            step.detail = "Base system is broken. Fix it above, then Retry.";
            return false;
        }
        if (!CLIAgentManager.isWorkshopAvailable()) {
            step.detail = "Linux system is missing — finish that step first.";
            appendLog("Agents: no Linux system yet, skipping.");
            return false;
        }
        CLIAgentManager mgr = new CLIAgentManager(this);
        mgr.invalidateInstallCache();
        String distro = CLIAgentManager.installedWorkshopDistro();
        if (distro == null) distro = selectedDistro;

        // Only the agents ticked in the wizard checklist (unchecked = skipped).
        List<CLIAgentManager.CLIAgent> chosen = new ArrayList<>();
        for (SetupAgentAdapter.Row row : agentAdapter.getItems()) {
            if (row.checked) chosen.add(row.agent);
        }
        if (chosen.isEmpty()) {
            step.detail = "No agents selected — tick at least one above, or Skip.";
            appendLog("Agents: nothing selected.");
            return false;
        }

        int ready = 0;
        int total = chosen.size();
        List<String> failed = new ArrayList<>();
        for (CLIAgentManager.CLIAgent agent : chosen) {
            if (cancelled.get()) return false;
            if (mgr.isAgentInstalled(agent)) {
                ready++;
                appendLog(agent.name + ": already installed.");
                runOnUiThread(() -> agentAdapter.setInstalled(agent.id));
                continue;
            }
            appendLog(agent.name + ": installing…");
            String command = null;
            File staged = WorkshopBundleInstaller.stageBundledAgent(this, agent.id);
            if (staged != null) {
                // Kiro ships as a zip (minimal rootfs has no unzip): expand it with
                // Java into the shared staging dir and hand the DIR to the snippet.
                String stagedPath = staged.getAbsolutePath();
                if (agent.id.equals("kiro") && stagedPath.endsWith(".zip")) {
                    File expanded = new File(staged.getParentFile(), "kiro-extracted");
                    File marker = new File(expanded, "kirocli/install.sh");
                    if (!marker.isFile()) {
                        deleteRecursively(expanded);
                        if (!WorkshopBundleInstaller.unzipToDir(staged, expanded)) {
                            appendLog("Kiro bundle unzip failed — trying download instead.");
                            staged = null;
                        }
                    }
                    if (staged != null) stagedPath = expanded.getAbsolutePath();
                }
                if (staged != null) {
                    String snippet = WorkshopBundleInstaller.bundledAgentInstallSnippet(
                            agent.id, stagedPath);
                    if (snippet != null) {
                        command = CLIAgentManager.shellPrelude() + snippet;
                        appendLog(agent.name + ": using app bundle (offline).");
                    }
                }
            }
            if (command == null) {
                command = mgr.buildInstallCommand(agent);
            }
            String wrapped = CLIAgentManager.wrapForWorkshop(command, distro);
            ShellResult out = execTermux(wrapped, homePath());
            if (cancelled.get()) return false;
            mgr.invalidateInstallCache();
            boolean installed = mgr.isAgentInstalled(agent);
            if (!installed && out.exit == 0) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                mgr.invalidateInstallCache();
                installed = mgr.isAgentInstalled(agent);
            }
            if (installed || out.exit == 0) {
                ready++;
                appendLog(agent.name + ": installed ✓");
                runOnUiThread(() -> agentAdapter.setInstalled(agent.id));
            } else {
                failed.add(agent.name);
                appendLog(agent.name + ": FAILED (exit " + out.exit + ")");
            }
            runOnUiThread(() -> adapter.refresh());
        }
        step.detail = ready + "/" + total + " agents ready"
                + (failed.isEmpty() ? "." : ". Failed: " + String.join(", ", failed));
        appendLog("Agents: " + step.detail);
        return failed.isEmpty();
    }

    // ------------------------------------------------------------------
    // Sync shell (worker thread only)
    // ------------------------------------------------------------------

    private static class ShellResult {
        int exit = -1;
        String output = "";
    }

    private String homePath() {
        return TermuxConstants.TERMUX_HOME_DIR_PATH;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        try {
            file.delete();
        } catch (Exception ignored) {
        }
    }

    /** Run a command in native Termux bash and stream every line into the log box. */
    private ShellResult execTermux(String command, String cwd) {
        ShellResult result = new ShellResult();
        StringBuilder output = new StringBuilder();
        String bash = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(bash).exists()) {
            appendLog("Shell missing — base system step must repair it first. Tap Retry.");
            return result;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(bash, "-lc", command);
            File dir = new File(cwd);
            if (!dir.isDirectory()) dir = new File(homePath());
            pb.directory(dir);
            pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/"
                    + ":" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/applets");
            pb.environment().put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/lib");
            pb.environment().put("TMPDIR", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/tmp");
            pb.environment().put("TERM", "xterm-256color");
            pb.environment().put("LANG", "en_US.UTF-8");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            currentProcess = process;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) appendLog(trimmed);
                    if (cancelled.get()) {
                        process.destroy();
                        break;
                    }
                }
            }
            result.exit = process.waitFor();
            result.output = output.toString();
        } catch (Exception e) {
            appendLog("Shell error: " + e.getMessage());
            result.output = output.toString();
        } finally {
            currentProcess = null;
        }
        return result;
    }
}
