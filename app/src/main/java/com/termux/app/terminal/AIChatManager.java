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
            
            java.util.List<CLIAgentManager.CLIAgent> enabledAgents = new java.util.ArrayList<>();
            for (CLIAgentManager.CLIAgent agent : CLIAgentManager.AGENTS) {
                if (cliAgentManager.isAgentEnabled(agent.id)) {
                    enabledAgents.add(agent);
                }
            }

            String[] providers = new String[enabledAgents.size()];
            String[] providerIds = new String[enabledAgents.size()];
            for (int i = 0; i < enabledAgents.size(); i++) {
                providers[i] = enabledAgents.get(i).name;
                providerIds[i] = enabledAgents.get(i).id;
            }
            
            ArrayAdapter<String> spinAdapter = new ArrayAdapter<>(activity, R.layout.spinner_item_white, providers);
            spinAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_white);
            providerSpinner.setAdapter(spinAdapter);
            
            // Set default to Custom Agent if no agent is explicitly saved
            String selectedId = cliAgentManager.getSelectedAgent().id;
            if (activity.getSharedPreferences("cli_agent_prefs", Context.MODE_PRIVATE).getString("selected_agent", null) == null) {
                selectedId = "custom";
                if (!cliAgentManager.isAgentEnabled("custom") && !enabledAgents.isEmpty()) {
                    selectedId = enabledAgents.get(0).id;
                }
                cliAgentManager.setSelectedAgent(selectedId);
            }

            boolean foundSelection = false;
            for (int i = 0; i < providerIds.length; i++) {
                if (providerIds[i].equals(selectedId)) {
                    providerSpinner.setSelection(i);
                    foundSelection = true;
                    break;
                }
            }
            if (!foundSelection && !enabledAgents.isEmpty()) {
                providerSpinner.setSelection(0);
                cliAgentManager.setSelectedAgent(providerIds[0]);
            }
            
            providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (position < 0 || position >= enabledAgents.size()) return;
                    CLIAgentManager.CLIAgent agent = enabledAgents.get(position);
                    
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
                        currentSession.write("clear && " + cmd + "\r\n");
                    } else {
                        // Send Ctrl+C to kill running foreground process
                        currentSession.write("\003");
                        // Send Ctrl+U to clear any typed text
                        currentSession.write("\025");
                        handler.postDelayed(() -> {
                            currentSession.write("clear && " + cmd + "\r\n");
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
