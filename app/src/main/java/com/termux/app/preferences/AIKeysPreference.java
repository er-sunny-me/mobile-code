package com.termux.app.preferences;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.termux.R;
import com.termux.app.terminal.CLIAgentManager;

public class AIKeysPreference extends Preference {

    private String[] providers = {
            "Claude Code",
            "Antigravity CLI",
            "Aider",
            "Codex CLI",
            "Gemini CLI",
            "Custom Agent"
    };

    private String[] providerIds = {
            "claude_code",
            "antigravity",
            "aider",
            "codex",
            "gemini_cli",
            "custom"
    };

    private CLIAgentManager agentManager;

    public AIKeysPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_ai_keys);
        agentManager = new CLIAgentManager(context);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false); // Make it focusable but not clickable

        Spinner spinnerProvider = (Spinner) holder.findViewById(R.id.spinner_ai_provider);
        LinearLayout customContainer = (LinearLayout) holder.findViewById(R.id.custom_command_container);
        EditText etCustomCommand = (EditText) holder.findViewById(R.id.et_custom_command);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(), android.R.layout.simple_spinner_item, providers);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProvider.setAdapter(adapter);

        // Set initial selection
        String selectedId = agentManager.getSelectedAgent().id;
        for (int i = 0; i < providerIds.length; i++) {
            if (providerIds[i].equals(selectedId)) {
                spinnerProvider.setSelection(i);
                if (selectedId.equals("custom")) {
                    customContainer.setVisibility(View.VISIBLE);
                } else {
                    customContainer.setVisibility(View.GONE);
                }
                break;
            }
        }

        etCustomCommand.setText(agentManager.getCustomCommand());

        LinearLayout anthropicContainer = (LinearLayout) holder.findViewById(R.id.anthropic_settings_container);
        EditText etAnthropicToken = (EditText) holder.findViewById(R.id.et_anthropic_auth_token);
        EditText etAnthropicBaseUrl = (EditText) holder.findViewById(R.id.et_anthropic_base_url);
        EditText etAnthropicModel = (EditText) holder.findViewById(R.id.et_anthropic_model);

        etAnthropicToken.setText(agentManager.getAnthropicAuthToken());
        etAnthropicBaseUrl.setText(agentManager.getAnthropicBaseUrl());
        etAnthropicModel.setText(agentManager.getAnthropicModel());

        spinnerProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedAgentId = providerIds[position];
                agentManager.setSelectedAgent(selectedAgentId);
                
                customContainer.setVisibility(selectedAgentId.equals("custom") ? View.VISIBLE : View.GONE);
                anthropicContainer.setVisibility(selectedAgentId.equals("claude_code") ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // Initialize visibility on load
        customContainer.setVisibility(selectedId.equals("custom") ? View.VISIBLE : View.GONE);
        anthropicContainer.setVisibility(selectedId.equals("claude_code") ? View.VISIBLE : View.GONE);

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
