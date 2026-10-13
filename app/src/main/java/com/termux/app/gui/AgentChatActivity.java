package com.termux.app.gui;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminal.CLIAgentManager;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

/**
 * The Agent Chat GUI.
 *
 * <p>The selected CLI coding agent is still executed exactly as before — as a normal process
 * inside the Termux environment — but instead of the user reading a raw terminal, its output is
 * parsed ({@link AgentOutputParser}) and rendered as chat bubbles.</p>
 *
 * <p>Agents that have no known non-interactive mode fall back to the terminal via the
 * "open in terminal" action, so nothing that worked before stops working.</p>
 */
public class AgentChatActivity extends AppCompatActivity {

    private static final String PREFS_WORKSPACE = "ZeroCodePrefs";
    private static final String KEY_WORKSPACE = "workspace_path";
    /** How often buffered agent output is pushed into the transcript. */
    private static final long FLUSH_INTERVAL_MS = 250;

    private CLIAgentManager agentManager;
    private AgentRunner runner;
    private ChatMessageAdapter adapter;

    private RecyclerView recyclerView;
    private Spinner agentSpinner;
    private EditText promptInput;
    private View rowRunning;
    private TextView runningLabel;
    private ProgressBar progressRunning;
    private View btnChatStop;

    private final List<CLIAgentManager.CLIAgent> enabledAgents = new ArrayList<>();
    private final StringBuilder pendingOutput = new StringBuilder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean suppressSpinnerCallback = false;
    private boolean flushScheduled = false;

    // Compact Floating Slash Commands Suggestions Card
    private View cardSlashSuggestions;
    private ListView listSlashSuggestions;
    private TextView tvSlashPanelTitle;
    private ImageView btnCloseSlashPanel;
    private ArrayAdapter<AgentSlashCommands.Command> slashCommandsAdapter;
    private final List<AgentSlashCommands.Command> visibleSlashCommands = new ArrayList<>();

    // Model & Thinking configuration strip
    private View btnSelectModel;
    private TextView tvCurrentModel;
    private View btnSelectThinking;
    private TextView tvCurrentThinking;
    private TextView tvStripLiveStatus;

    // Live thinking timer state
    private long executionStartTime = 0;
    private boolean firstOutputReceived = false;
    private final Handler thinkingTimerHandler = new Handler(Looper.getMainLooper());
    private final Runnable thinkingTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (runner != null && runner.isRunning()) {
                long elapsed = System.currentTimeMillis() - executionStartTime;
                int seconds = (int) (elapsed / 1000);
                CLIAgentManager.CLIAgent agent = selectedAgent();
                String agentName = agent != null ? agent.name : "Agent";
                if (!firstOutputReceived) {
                    if (tvStripLiveStatus != null) {
                        tvStripLiveStatus.setText("🧠 Thinking " + seconds + "s");
                    }
                    if (runningLabel != null) {
                        runningLabel.setText("🧠 " + agentName + " is thinking… (" + seconds + "s)");
                    }
                } else {
                    if (tvStripLiveStatus != null) {
                        tvStripLiveStatus.setText("⚡ Streaming answer… (" + seconds + "s)");
                    }
                }
                thinkingTimerHandler.postDelayed(this, 1000);
            }
        }
    };

    // Attachments & @file tagging state
    private HorizontalScrollView scrollAttachments;
    private LinearLayout containerAttachments;
    private ImageButton btnChatSlash;
    private ImageButton btnChatPhoto;
    private ImageButton btnChatAttach;
    private String pendingAttachedImagePath = null;
    private final List<String> pendingAttachedFiles = new ArrayList<>();
    private ActivityResultLauncher<String> imagePickerLauncher;

    private final Runnable flushRunnable = new Runnable() {
        @Override
        public void run() {
            flushScheduled = false;
            flushOutput();
        }
    };

    private boolean hasActiveSession = false;
    private ChatSessionManager sessionManager;
    private ChatSession currentSession;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent_chat);
        applyEdgeToEdgeInsets();

        agentManager = new CLIAgentManager(this);
        runner = new AgentRunner();
        adapter = new ChatMessageAdapter();
        sessionManager = new ChatSessionManager(this);

        recyclerView = findViewById(R.id.recycler_chat);
        agentSpinner = findViewById(R.id.chat_agent_spinner);
        promptInput = findViewById(R.id.et_chat_prompt);
        rowRunning = findViewById(R.id.row_running);
        runningLabel = findViewById(R.id.tv_running_label);
        progressRunning = findViewById(R.id.progress_running);
        btnChatStop = findViewById(R.id.btn_chat_stop);

        // Bind Model & Thinking strip
        btnSelectModel = findViewById(R.id.btn_select_model);
        tvCurrentModel = findViewById(R.id.tv_current_model);
        btnSelectThinking = findViewById(R.id.btn_select_thinking);
        tvCurrentThinking = findViewById(R.id.tv_current_thinking);
        tvStripLiveStatus = findViewById(R.id.tv_strip_live_status);

        if (btnSelectModel != null) {
            btnSelectModel.setOnClickListener(v -> showModelSelectionDialog());
        }
        if (btnSelectThinking != null) {
            btnSelectThinking.setOnClickListener(v -> showThinkingLevelSelectionDialog());
        }
        updateModelAndThinkingChips();

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        setupPermissionActionListener();

        findViewById(R.id.btn_chat_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.btn_chat_history).setOnClickListener(v -> showSessionsDialog());
        findViewById(R.id.btn_chat_new).setOnClickListener(v -> startNewSession());
        findViewById(R.id.btn_chat_send).setOnClickListener(v -> sendPrompt());
        if (btnChatStop != null) {
            btnChatStop.setOnClickListener(v -> stopAgent());
        }
        findViewById(R.id.btn_chat_dashboard).setOnClickListener(v ->
                startActivity(new Intent(this, AgentDashboardActivity.class)));
        findViewById(R.id.btn_chat_terminal).setOnClickListener(v -> openInTerminal(null));

        scrollAttachments = findViewById(R.id.scroll_attachments);
        containerAttachments = findViewById(R.id.container_attachments);

        cardSlashSuggestions = findViewById(R.id.card_slash_suggestions);
        listSlashSuggestions = findViewById(R.id.list_slash_suggestions);
        tvSlashPanelTitle = findViewById(R.id.tv_slash_panel_title);
        btnCloseSlashPanel = findViewById(R.id.btn_close_slash_panel);
        setupCompactSlashSuggestions();

        btnChatSlash = findViewById(R.id.btn_chat_slash);
        if (btnChatSlash != null) {
            btnChatSlash.setOnClickListener(v -> toggleCompactSlashSuggestions());
        }

        btnChatPhoto = findViewById(R.id.btn_chat_photo);
        if (btnChatPhoto != null) {
            btnChatPhoto.setOnClickListener(v -> launchPhotoPicker());
        }

        btnChatAttach = findViewById(R.id.btn_chat_attach);
        if (btnChatAttach != null) {
            btnChatAttach.setOnClickListener(v -> showAttachOptionsDialog());
        }

        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        handleImagePicked(uri);
                    }
                }
        );

        promptInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s != null) {
                    String str = s.toString();
                    if (str.startsWith("/")) {
                        showCompactSlashSuggestions(str);
                    } else {
                        hideCompactSlashSuggestions();
                        if (str.endsWith("@")) {
                            showFilePickerDialog();
                        }
                    }
                } else {
                    hideCompactSlashSuggestions();
                }
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        promptInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendPrompt();
                return true;
            }
            return false;
        });

        setupAgentSpinner();
        List<ChatSession> pastSessions = sessionManager.getAllSessions();
        if (!pastSessions.isEmpty()) {
            loadSession(pastSessions.get(0), false);
        } else {
            showWelcome();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The dashboard may have changed which agents are enabled / installed.
        setupAgentSpinner();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(flushRunnable);
        thinkingTimerHandler.removeCallbacks(thinkingTimerRunnable);
        if (runner != null) runner.cancel();
    }

    @Override
    public void onBackPressed() {
        if (runner != null && runner.isRunning()) {
            new AlertDialog.Builder(this)
                    .setTitle("Agent is still running")
                    .setMessage("Stop it and leave?")
                    .setPositiveButton("Stop", (d, w) -> {
                        stopAgent();
                        finish();
                    })
                    .setNegativeButton("Keep working", null)
                    .show();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------
    // Agent selection
    // ------------------------------------------------------------------

    /**
     * Edge-to-edge fix: on Android 15+ the top bar slides under the status bar and the
     * bottom input slides under the navigation bar / IME. Pad both bars with the
     * system-bar insets so nothing is obscured or untappable.
     */
    private void applyEdgeToEdgeInsets() {
        try {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                    findViewById(R.id.root_agent_chat), (v, insets) -> {
                        androidx.core.graphics.Insets sys =
                                insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
                        View topBar = findViewById(R.id.top_bar);
                        if (topBar != null) {
                            topBar.setPadding(topBar.getPaddingLeft(), sys.top,
                                    topBar.getPaddingRight(), topBar.getPaddingBottom());
                        }
                        View bottomBar = findViewById(R.id.bottom_bar);
                        if (bottomBar != null) {
                            bottomBar.setPadding(bottomBar.getPaddingLeft(),
                                    bottomBar.getPaddingTop(), bottomBar.getPaddingRight(),
                                    Math.max(sys.bottom, 8));
                        }
                        // Keep chat content above the nav bar.
                        if (recyclerView != null) {
                            recyclerView.setClipToPadding(false);
                        }
                        return insets;
                    });
        } catch (Throwable ignored) {
            // Insets API missing on very old devices — fitsSystemWindows in XML covers it.
        }
    }

    private void setupAgentSpinner() {
        CLIAgentManager.CLIAgent previouslySelected = selectedAgent();

        enabledAgents.clear();
        for (CLIAgentManager.CLIAgent agent : CLIAgentManager.AGENTS) {
            if (agentManager.isAgentEnabled(agent.id)) {
                enabledAgents.add(agent);
            }
        }

        if (enabledAgents.isEmpty()) {
            enabledAgents.add(CLIAgentManager.AGENTS[0]);
        }

        List<String> labels = new ArrayList<>();
        for (CLIAgentManager.CLIAgent agent : enabledAgents) {
            labels.add(agent.iconEmoji + "  " + agent.name);
        }

        suppressSpinnerCallback = true;
        ArrayAdapter<String> spinAdapter =
                new ArrayAdapter<>(this, R.layout.spinner_item_white, labels);
        spinAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_white);
        agentSpinner.setAdapter(spinAdapter);

        int index = 0;
        if (previouslySelected != null) {
            for (int i = 0; i < enabledAgents.size(); i++) {
                if (enabledAgents.get(i).id.equals(previouslySelected.id)) {
                    index = i;
                    break;
                }
            }
        }
        agentSpinner.setSelection(index);
        suppressSpinnerCallback = false;

        agentSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressSpinnerCallback) return;
                if (position < 0 || position >= enabledAgents.size()) return;
                CLIAgentManager.CLIAgent agent = enabledAgents.get(position);
                agentManager.setSelectedAgent(agent.id);
                hasActiveSession = false;
                if (rowRunning != null) rowRunning.setVisibility(View.GONE);
                if (promptInput != null) {
                    promptInput.setHint("Message " + agent.name + "…");
                }
                hideCompactSlashSuggestions();
                updateRunningLabelFor(agent);
                updateModelAndThinkingChips();
                agentManager.queryLiveModelsAsync(agent, liveModels -> updateModelAndThinkingChips());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        CLIAgentManager.CLIAgent current = selectedAgent();
        if (current != null) {
            updateRunningLabelFor(current);
            if (promptInput != null) {
                promptInput.setHint("Message " + current.name + "…");
            }
            agentManager.queryLiveModelsAsync(current, liveModels -> updateModelAndThinkingChips());
        }
        updateModelAndThinkingChips();
    }

    private void startNewSession() {
        if (runner != null && runner.isRunning()) {
            Toast.makeText(this, "An agent is currently running. Stop it first.", Toast.LENGTH_SHORT).show();
            return;
        }
        hasActiveSession = false;
        currentSession = null;
        adapter.clear();
        showWelcome();
        if (rowRunning != null) rowRunning.setVisibility(View.GONE);
        hideCompactSlashSuggestions();
        CLIAgentManager.CLIAgent current = selectedAgent();
        if (current != null) {
            updateRunningLabelFor(current);
            if (promptInput != null) {
                promptInput.setHint("Message " + current.name + "…");
            }
        }
        updateModelAndThinkingChips();
        Toast.makeText(this, "Started new chat session", Toast.LENGTH_SHORT).show();
    }

    private void loadSession(ChatSession session, boolean showToast) {
        if (session == null) return;
        currentSession = session;
        hasActiveSession = !session.messages.isEmpty();
        adapter.clear();
        for (ChatMessage msg : session.messages) {
            adapter.add(msg);
        }
        for (int i = 0; i < enabledAgents.size(); i++) {
            if (enabledAgents.get(i).id.equals(session.agentId)) {
                suppressSpinnerCallback = true;
                agentSpinner.setSelection(i);
                suppressSpinnerCallback = false;
                agentManager.setSelectedAgent(session.agentId);
                break;
            }
        }
        hideCompactSlashSuggestions();
        CLIAgentManager.CLIAgent current = selectedAgent();
        if (current != null) {
            if (hasActiveSession) {
                if (rowRunning != null) rowRunning.setVisibility(View.VISIBLE);
                if (progressRunning != null) progressRunning.setVisibility(View.GONE);
                if (runningLabel != null) {
                    runningLabel.setText("🟢 " + current.name + " is Active & Ready");
                }
                if (btnChatStop != null) btnChatStop.setVisibility(View.VISIBLE);
                if (promptInput != null) {
                    promptInput.setHint("Reply to " + current.name + "…");
                }
            } else {
                if (rowRunning != null) rowRunning.setVisibility(View.GONE);
                if (promptInput != null) {
                    promptInput.setHint("Message " + current.name + "…");
                }
            }
            updateRunningLabelFor(current);
        }
        updateModelAndThinkingChips();
        scrollToBottom();
        if (showToast) {
            Toast.makeText(this, "Loaded: " + session.title, Toast.LENGTH_SHORT).show();
        }
    }

    private void showSessionsDialog() {
        List<ChatSession> sessions = sessionManager.getAllSessions();
        if (sessions.isEmpty()) {
            Toast.makeText(this, "No saved chat sessions yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] titles = new String[sessions.size()];
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault());
        for (int i = 0; i < sessions.size(); i++) {
            ChatSession s = sessions.get(i);
            String title = s.title != null && !s.title.isEmpty() ? s.title : "Untitled Session";
            if (title.length() > 35) title = title.substring(0, 35) + "…";
            titles[i] = (s.icon != null ? s.icon + " " : "")
                    + title + "\n"
                    + s.agentName + " • " + sdf.format(new java.util.Date(s.lastUpdated))
                    + " (" + s.messages.size() + " messages)";
        }

        new AlertDialog.Builder(this)
                .setTitle("Chat History (" + sessions.size() + ")")
                .setItems(titles, (dialog, which) -> {
                    if (runner != null && runner.isRunning()) {
                        Toast.makeText(this, "Stop the running agent before switching sessions.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    loadSession(sessions.get(which), true);
                })
                .setNeutralButton("Delete All", (dialog, which) -> {
                    new AlertDialog.Builder(this)
                            .setTitle("Delete all chat sessions?")
                            .setMessage("All saved chat history will be removed.")
                            .setPositiveButton("Delete", (d2, w2) -> {
                                for (ChatSession s : sessions) {
                                    sessionManager.deleteSession(s.id);
                                }
                                startNewSession();
                                Toast.makeText(this, "All sessions deleted", Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private CLIAgentManager.CLIAgent selectedAgent() {
        if (agentSpinner == null) return agentManager.getSelectedAgent();
        int position = agentSpinner.getSelectedItemPosition();
        if (position >= 0 && position < enabledAgents.size()) {
            return enabledAgents.get(position);
        }
        return agentManager.getSelectedAgent();
    }

    private void updateRunningLabelFor(CLIAgentManager.CLIAgent agent) {
        if (runningLabel == null || agent == null) return;
        if (!agentManager.isAgentInstalled(agent) && !agent.id.equals("custom")) {
            runningLabel.setText(agent.name + " is not installed yet — tap Install in the Agents screen.");
        } else {
            runningLabel.setText(agent.name + " is ready.");
        }
    }

    // ------------------------------------------------------------------
    // Running prompts
    // ------------------------------------------------------------------

    private void sendPrompt() {
        final String prompt = promptInput.getText().toString().trim();
        if (prompt.isEmpty() && pendingAttachedImagePath == null && pendingAttachedFiles.isEmpty()) return;

        hideCompactSlashSuggestions();

        // Local slash commands shortcuts
        if (prompt.equalsIgnoreCase("/clear")) {
            promptInput.setText("");
            startNewSession();
            return;
        }
        if (prompt.equalsIgnoreCase("/terminal")) {
            promptInput.setText("");
            openInTerminal(null);
            return;
        }
        if (prompt.equalsIgnoreCase("/model") || prompt.startsWith("/model ")) {
            String modelArg = prompt.length() > 7 ? prompt.substring(7).trim() : "";
            promptInput.setText("");
            CLIAgentManager.CLIAgent cur = selectedAgent();
            if (!modelArg.isEmpty() && cur != null) {
                agentManager.setAgentModel(cur.id, modelArg);
                updateModelAndThinkingChips();
                Toast.makeText(this, "Model set to " + modelArg, Toast.LENGTH_SHORT).show();
            } else {
                showModelSelectionDialog();
            }
            return;
        }
        if (prompt.equalsIgnoreCase("/thinking") || prompt.startsWith("/thinking ")) {
            String thinkingArg = prompt.length() > 10 ? prompt.substring(10).trim() : "";
            promptInput.setText("");
            CLIAgentManager.CLIAgent cur = selectedAgent();
            if (!thinkingArg.isEmpty() && cur != null) {
                agentManager.setAgentThinkingLevel(cur.id, thinkingArg);
                updateModelAndThinkingChips();
                Toast.makeText(this, "Thinking set to " + thinkingArg, Toast.LENGTH_SHORT).show();
            } else {
                showThinkingLevelSelectionDialog();
            }
            return;
        }

        final CLIAgentManager.CLIAgent agent = selectedAgent();
        if (agent == null) return;

        // Build prompt incorporating attached files and image
        StringBuilder promptWithContext = new StringBuilder();
        if (!pendingAttachedFiles.isEmpty()) {
            promptWithContext.append("[Attached project files: ");
            for (int i = 0; i < pendingAttachedFiles.size(); i++) {
                promptWithContext.append("@").append(pendingAttachedFiles.get(i));
                if (i < pendingAttachedFiles.size() - 1) promptWithContext.append(", ");
            }
            promptWithContext.append("]\n");
        }
        if (pendingAttachedImagePath != null) {
            promptWithContext.append("[Attached photo/image: ").append(pendingAttachedImagePath).append("]\n");
        }
        if (!prompt.isEmpty()) {
            promptWithContext.append(prompt);
        } else if (pendingAttachedImagePath != null) {
            promptWithContext.append("Please inspect the attached photo/image and analyze it.");
        } else {
            promptWithContext.append("Please inspect the attached project files and summarize them.");
        }
        final String effectivePrompt = promptWithContext.toString();

        final String attachedImage = pendingAttachedImagePath;
        final List<String> attachedFiles = new ArrayList<>(pendingAttachedFiles);

        // If an agent process is currently running and active, stream input directly via stdin
        if (runner.isRunning()) {
            boolean sent = runner.sendInput(effectivePrompt + "\n");
            if (sent) {
                promptInput.setText("");
                pendingAttachedImagePath = null;
                pendingAttachedFiles.clear();
                refreshAttachmentChips();

                String displayPrompt = !prompt.isEmpty() ? prompt : "Sent input to agent";
                ChatMessage userMsg = new ChatMessage(ChatMessage.Type.USER, displayPrompt);
                userMsg.attachedImagePath = attachedImage;
                userMsg.attachedFiles.addAll(attachedFiles);
                adapter.add(userMsg);
                if (currentSession != null) {
                    currentSession.messages.add(userMsg);
                    sessionManager.saveSession(currentSession);
                }
                scrollToBottom();
                return;
            } else {
                Toast.makeText(this, agent.name + " is currently busy. Tap Stop if you wish to cancel.", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        if (!agentManager.isAgentInstalled(agent) && !agent.id.equals("custom")) {
            boolean elsewhere = agentManager.isAgentInstalledElsewhere(agent);
            new AlertDialog.Builder(this)
                    .setTitle(agent.name + (elsewhere ? " is in the wrong place" : " is not installed"))
                    .setMessage(elsewhere
                            ? "It exists on this phone but NOT where agents run. Open Coding Agents and tap Reinstall on it."
                            : "Install it from the Coding Agents screen first?")
                    .setPositiveButton("Open Agents", (d, w) ->
                            startActivity(new Intent(this, AgentDashboardActivity.class)))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        final boolean isContinuing = hasActiveSession;
        final String command = agentManager.buildGuiPromptCommand(agent, effectivePrompt, workspace(), isContinuing);
        if (command == null || command.trim().isEmpty()) {
            // No non-interactive mode configured for this agent.
            new AlertDialog.Builder(this)
                    .setTitle(agent.name + " has no GUI mode")
                    .setMessage("This agent has no known non-interactive command, so it cannot run "
                            + "inside the chat view. Open it in the terminal instead?\n\n"
                            + "You can also set a command for it from the Coding Agents screen.")
                    .setPositiveButton("Open Terminal", (d, w) -> openInTerminal(effectivePrompt))
                    .setNeutralButton("Set Command", (d, w) ->
                            startActivity(new Intent(this, AgentDashboardActivity.class)))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        if (currentSession == null) {
            String titleText = !prompt.isEmpty() ? prompt : (!pendingAttachedFiles.isEmpty() ? pendingAttachedFiles.get(0) : "Photo Inspection");
            String title = titleText.length() > 45 ? titleText.substring(0, 45) + "…" : titleText;
            currentSession = new ChatSession(
                    "session_" + System.currentTimeMillis(),
                    agent.id,
                    agent.name,
                    agent.iconEmoji,
                    title
            );
        }

        promptInput.setText("");
        pendingAttachedImagePath = null;
        pendingAttachedFiles.clear();
        refreshAttachmentChips();

        String displayPrompt;
        if (!prompt.isEmpty()) {
            displayPrompt = prompt;
        } else if (attachedImage != null) {
            displayPrompt = "Inspect attached photo (" + new File(attachedImage).getName() + ")";
        } else {
            displayPrompt = "Inspect attached project files";
        }
        ChatMessage userMsg = new ChatMessage(ChatMessage.Type.USER, displayPrompt);
        userMsg.attachedImagePath = attachedImage;
        userMsg.attachedFiles.addAll(attachedFiles);
        adapter.add(userMsg);
        currentSession.messages.add(userMsg);
        sessionManager.saveSession(currentSession);

        pendingOutput.setLength(0);
        rowRunning.setVisibility(View.VISIBLE);
        if (progressRunning != null) progressRunning.setVisibility(View.VISIBLE);
        if (btnChatStop != null) btnChatStop.setVisibility(View.VISIBLE);
        runningLabel.setText((isContinuing ? "Continuing " : "Running ") + agent.name + "…");
        findViewById(R.id.btn_chat_send).setEnabled(true);

        executionStartTime = System.currentTimeMillis();
        firstOutputReceived = false;
        if (tvStripLiveStatus != null) {
            tvStripLiveStatus.setText("⚡ Connecting…");
        }
        thinkingTimerHandler.removeCallbacks(thinkingTimerRunnable);
        thinkingTimerHandler.postDelayed(thinkingTimerRunnable, 500);

        final StringBuilder diffBuffer = new StringBuilder();

        runner.run(workspace(), command, new AgentRunner.Listener() {
            @Override
            public void onStarted(String cmd) {
                String env = runner.getLastEnvironment() == AgentRunner.Environment.UBUNTU_WORKSHOP
                        ? "Ubuntu workshop" : "Termux";
                if (progressRunning != null) progressRunning.setVisibility(View.VISIBLE);
                runningLabel.setText((isContinuing ? "Continuing " : "Running ") + agent.name + " in " + env + "…");
                if (tvStripLiveStatus != null) {
                    tvStripLiveStatus.setText("🧠 Thinking…");
                }
            }

            @Override
            public void onOutput(String line) {
                if (!firstOutputReceived) {
                    firstOutputReceived = true;
                    long elapsed = System.currentTimeMillis() - executionStartTime;
                    int seconds = (int) (elapsed / 1000);
                    if (tvStripLiveStatus != null) {
                        tvStripLiveStatus.setText("⚡ Streaming answer (" + seconds + "s)…");
                    }
                    if (runningLabel != null) {
                        runningLabel.setText("⚡ " + agent.name + " is replying… (" + seconds + "s)");
                    }
                }

                String trimmed = line.trim();
                if (trimmed.startsWith("diff ") || trimmed.startsWith("--- ")
                        || trimmed.startsWith("+++ ") || trimmed.startsWith("@@")
                        || trimmed.startsWith("+") || trimmed.startsWith("-")) {
                    if (diffBuffer.length() < 10000) {
                        diffBuffer.append(line).append('\n');
                    }
                }

                if (AgentPermissionDetector.isPermissionPrompt(line)) {
                    flushOutput();
                    AgentPermissionDetector.Request req =
                            AgentPermissionDetector.parse(line, diffBuffer.toString(), agent.name);
                    diffBuffer.setLength(0);

                    ChatMessage permMsg = ChatMessage.createPermissionRequest(
                            req.action,
                            req.target,
                            req.diff,
                            req.promptText,
                            agent.name,
                            req.icon
                    );
                    adapter.add(permMsg);
                    if (currentSession != null) {
                        currentSession.messages.add(permMsg);
                        sessionManager.saveSession(currentSession);
                    }
                    scrollToBottom();
                    return;
                }

                pendingOutput.append(line).append('\n');
                scheduleFlush();
            }

            @Override
            public void onFinished(int exitCode) {
                thinkingTimerHandler.removeCallbacks(thinkingTimerRunnable);
                if (tvStripLiveStatus != null) {
                    tvStripLiveStatus.setText(exitCode == 0 ? "Ready" : "Error (" + exitCode + ")");
                }
                diffBuffer.setLength(0);
                String fullOutput = pendingOutput.toString();
                flushOutput();
                findViewById(R.id.btn_chat_send).setEnabled(true);

                if (exitCode == 0) {
                    hasActiveSession = true;
                    // Keep agent continuous and active & ready!
                    rowRunning.setVisibility(View.VISIBLE);
                    if (progressRunning != null) progressRunning.setVisibility(View.GONE);
                    if (runningLabel != null) {
                        runningLabel.setText("🟢 " + agent.name + " is Active & Ready");
                    }
                    if (btnChatStop != null) {
                        btnChatStop.setVisibility(View.VISIBLE);
                    }
                    promptInput.setHint("Reply to " + agent.name + "…");
                } else {
                    hasActiveSession = false;
                    rowRunning.setVisibility(View.GONE);
                    adapter.markLastAsError();
                    promptInput.setHint("Message " + agent.name + "…");
                    String hint;
                    if (exitCode == 127 || fullOutput.contains("command not found")) {
                        hint = "The agent binary was not found on PATH. Install it from the "
                                + "Coding Agents screen, then retry. If you just installed it, "
                                + "restart the terminal once (exec $SHELL -l).";
                    } else if (fullOutput.contains("unexpected argument")
                            || fullOutput.contains("read only from -p")) {
                        hint = "The saved GUI command for this agent is outdated. Open Coding "
                                + "Agents > " + agent.name + " > edit command > Reset to restore "
                                + "the built-in command, then retry.";
                    } else if (fullOutput.toLowerCase().contains("authenticat")
                            || fullOutput.toLowerCase().contains("not logged in")
                            || fullOutput.toLowerCase().contains("please login")
                            || fullOutput.toLowerCase().contains("sign in")) {
                        hint = "Authentication is required. Sign-in needs the interactive "
                                + "terminal (the chat view cannot answer browser/code prompts).";
                        adapter.add(new ChatMessage(ChatMessage.Type.STATUS,
                                "Exited with code " + exitCode + ". " + hint));
                        scrollToBottom();
                        // Offer the one-tap login path: interactive TUI in the workshop.
                        new AlertDialog.Builder(AgentChatActivity.this)
                                .setTitle(agent.name + " needs sign-in")
                                .setMessage("Open " + agent.name + " in the terminal to complete "
                                        + "sign-in?\n\nFor Antigravity: if no browser opens, copy "
                                        + "the printed URL into your phone browser, sign in, "
                                        + "and paste the code back into the terminal.")
                                .setPositiveButton("Open Terminal",
                                        (d, w) -> openLoginInTerminal(agent))
                                .setNegativeButton("Later", null)
                                .show();
                        return;
                    } else {
                        hint = "If this agent needs an interactive terminal, "
                                + "use the terminal button above.";
                    }
                    adapter.add(new ChatMessage(ChatMessage.Type.STATUS,
                            "Exited with code " + exitCode + ". " + hint));
                }
                if (currentSession != null) {
                    currentSession.messages.clear();
                    currentSession.messages.addAll(adapter.getMessages());
                    sessionManager.saveSession(currentSession);
                }
                scrollToBottom();
            }

            @Override
            public void onFailure(String message) {
                thinkingTimerHandler.removeCallbacks(thinkingTimerRunnable);
                if (tvStripLiveStatus != null) {
                    tvStripLiveStatus.setText("Failed to start");
                }
                hasActiveSession = false;
                rowRunning.setVisibility(View.GONE);
                findViewById(R.id.btn_chat_send).setEnabled(true);
                adapter.add(new ChatMessage(ChatMessage.Type.ERROR, message, agent.name, agent.iconEmoji));
                CLIAgentManager.CLIAgent cur = selectedAgent();
                if (promptInput != null) {
                    promptInput.setHint(cur != null ? "Message " + cur.name + "…" : "Message agent…");
                }
                if (currentSession != null) {
                    currentSession.messages.clear();
                    currentSession.messages.addAll(adapter.getMessages());
                    sessionManager.saveSession(currentSession);
                }
                scrollToBottom();
            }
        });
    }

    private void stopAgent() {
        thinkingTimerHandler.removeCallbacks(thinkingTimerRunnable);
        if (tvStripLiveStatus != null) {
            tvStripLiveStatus.setText("Ready");
        }
        boolean wasRunning = runner != null && runner.isRunning();
        if (wasRunning) {
            runner.cancel();
            flushOutput();
            adapter.add(new ChatMessage(ChatMessage.Type.STATUS, "Session stopped by user"));
        } else if (hasActiveSession) {
            adapter.add(new ChatMessage(ChatMessage.Type.STATUS, "Session ended"));
        }
        hasActiveSession = false;
        if (currentSession != null) {
            currentSession.messages.clear();
            currentSession.messages.addAll(adapter.getMessages());
            sessionManager.saveSession(currentSession);
        }
        if (rowRunning != null) rowRunning.setVisibility(View.GONE);
        findViewById(R.id.btn_chat_send).setEnabled(true);
        CLIAgentManager.CLIAgent agent = selectedAgent();
        if (promptInput != null) {
            promptInput.setHint(agent != null ? "Message " + agent.name + "…" : "Message agent…");
        }
        scrollToBottom();
    }

    private void scheduleFlush() {
        if (flushScheduled) return;
        flushScheduled = true;
        handler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS);
    }

    private void flushOutput() {
        if (pendingOutput.length() == 0) return;
        String raw = pendingOutput.toString();
        pendingOutput.setLength(0);

        CLIAgentManager.CLIAgent agent = selectedAgent();
        String name = agent == null ? "Agent" : agent.name;
        String icon = agent == null ? "" : agent.iconEmoji;

        List<ChatMessage> parsed = AgentOutputParser.parse(raw, name, icon);
        for (ChatMessage message : parsed) {
            adapter.appendOrAdd(message);
        }
        if (currentSession != null) {
            currentSession.messages.clear();
            currentSession.messages.addAll(adapter.getMessages());
            sessionManager.saveSession(currentSession);
        }
        if (!parsed.isEmpty()) scrollToBottom();
    }

    private void scrollToBottom() {
        if (adapter.size() == 0) return;
        recyclerView.post(() -> recyclerView.smoothScrollToPosition(adapter.size() - 1));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String workspace() {
        SharedPreferences prefs = getSharedPreferences(PREFS_WORKSPACE, MODE_PRIVATE);
        String path = prefs.getString(KEY_WORKSPACE, null);
        if (!TextUtils.isEmpty(path) && new File(path).isDirectory()) {
            return path;
        }
        return TermuxConstants.TERMUX_HOME_DIR_PATH;
    }

    /** Fall back to the proven interactive terminal path for this agent. */
    private void openInTerminal(String prompt) {
        Intent intent = new Intent(this, TermuxActivity.class);
        intent.putExtra("com.termux.execute.cwd", workspace());
        CLIAgentManager.CLIAgent agent = selectedAgent();
        if (agent != null) {
            String cmd = agentManager.buildLaunchCommand(agent, prompt == null ? "" : prompt, workspace());
            intent.putExtra("com.termux.execute.command", cmd);
        }
        if (!TextUtils.isEmpty(prompt)) {
            intent.putExtra("com.termux.execute.prompt", prompt);
        }
        startActivity(intent);
        overridePendingTransition(R.anim.slide_up, R.anim.stay);
    }

    /** Interactive sign-in: full TUI (not -p) so OAuth / API-key flows can complete. */
    private void openLoginInTerminal(CLIAgentManager.CLIAgent agent) {
        Intent intent = new Intent(this, TermuxActivity.class);
        intent.putExtra("com.termux.execute.cwd", workspace());
        intent.putExtra("com.termux.execute.command",
                agentManager.buildLoginCommand(agent, workspace()));
        startActivity(intent);
        overridePendingTransition(R.anim.slide_up, R.anim.stay);
    }

    private void setupPermissionActionListener() {
        adapter.setOnPermissionActionListener(new ChatMessageAdapter.OnPermissionActionListener() {
            @Override
            public void onAllow(ChatMessage message, int position) {
                runner.sendInput("y\n");
                message.permissionDecision = ChatMessage.PermissionDecision.ALLOWED;
                adapter.notifyItemChanged(position);
                if (currentSession != null) {
                    currentSession.messages.clear();
                    currentSession.messages.addAll(adapter.getMessages());
                    sessionManager.saveSession(currentSession);
                }
                String target = message.permissionTarget != null ? message.permissionTarget : "action";
                Toast.makeText(AgentChatActivity.this, "✅ Allowed: " + target, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onReject(ChatMessage message, int position) {
                runner.sendInput("n\n");
                message.permissionDecision = ChatMessage.PermissionDecision.REJECTED;
                adapter.notifyItemChanged(position);
                if (currentSession != null) {
                    currentSession.messages.clear();
                    currentSession.messages.addAll(adapter.getMessages());
                    sessionManager.saveSession(currentSession);
                }
                Toast.makeText(AgentChatActivity.this, "❌ Rejected", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onViewDiff(ChatMessage message, int position) {
                showDiffDialog(message, position);
            }
        });
    }

    private void showDiffDialog(ChatMessage message, int position) {
        String diffText = message.diffContent;
        if (diffText == null || diffText.trim().isEmpty()) {
            diffText = "(No detailed diff captured. Target: " + (message.permissionTarget != null ? message.permissionTarget : "file") + ")";
        }

        android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder();
        String[] lines = diffText.split("\n");
        for (String line : lines) {
            int start = ssb.length();
            ssb.append(line).append("\n");
            int end = ssb.length();
            if (line.startsWith("+")) {
                ssb.setSpan(new android.text.style.ForegroundColorSpan(0xFF3FB950), start, end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.startsWith("-")) {
                ssb.setSpan(new android.text.style.ForegroundColorSpan(0xFFF85149), start, end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.startsWith("@@")) {
                ssb.setSpan(new android.text.style.ForegroundColorSpan(0xFF58A6FF), start, end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                ssb.setSpan(new android.text.style.ForegroundColorSpan(0xFFC9D1D9), start, end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        TextView tv = new TextView(this);
        tv.setText(ssb);
        tv.setTextSize(12);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextColor(0xFFE0E0E0);
        tv.setTextIsSelectable(true);
        tv.setPadding(32, 24, 32, 24);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setBackgroundColor(0xFF0D1117);
        sv.addView(tv);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("📝 Diff: " + (message.permissionTarget != null ? message.permissionTarget : "Changes"))
                .setView(sv)
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy Diff", (dialog, which) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("diff", message.diffContent));
                        Toast.makeText(this, "Diff copied to clipboard", Toast.LENGTH_SHORT).show();
                    }
                });

        if (message.permissionDecision == ChatMessage.PermissionDecision.PENDING) {
            builder.setNegativeButton("✅ Allow", (dialog, which) -> {
                runner.sendInput("y\n");
                message.permissionDecision = ChatMessage.PermissionDecision.ALLOWED;
                adapter.notifyItemChanged(position);
                if (currentSession != null) {
                    currentSession.messages.clear();
                    currentSession.messages.addAll(adapter.getMessages());
                    sessionManager.saveSession(currentSession);
                }
                Toast.makeText(this, "✅ Allowed: " + message.permissionTarget, Toast.LENGTH_SHORT).show();
            });
        }

        builder.show();
    }

    private void showWelcome() {
        if (adapter.size() > 0) return;
        String env = AgentRunner.isWorkshopAvailable()
                ? "the Ubuntu workshop — the same environment the terminal opens in"
                : "Termux. Open the terminal once to set up the Ubuntu workshop";
        adapter.add(new ChatMessage(ChatMessage.Type.STATUS,
                "Pick an agent above and describe what you want it to do.\n"
                        + "Agents run in " + env + "; their output appears here."));
    }

    // ------------------------------------------------------------------
    // Model & Thinking selection dialogs
    // ------------------------------------------------------------------

    private void updateModelAndThinkingChips() {
        CLIAgentManager.CLIAgent agent = selectedAgent();
        if (agent == null) return;

        if (tvCurrentModel != null) {
            String model = agentManager.getAgentModel(agent.id);
            tvCurrentModel.setText(model);
        }

        if (tvCurrentThinking != null) {
            String thinking = agentManager.getAgentThinkingLevel(agent.id);
            if (thinking == null || thinking.isEmpty() || thinking.startsWith("Off")) {
                tvCurrentThinking.setText("🧠 Off");
            } else {
                tvCurrentThinking.setText("🧠 " + thinking);
            }
        }
    }

    private void showModelSelectionDialog() {
        CLIAgentManager.CLIAgent agent = selectedAgent();
        if (agent == null) return;

        List<String> models = agentManager.getAvailableModelsForAgent(agent.id);
        String currentModel = agentManager.getAgentModel(agent.id);
        String defaultModel = CLIAgentManager.defaultModelForAgent(agent.id);

        List<String> displayItems = new ArrayList<>();
        int selectedIndex = 0;
        displayItems.add("Default (" + defaultModel + ")");
        for (int i = 0; i < models.size(); i++) {
            String m = models.get(i);
            displayItems.add(m);
            if (m.equalsIgnoreCase(currentModel)) {
                selectedIndex = i + 1;
            }
        }
        displayItems.add("🔄 Query / Refresh from CLI (" + agent.name + ")");
        displayItems.add("✏️ Custom model…");

        final int refreshIndex = displayItems.size() - 2;
        final int customIndex = displayItems.size() - 1;

        if (selectedIndex == 0 && currentModel != null
                && !currentModel.equalsIgnoreCase("default")
                && !currentModel.equalsIgnoreCase(defaultModel)) {
            selectedIndex = customIndex;
        }

        String[] itemArray = displayItems.toArray(new String[0]);

        new AlertDialog.Builder(this)
                .setTitle("Select Model (" + agent.name + ")")
                .setSingleChoiceItems(itemArray, selectedIndex, (dialog, which) -> {
                    if (which == 0) {
                        agentManager.setAgentModel(agent.id, null);
                        updateModelAndThinkingChips();
                        Toast.makeText(this, "Model set to Default (" + defaultModel + ")", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    } else if (which == refreshIndex) {
                        dialog.dismiss();
                        Toast.makeText(this, "Querying " + agent.name + " CLI for available models…", Toast.LENGTH_SHORT).show();
                        agentManager.queryLiveModelsAsync(agent, liveModels -> {
                            updateModelAndThinkingChips();
                            Toast.makeText(this, "Loaded " + liveModels.size() + " models from " + agent.name, Toast.LENGTH_SHORT).show();
                            showModelSelectionDialog();
                        });
                    } else if (which == customIndex) {
                        dialog.dismiss();
                        showCustomModelInputDialog(agent);
                    } else {
                        String selected = models.get(which - 1);
                        agentManager.setAgentModel(agent.id, selected);
                        updateModelAndThinkingChips();
                        Toast.makeText(this, "Model set to " + selected, Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showCustomModelInputDialog(CLIAgentManager.CLIAgent agent) {
        EditText input = new EditText(this);
        input.setHint("e.g. claude-3-7-sonnet, gemini-2.5-pro, o3-mini");
        String current = agentManager.getAgentModel(agent.id);
        if (current != null) input.setText(current);
        input.setPadding(32, 24, 32, 24);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF888888);

        new AlertDialog.Builder(this)
                .setTitle("Custom Model for " + agent.name)
                .setView(input)
                .setPositiveButton("Set", (dialog, which) -> {
                    String val = input.getText().toString().trim();
                    if (!val.isEmpty()) {
                        agentManager.setAgentModel(agent.id, val);
                        updateModelAndThinkingChips();
                        Toast.makeText(this, "Model changed to " + val, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showThinkingLevelSelectionDialog() {
        CLIAgentManager.CLIAgent agent = selectedAgent();
        if (agent == null) return;

        List<String> levels = agentManager.getAvailableThinkingLevels();
        String currentLevel = agentManager.getAgentThinkingLevel(agent.id);

        String[] labels = new String[levels.size()];
        int selectedIndex = 0;
        for (int i = 0; i < levels.size(); i++) {
            String lvl = levels.get(i);
            labels[i] = lvl;
            if (lvl.equalsIgnoreCase(currentLevel)) {
                selectedIndex = i;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("🧠 Thinking Budget (" + agent.name + ")")
                .setSingleChoiceItems(labels, selectedIndex, (dialog, which) -> {
                    String selected = levels.get(which);
                    agentManager.setAgentThinkingLevel(agent.id, selected);
                    updateModelAndThinkingChips();
                    Toast.makeText(this, "Thinking set to " + selected, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Attachment & @file tagging helpers
    // ------------------------------------------------------------------

    private void launchPhotoPicker() {
        try {
            imagePickerLauncher.launch("image/*");
        } catch (Exception e) {
            Toast.makeText(this, "Could not open image picker: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showAttachOptionsDialog() {
        String[] options = new String[]{
                "📷  Pick & Send Photo",
                "📄  Tag Project File (@file)",
                "🖼️  Tag Workspace Image"
        };
        new AlertDialog.Builder(this)
                .setTitle("Attachments & Tags")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        launchPhotoPicker();
                    } else if (which == 1) {
                        showFilePickerDialog();
                    } else if (which == 2) {
                        showWorkspaceImagePickerDialog();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void handleImagePicked(Uri uri) {
        if (uri == null) return;
        try {
            File dir = new File(workspace(), ".attachments");
            if (!dir.exists()) dir.mkdirs();
            String ext = ".png";
            String mime = getContentResolver().getType(uri);
            if (mime != null && mime.contains("jpeg")) ext = ".jpg";
            File dest = new File(dir, "img_" + System.currentTimeMillis() + ext);

            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(dest)) {
                if (in == null) return;
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }

            pendingAttachedImagePath = dest.getAbsolutePath();
            refreshAttachmentChips();
            Toast.makeText(this, "Attached image: " + dest.getName(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to load image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showFilePickerDialog() {
        File root = new File(workspace());
        if (!root.exists() || !root.isDirectory()) {
            Toast.makeText(this, "Workspace directory not found: " + workspace(), Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> fileList = new ArrayList<>();
        collectProjectFiles(root, root, fileList, 250);

        if (fileList.isEmpty()) {
            Toast.makeText(this, "No project files found in workspace.", Toast.LENGTH_SHORT).show();
            return;
        }

        Collections.sort(fileList);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 8);

        EditText searchInput = new EditText(this);
        searchInput.setHint("Search project files…");
        searchInput.setTextColor(0xFFFFFFFF);
        searchInput.setHintTextColor(0xFF888888);
        searchInput.setBackgroundResource(R.drawable.bg_chat_input_pill);
        searchInput.setPadding(24, 16, 24, 16);
        searchInput.setTextSize(13);
        layout.addView(searchInput);

        ArrayAdapter<String> fileAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, new ArrayList<>(fileList)) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(0xFFE0E0E0);
                tv.setTextSize(13);
                tv.setText("📄  " + getItem(position));
                return tv;
            }
        };

        ListView listView = new ListView(this);
        listView.setAdapter(fileAdapter);
        listView.setDividerHeight(1);
        layout.addView(listView);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Tag Project File (@file)")
                .setView(layout)
                .setNegativeButton("Cancel", null)
                .create();

        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().toLowerCase().trim();
                fileAdapter.clear();
                for (String f : fileList) {
                    if (query.isEmpty() || f.toLowerCase().contains(query)) {
                        fileAdapter.add(f);
                    }
                }
                fileAdapter.notifyDataSetChanged();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            String selected = fileAdapter.getItem(position);
            if (selected != null) {
                if (!pendingAttachedFiles.contains(selected)) {
                    pendingAttachedFiles.add(selected);
                    refreshAttachmentChips();
                    Toast.makeText(this, "Tagged @" + selected, Toast.LENGTH_SHORT).show();
                }
                String currentText = promptInput.getText().toString();
                if (currentText.endsWith("@")) {
                    promptInput.setText(currentText + selected + " ");
                } else if (currentText.isEmpty()) {
                    promptInput.setText("@" + selected + " ");
                } else {
                    promptInput.append("@" + selected + " ");
                }
                promptInput.setSelection(promptInput.getText().length());
            }
            dialog.dismiss();
        });

        dialog.show();
    }

    private void showWorkspaceImagePickerDialog() {
        File root = new File(workspace());
        if (!root.exists() || !root.isDirectory()) {
            Toast.makeText(this, "Workspace directory not found: " + workspace(), Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> imageList = new ArrayList<>();
        collectProjectImages(root, root, imageList, 150);

        if (imageList.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No Workspace Images")
                    .setMessage("No image files found in the current workspace. Pick a photo from device storage instead?")
                    .setPositiveButton("Pick Photo", (d, w) -> launchPhotoPicker())
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        Collections.sort(imageList);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 8);

        EditText searchInput = new EditText(this);
        searchInput.setHint("Search workspace images…");
        searchInput.setTextColor(0xFFFFFFFF);
        searchInput.setHintTextColor(0xFF888888);
        searchInput.setBackgroundResource(R.drawable.bg_chat_input_pill);
        searchInput.setPadding(24, 16, 24, 16);
        searchInput.setTextSize(13);
        layout.addView(searchInput);

        ArrayAdapter<String> imgAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, new ArrayList<>(imageList)) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(0xFFE0E0E0);
                tv.setTextSize(13);
                tv.setText("🖼️  " + getItem(position));
                return tv;
            }
        };

        ListView listView = new ListView(this);
        listView.setAdapter(imgAdapter);
        listView.setDividerHeight(1);
        layout.addView(listView);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Tag Workspace Photo / Image")
                .setView(layout)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Pick from Gallery", (d, w) -> launchPhotoPicker())
                .create();

        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().toLowerCase().trim();
                imgAdapter.clear();
                for (String f : imageList) {
                    if (query.isEmpty() || f.toLowerCase().contains(query)) {
                        imgAdapter.add(f);
                    }
                }
                imgAdapter.notifyDataSetChanged();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            String selected = imgAdapter.getItem(position);
            if (selected != null) {
                File fullFile = new File(root, selected);
                pendingAttachedImagePath = fullFile.getAbsolutePath();
                refreshAttachmentChips();
                Toast.makeText(this, "Tagged image: " + fullFile.getName(), Toast.LENGTH_SHORT).show();

                String currentText = promptInput.getText().toString();
                if (currentText.endsWith("@")) {
                    promptInput.setText(currentText + selected + " ");
                } else if (currentText.isEmpty()) {
                    promptInput.setText("@" + selected + " ");
                } else {
                    promptInput.append("@" + selected + " ");
                }
                promptInput.setSelection(promptInput.getText().length());
            }
            dialog.dismiss();
        });

        dialog.show();
    }

    private void collectProjectImages(File root, File current, List<String> result, int maxFiles) {
        if (result.size() >= maxFiles) return;
        File[] files = current.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(".") && !name.equals(".attachments")) continue;
            if (name.equals("node_modules") || name.equals("build")
                    || name.equals(".git") || name.equals(".gradle") || name.equals(".idea")
                    || name.equals("__pycache__") || name.equals("dist") || name.equals("out")) {
                continue;
            }
            if (f.isDirectory()) {
                collectProjectImages(root, f, result, maxFiles);
            } else {
                String lower = name.toLowerCase();
                if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                        || lower.endsWith(".webp") || lower.endsWith(".svg") || lower.endsWith(".gif")
                        || lower.endsWith(".bmp")) {
                    String rel = root.toURI().relativize(f.toURI()).getPath();
                    result.add(rel);
                    if (result.size() >= maxFiles) return;
                }
            }
        }
    }

    private void setupCompactSlashSuggestions() {
        if (listSlashSuggestions == null) return;

        slashCommandsAdapter = new ArrayAdapter<AgentSlashCommands.Command>(this, 0, visibleSlashCommands) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_slash_command, parent, false);
                }
                AgentSlashCommands.Command cmd = getItem(position);
                if (cmd != null) {
                    TextView icon = convertView.findViewById(R.id.slash_cmd_icon);
                    TextView name = convertView.findViewById(R.id.slash_cmd_name);
                    TextView title = convertView.findViewById(R.id.slash_cmd_title);
                    TextView desc = convertView.findViewById(R.id.slash_cmd_desc);
                    TextView badge = convertView.findViewById(R.id.slash_cmd_badge);

                    icon.setText(cmd.icon);
                    name.setText(cmd.name);
                    title.setText(cmd.title);
                    desc.setText(cmd.description);
                    badge.setText(cmd.isLocalAction ? "App" : "Agent");
                    badge.setTextColor(cmd.isLocalAction ? 0xFF00E676 : 0xFF58A6FF);
                }
                return convertView;
            }
        };

        listSlashSuggestions.setAdapter(slashCommandsAdapter);

        listSlashSuggestions.setOnItemClickListener((parent, view, position, id) -> {
            AgentSlashCommands.Command cmd = slashCommandsAdapter.getItem(position);
            if (cmd != null) {
                applySlashCommand(cmd);
            }
        });

        if (btnCloseSlashPanel != null) {
            btnCloseSlashPanel.setOnClickListener(v -> hideCompactSlashSuggestions());
        }
    }

    private void showCompactSlashSuggestions(String query) {
        if (cardSlashSuggestions == null || listSlashSuggestions == null) return;

        CLIAgentManager.CLIAgent agent = selectedAgent();
        String agentId = agent != null ? agent.id : "";
        String agentName = agent != null ? agent.name : "Agent";
        String agentEmoji = agent != null ? agent.iconEmoji : "⚡";

        if (tvSlashPanelTitle != null) {
            tvSlashPanelTitle.setText(agentEmoji + " " + agentName + " Slash Commands");
        }

        List<AgentSlashCommands.Command> filtered = AgentSlashCommands.filterCommands(agentId, query);
        if (filtered.isEmpty()) {
            cardSlashSuggestions.setVisibility(View.GONE);
            return;
        }

        visibleSlashCommands.clear();
        visibleSlashCommands.addAll(filtered);
        slashCommandsAdapter.notifyDataSetChanged();

        float density = getResources().getDisplayMetrics().density;
        int targetDp = Math.min(filtered.size() * 45, 160);
        int targetPx = (int) (targetDp * density);
        ViewGroup.LayoutParams lp = listSlashSuggestions.getLayoutParams();
        if (lp != null) {
            lp.height = Math.max(targetPx, (int) (45 * density));
            listSlashSuggestions.setLayoutParams(lp);
        }

        cardSlashSuggestions.setVisibility(View.VISIBLE);
    }

    private void hideCompactSlashSuggestions() {
        if (cardSlashSuggestions != null) {
            cardSlashSuggestions.setVisibility(View.GONE);
        }
    }

    private void toggleCompactSlashSuggestions() {
        if (cardSlashSuggestions != null && cardSlashSuggestions.getVisibility() == View.VISIBLE) {
            hideCompactSlashSuggestions();
        } else {
            String text = promptInput != null ? promptInput.getText().toString() : "";
            if (!text.startsWith("/")) {
                if (promptInput != null) {
                    promptInput.setText("/");
                    promptInput.setSelection(1);
                    promptInput.requestFocus();
                    com.termux.shared.view.KeyboardUtils.showSoftKeyboard(this, promptInput);
                }
            }
            showCompactSlashSuggestions(promptInput != null ? promptInput.getText().toString() : "");
        }
    }

    private void applySlashCommand(AgentSlashCommands.Command cmd) {
        hideCompactSlashSuggestions();
        if (cmd == null || promptInput == null) return;

        if (cmd.isLocalAction) {
            if (cmd.name.equals("/clear")) {
                promptInput.setText("");
                startNewSession();
                return;
            } else if (cmd.name.equals("/terminal")) {
                promptInput.setText("");
                openInTerminal(null);
                return;
            } else if (cmd.name.equals("/model")) {
                promptInput.setText("");
                showModelSelectionDialog();
                return;
            } else if (cmd.name.equals("/thinking")) {
                promptInput.setText("");
                showThinkingLevelSelectionDialog();
                return;
            }
        }

        String toInsert = cmd.requiresArgs ? (cmd.name + " ") : cmd.name;
        promptInput.setText(toInsert);
        promptInput.setSelection(promptInput.getText().length());
        promptInput.requestFocus();
        com.termux.shared.view.KeyboardUtils.showSoftKeyboard(this, promptInput);
    }

    private void collectProjectFiles(File root, File current, List<String> result, int maxFiles) {
        if (result.size() >= maxFiles) return;
        File[] files = current.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(".") || name.equals("node_modules") || name.equals("build")
                    || name.equals(".git") || name.equals(".gradle") || name.equals(".idea")
                    || name.equals("__pycache__") || name.equals("dist") || name.equals("out")) {
                continue;
            }
            if (f.isDirectory()) {
                collectProjectFiles(root, f, result, maxFiles);
            } else {
                String rel = root.toURI().relativize(f.toURI()).getPath();
                result.add(rel);
                if (result.size() >= maxFiles) return;
            }
        }
    }

    private void refreshAttachmentChips() {
        if (containerAttachments == null || scrollAttachments == null) return;
        containerAttachments.removeAllViews();
        boolean hasAny = false;
        LayoutInflater inflater = LayoutInflater.from(this);

        if (pendingAttachedImagePath != null) {
            hasAny = true;
            View chip = inflater.inflate(R.layout.item_attachment_chip, containerAttachments, false);
            TextView chipIcon = chip.findViewById(R.id.chip_icon);
            TextView chipText = chip.findViewById(R.id.chip_text);
            View removeBtn = chip.findViewById(R.id.chip_remove);

            chipIcon.setText("🖼️");
            File img = new File(pendingAttachedImagePath);
            chipText.setText(img.getName());
            removeBtn.setOnClickListener(v -> {
                pendingAttachedImagePath = null;
                refreshAttachmentChips();
            });
            containerAttachments.addView(chip);
        }

        for (String filePath : pendingAttachedFiles) {
            hasAny = true;
            View chip = inflater.inflate(R.layout.item_attachment_chip, containerAttachments, false);
            TextView chipIcon = chip.findViewById(R.id.chip_icon);
            TextView chipText = chip.findViewById(R.id.chip_text);
            View removeBtn = chip.findViewById(R.id.chip_remove);

            chipIcon.setText("📄");
            chipText.setText(filePath);
            removeBtn.setOnClickListener(v -> {
                pendingAttachedFiles.remove(filePath);
                refreshAttachmentChips();
            });
            containerAttachments.addView(chip);
        }

        scrollAttachments.setVisibility(hasAny ? View.VISIBLE : View.GONE);
    }
}
