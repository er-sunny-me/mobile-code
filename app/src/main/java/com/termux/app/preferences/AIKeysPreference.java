package com.termux.app.preferences;

import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import androidx.appcompat.widget.SwitchCompat;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.termux.R;
import com.termux.app.terminal.CLIAgentManager;
import java.util.ArrayList;
import java.util.List;

public class AIKeysPreference extends Preference {

    private CLIAgentManager agentManager;
    private Spinner spinnerProvider;
    private ArrayAdapter<String> adapter;
    private List<String> enabledProviderIds;
    private List<String> enabledProviders;

    public AIKeysPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_ai_keys);
        agentManager = new CLIAgentManager(context);
    }

    private void updateSpinnerAdapter() {
        enabledProviderIds = new ArrayList<>();
        enabledProviders = new ArrayList<>();
        
        for (CLIAgentManager.CLIAgent agent : CLIAgentManager.AGENTS) {
            if (agentManager.isAgentEnabled(agent.id)) {
                enabledProviderIds.add(agent.id);
                enabledProviders.add(agent.name);
            }
        }

        if (adapter != null) {
            adapter.clear();
            adapter.addAll(enabledProviders);
            adapter.notifyDataSetChanged();
        } else {
            adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, enabledProviders);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerProvider.setAdapter(adapter);
        }

        String selectedId = agentManager.getSelectedAgent().id;
        int index = enabledProviderIds.indexOf(selectedId);
        
        if (index != -1) {
            spinnerProvider.setSelection(index);
        } else if (!enabledProviderIds.isEmpty()) {
            spinnerProvider.setSelection(0);
            agentManager.setSelectedAgent(enabledProviderIds.get(0));
        }
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false); // Make it focusable but not clickable

        spinnerProvider = (Spinner) holder.findViewById(R.id.spinner_ai_provider);
        View customContainer = holder.findViewById(R.id.custom_command_container);
        EditText etCustomCommand = (EditText) holder.findViewById(R.id.et_custom_command);

        updateSpinnerAdapter();

        etCustomCommand.setText(agentManager.getCustomCommand());

        View anthropicContainer = holder.findViewById(R.id.anthropic_settings_container);
        EditText etAnthropicToken = (EditText) holder.findViewById(R.id.et_anthropic_auth_token);
        EditText etAnthropicBaseUrl = (EditText) holder.findViewById(R.id.et_anthropic_base_url);
        EditText etAnthropicModel = (EditText) holder.findViewById(R.id.et_anthropic_model);

        etAnthropicToken.setText(agentManager.getAnthropicAuthToken());
        etAnthropicBaseUrl.setText(agentManager.getAnthropicBaseUrl());
        etAnthropicModel.setText(agentManager.getAnthropicModel());

        // Setup visibility toggles
        LinearLayout agentsToggleContainer = (LinearLayout) holder.findViewById(R.id.agents_toggle_container);
        if (agentsToggleContainer != null) {
            agentsToggleContainer.removeAllViews();
            for (CLIAgentManager.CLIAgent agent : CLIAgentManager.AGENTS) {
                LinearLayout row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                rowParams.setMargins(0, 8, 0, 8);
                row.setLayoutParams(rowParams);

                SwitchCompat switchView = new SwitchCompat(getContext());
                LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f
                );
                switchView.setLayoutParams(switchParams);
                
                switchView.setText(agent.name);
                switchView.setTextColor(Color.WHITE);
                switchView.setShowText(false);
                switchView.setTextOn("");
                switchView.setTextOff("");
                switchView.setChecked(agentManager.isAgentEnabled(agent.id));
                switchView.setPadding(0, 16, 0, 16);
                switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    agentManager.setAgentEnabled(agent.id, isChecked);
                    updateSpinnerAdapter();
                });
                
                row.addView(switchView);

                if (agent.installCommand != null && !agent.installCommand.isEmpty()) {
                    android.widget.Button btnDownload = new android.widget.Button(getContext());
                    btnDownload.setText("Download");
                    btnDownload.setTextColor(Color.WHITE);
                    btnDownload.setTextSize(12f);
                    
                    LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    btnDownload.setLayoutParams(btnParams);
                    btnDownload.setOnClickListener(v -> {
                        android.content.Intent intent = new android.content.Intent(getContext(), com.termux.app.TermuxActivity.class);
                        intent.setAction("com.termux.app.INSTALL_AGENT");
                        intent.putExtra("agent_id", agent.id);
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        getContext().startActivity(intent);
                    });
                    row.addView(btnDownload);
                }

                agentsToggleContainer.addView(row);
            }
        }

        spinnerProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < enabledProviderIds.size()) {
                    String selectedAgentId = enabledProviderIds.get(position);
                    agentManager.setSelectedAgent(selectedAgentId);
                    
                    customContainer.setVisibility(selectedAgentId.equals("custom") ? View.VISIBLE : View.GONE);
                    anthropicContainer.setVisibility(selectedAgentId.equals("claude_code") ? View.VISIBLE : View.GONE);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        String currentId = agentManager.getSelectedAgent().id;
        customContainer.setVisibility(currentId.equals("custom") ? View.VISIBLE : View.GONE);
        anthropicContainer.setVisibility(currentId.equals("claude_code") ? View.VISIBLE : View.GONE);

        etCustomCommand.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                agentManager.setCustomCommand(s.toString());
            }
        });

        etAnthropicToken.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                agentManager.setAnthropicAuthToken(s.toString());
            }
        });

        etAnthropicBaseUrl.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                agentManager.setAnthropicBaseUrl(s.toString());
            }
        });

        etAnthropicModel.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                agentManager.setAnthropicModel(s.toString());
            }
        });
    }
}
