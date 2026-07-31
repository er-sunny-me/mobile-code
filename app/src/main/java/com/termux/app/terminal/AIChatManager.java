package com.termux.app.terminal;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalSession;

public class AIChatManager {
    private final TermuxActivity activity;
    private final CLIAgentManager cliAgentManager;
    private boolean hasLaunchedInitialAgent = false;

    public AIChatManager(TermuxActivity activity) {
        this.activity = activity;
        this.cliAgentManager = new CLIAgentManager(activity);
        
        Spinner providerSpinner = activity.findViewById(R.id.chat_provider_spinner);
        
        if (providerSpinner != null) {
            providerSpinner.setVisibility(View.VISIBLE);
            
            String[] providers = { "Claude Code", "Antigravity CLI", "Aider", "Codex CLI", "Gemini CLI", "Custom Agent" };
            String[] providerIds = { "claude_code", "antigravity", "aider", "codex", "gemini_cli", "custom" };
            
            ArrayAdapter<String> spinAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, providers);
            spinAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            providerSpinner.setAdapter(spinAdapter);
            
            // Set default to Custom Agent if no agent is explicitly saved
            String selectedId = cliAgentManager.getSelectedAgent().id;
            if (activity.getSharedPreferences("cli_agent_prefs", Context.MODE_PRIVATE).getString("selected_agent", null) == null) {
                selectedId = "custom";
                cliAgentManager.setSelectedAgent(selectedId);
            }

            for (int i = 0; i < providerIds.length; i++) {
                if (providerIds[i].equals(selectedId)) {
                    providerSpinner.setSelection(i);
                    break;
                }
            }
            
            providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    CLIAgentManager.CLIAgent agent = CLIAgentManager.AGENTS[position];
                    
                    if (!hasLaunchedInitialAgent) {
                        cliAgentManager.setSelectedAgent(agent.id);
                        hasLaunchedInitialAgent = true;
                        launchAgent(agent, true);
                    } else if (!agent.id.equals(cliAgentManager.getSelectedAgent().id)) {
                        cliAgentManager.setSelectedAgent(agent.id);
                        launchAgent(agent, false);
                    }
                }
                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
    }

    private void launchAgent(CLIAgentManager.CLIAgent agent, boolean isInitial) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            int retries = 0;
            @Override
            public void run() {
                TerminalSession currentSession = activity.getCurrentSession();
                if (currentSession != null) {
                    String workspace = activity.getSharedPreferences("ZeroCodePrefs", Context.MODE_PRIVATE)
                            .getString("workspace_path", "/data/data/com.termux/files/home");
                    String cmd = cliAgentManager.buildLaunchCommand(agent, "", workspace);
                    
                    if (isInitial) {
                        currentSession.write("clear && " + cmd + "\r");
                    } else {
                        // Create a NEW session for the new agent
                        activity.getTermuxTerminalSessionClient().addNewSession(false, agent.name);
                        
                        // Wait for the new session to become active
                        handler.postDelayed(() -> {
                            TerminalSession newSession = activity.getCurrentSession();
                            if (newSession != null && newSession != currentSession) {
                                // Kill the old session
                                currentSession.finishIfRunning();
                                // Run command in new session
                                newSession.write("clear && " + cmd + "\r");
                            } else {
                                // Fallback just in case
                                currentSession.write("\003");
                                currentSession.write("clear && " + cmd + "\r");
                            }
                        }, 500);
                    }
                } else if (retries < 20) {
                    // Wait up to 2 seconds for terminal session to be created
                    retries++;
                    handler.postDelayed(this, 100);
                }
            }
        });
    }
}
