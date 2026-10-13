package com.termux.app.gui;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.app.terminal.CLIAgentManager;

import java.util.List;

/**
 * Agent Dashboard — lists every supported CLI coding agent, reports whether its binary is
 * present in the Termux environment, and lets the user install, enable/disable, choose a
 * default, or override the non-interactive command used by the chat GUI.
 *
 * <p>All agent state lives in {@link CLIAgentManager}; this screen is pure presentation plus
 * the install process. Installing runs the agent's own install command through
 * {@link AgentRunner} so the terminal screen is not needed.</p>
 */
public class AgentDashboardActivity extends AppCompatActivity implements AgentListAdapter.Listener {

    private CLIAgentManager agentManager;
    private AgentListAdapter adapter;
    private AgentRunner installRunner;
    private TextView hintView;

    private String installingAgentId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent_dashboard);

        try {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                    findViewById(R.id.root_agent_dashboard), (v, insets) -> {
                        androidx.core.graphics.Insets sys =
                                insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
                        View topBar = findViewById(R.id.top_bar);
                        if (topBar != null) {
                            topBar.setPadding(topBar.getPaddingLeft(), sys.top,
                                    topBar.getPaddingRight(), topBar.getPaddingBottom());
                        }
                        v.setPadding(v.getPaddingLeft(), 0, v.getPaddingRight(), sys.bottom);
                        return insets;
                    });
        } catch (Throwable ignored) {
        }

        agentManager = new CLIAgentManager(this);
        installRunner = new AgentRunner();
        adapter = new AgentListAdapter(agentManager, this);
        hintView = findViewById(R.id.tv_dash_hint);

        RecyclerView recyclerView = findViewById(R.id.recycler_agents);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        findViewById(R.id.btn_dash_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_dash_refresh).setOnClickListener(v -> {
            Toast.makeText(this, "Re-checking installed agents…", Toast.LENGTH_SHORT).show();
            refreshStatuses();
        });
        findViewById(R.id.btn_dash_setup_all).setOnClickListener(v -> onSetupAll());

        refreshStatuses();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (installRunner != null) installRunner.cancel();
    }

    /**
     * Re-read the installed state for every agent. Detecting a binary shells out, so this runs
     * off the main thread.
     */
    private void refreshStatuses() {
        agentManager.invalidateInstallCache();
        new Thread(() -> {
            final List<CLIAgentManager.AgentStatus> statuses = agentManager.getAgentStatuses();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                adapter.setItems(statuses);
                updateHint(statuses);
            });
        }, "agent-status").start();
    }

    private void updateHint(List<CLIAgentManager.AgentStatus> statuses) {
        int installed = 0;
        int misplaced = 0;
        for (CLIAgentManager.AgentStatus status : statuses) {
            if (status.installed) installed++;
            else if (status.installedElsewhere) misplaced++;
        }
        String env = AgentRunner.isWorkshopAvailable()
                ? "Ubuntu workshop" : "Termux (open the terminal once to set up the workshop)";
        String text = installed + " of " + statuses.size() + " agents ready in the " + env
                + ". Install one to use it from Agent Chat, or open it in the terminal.";
        if (misplaced > 0) {
            text = installed + " of " + statuses.size() + " agents ready in the " + env + ". "
                    + misplaced + " found elsewhere (wrong place) — tap Reinstall on them.";
        }
        hintView.setText(text);
    }

    // ------------------------------------------------------------------
    // AgentListAdapter.Listener
    // ------------------------------------------------------------------

    @Override
    public void onInstall(CLIAgentManager.CLIAgent agent) {
        if (installRunner.isRunning()) {
            Toast.makeText(this, "Another install is already running.", Toast.LENGTH_SHORT).show();
            return;
        }

        installingAgentId = agent.id;
        adapter.setInstalling(agent.id);
        hintView.setText("Installing " + agent.name + "… this can take a few minutes.");

        String command = agentManager.buildInstallCommand(agent);
        installRunner.run(homeDir(), command, new AgentRunner.Listener() {
            @Override
            public void onStarted(String cmd) {
                // no-op
            }

            @Override
            public void onOutput(String line) {
                if (!TextUtils.isEmpty(line)) {
                    hintView.setText(agent.name + ": " + line.trim());
                }
            }

            @Override
            public void onFinished(int exitCode) {
                installingAgentId = null;
                adapter.setInstalling(null);
                agentManager.invalidateInstallCache();
                boolean ok = exitCode == 0 && agentManager.isAgentInstalled(agent);
                Toast.makeText(AgentDashboardActivity.this,
                        ok ? agent.name + " installed." : agent.name + " install finished (exit " + exitCode + ").",
                        Toast.LENGTH_LONG).show();
                refreshStatuses();
            }

            @Override
            public void onFailure(String message) {
                installingAgentId = null;
                adapter.setInstalling(null);
                Toast.makeText(AgentDashboardActivity.this, message, Toast.LENGTH_LONG).show();
                refreshStatuses();
            }
        });
    }

    @Override
    public void onLogin(CLIAgentManager.CLIAgent agent) {
        // Auth (browser OAuth for agy, API keys for the rest) needs the interactive
        // terminal — the chat GUI closes stdin, so login can never complete there.
        // Open the agent's TUI in the Ubuntu workshop (same env it was installed in).
        android.content.Intent intent =
                new android.content.Intent(this, com.termux.app.TermuxActivity.class);
        intent.putExtra("com.termux.execute.cwd", workspacePath());
        intent.putExtra("com.termux.execute.command",
                agentManager.buildLoginCommand(agent, workspacePath()));
        startActivity(intent);
        Toast.makeText(this,
                agent.name + ": sign in inside the terminal, then come back here.",
                Toast.LENGTH_LONG).show();
    }

    @Override
    public void onUseInChat(CLIAgentManager.CLIAgent agent) {
        agentManager.setSelectedAgent(agent.id);
        if (!agentManager.isAgentEnabled(agent.id)) {
            agentManager.setAgentEnabled(agent.id, true);
        }
        Toast.makeText(this, agent.name + " selected for Agent Chat", Toast.LENGTH_SHORT).show();
        refreshStatuses();
    }

    @Override
    public void onToggleEnabled(CLIAgentManager.CLIAgent agent) {
        boolean nowEnabled = !agentManager.isAgentEnabled(agent.id);
        agentManager.setAgentEnabled(agent.id, nowEnabled);
        Toast.makeText(this, agent.name + (nowEnabled ? " enabled" : " hidden from the picker"),
                Toast.LENGTH_SHORT).show();
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onEditCommand(CLIAgentManager.CLIAgent agent) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_agent_gui_command, null);
        TextView title = content.findViewById(R.id.tv_cmd_title);
        EditText input = content.findViewById(R.id.et_cmd_value);

        title.setText(agent.name + " — GUI command");
        String current = agent.id.equals("custom")
                ? agentManager.getCustomCommand()
                : agentManager.getGuiCommand(agent);
        input.setText(current == null ? "" : current);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setView(content)
                .setPositiveButton("Save", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (agent.id.equals("custom")) {
                        agentManager.setCustomCommand(value);
                    } else {
                        agentManager.setGuiCommand(agent.id, value);
                    }
                    adapter.notifyDataSetChanged();
                    Toast.makeText(this, "Command saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null);

        if (agentManager.hasCustomGuiCommand(agent.id)) {
            builder.setNeutralButton("Reset", (dialog, which) -> {
                agentManager.setGuiCommand(agent.id, null);
                adapter.notifyDataSetChanged();
                Toast.makeText(this, "Reverted to the built-in command", Toast.LENGTH_SHORT).show();
            });
        }

        builder.show();
    }

    // ------------------------------------------------------------------
    // Setup-all: Ubuntu workshop + every missing agent, one tap. This is the
    // closest thing to "bundling" without shipping a multi-GB APK (Ubuntu rootfs
    // ~200MB + agy ~160MB + node toolchains, per-arch, stale within weeks, and
    // auth can never be bundled anyway). One tap fetches the same bits the
    // terminal would have fetched, in the same environment.
    // ------------------------------------------------------------------

    /** Pending setup-all queue, or null when idle. */
    private java.util.ArrayDeque<CLIAgentManager.CLIAgent> setupQueue;
    private int setupTotal;

    private void onSetupAll() {
        if (installRunner.isRunning()) {
            Toast.makeText(this, "Another install is already running.", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            agentManager.invalidateInstallCache();
            final java.util.List<CLIAgentManager.AgentStatus> statuses =
                    agentManager.getAgentStatuses();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setupQueue = new java.util.ArrayDeque<>();
                for (CLIAgentManager.AgentStatus s : statuses) {
                    if (!s.installed && !s.agent.id.equals("custom")
                            && s.agent.installCommand != null
                            && !s.agent.installCommand.isEmpty()) {
                        setupQueue.add(s.agent);
                    }
                }
                boolean needWorkshop = !AgentRunner.isWorkshopAvailable();
                if (setupQueue.isEmpty() && !needWorkshop) {
                    Toast.makeText(this, "Everything is already installed.",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                setupTotal = setupQueue.size() + (needWorkshop ? 1 : 0);
                if (needWorkshop) {
                    runWorkshopSetup(() -> runNextSetup());
                } else {
                    runNextSetup();
                }
            });
        }, "setup-all-plan").start();
    }

    /** Install the Ubuntu rootfs in native Termux (must NOT run inside the workshop). */
    private void runWorkshopSetup(Runnable onDone) {
        adapter.setInstalling("__workshop__");
        hintView.setText("Setting up Ubuntu workshop (1/" + setupTotal + ")… "
                + "this downloads ~200MB once.");
        String command = CLIAgentManager.shellPrelude()
                + "if ! command -v proot-distro &> /dev/null; then pkg update -y && pkg install -y proot-distro; fi; "
                + "if [ ! -d \"$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu\" ]; then proot-distro install ubuntu; fi";
        // Workshop is missing so AgentRunner runs this in native Termux. Correct env.
        installRunner.run(homeDir(), command, new AgentRunner.Listener() {
            @Override
            public void onStarted(String cmd) {
            }

            @Override
            public void onOutput(String line) {
                if (!TextUtils.isEmpty(line)) {
                    hintView.setText("Ubuntu: " + line.trim());
                }
            }

            @Override
            public void onFinished(int exitCode) {
                adapter.setInstalling(null);
                agentManager.invalidateInstallCache();
                if (exitCode != 0 || !AgentRunner.isWorkshopAvailable()) {
                    installingAgentId = null;
                    setupQueue = null;
                    Toast.makeText(AgentDashboardActivity.this,
                            "Ubuntu setup failed (exit " + exitCode
                                    + "). Open the terminal once and retry.",
                            Toast.LENGTH_LONG).show();
                    refreshStatuses();
                    return;
                }
                onDone.run();
            }

            @Override
            public void onFailure(String message) {
                adapter.setInstalling(null);
                setupQueue = null;
                Toast.makeText(AgentDashboardActivity.this, message, Toast.LENGTH_LONG).show();
                refreshStatuses();
            }
        });
    }

    private void runNextSetup() {
        if (setupQueue == null || setupQueue.isEmpty()) {
            setupQueue = null;
            installingAgentId = null;
            adapter.setInstalling(null);
            Toast.makeText(this, "Setup finished. Use Login for agents needing sign-in.",
                    Toast.LENGTH_LONG).show();
            refreshStatuses();
            return;
        }
        CLIAgentManager.CLIAgent agent = setupQueue.poll();
        int done = setupTotal - setupQueue.size();
        installingAgentId = agent.id;
        adapter.setInstalling(agent.id);
        hintView.setText("Installing " + agent.name + " (" + done + "/" + setupTotal + ")…");

        String command = agentManager.buildInstallCommand(agent);
        installRunner.run(homeDir(), command, new AgentRunner.Listener() {
            @Override
            public void onStarted(String cmd) {
            }

            @Override
            public void onOutput(String line) {
                if (!TextUtils.isEmpty(line)) {
                    hintView.setText(agent.name + " (" + done + "/" + setupTotal + "): "
                            + line.trim());
                }
            }

            @Override
            public void onFinished(int exitCode) {
                agentManager.invalidateInstallCache();
                // Continue even if one agent failed — report at the end via refresh.
                runNextSetup();
            }

            @Override
            public void onFailure(String message) {
                agentManager.invalidateInstallCache();
                runNextSetup();
            }
        });
    }

    // ------------------------------------------------------------------

    private String workspacePath() {
        android.content.SharedPreferences prefs =
                getSharedPreferences("ZeroCodePrefs", MODE_PRIVATE);
        String path = prefs.getString("workspace_path", null);
        if (!TextUtils.isEmpty(path) && new java.io.File(path).isDirectory()) {
            return path;
        }
        return homeDir();
    }

    private String homeDir() {
        return com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH;
    }
}
