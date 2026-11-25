package com.termux.app.preferences;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
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

        // Setup sleek VS Code-style agent rows
        LinearLayout agentsToggleContainer = (LinearLayout) holder.findViewById(R.id.agents_toggle_container);
        if (agentsToggleContainer != null) {
            agentsToggleContainer.removeAllViews();
            Context ctx = getContext();
            int padH = dp(ctx, 12);
            int padV = dp(ctx, 10);
            int rowGap = dp(ctx, 8);
            int badgePadH = dp(ctx, 10);
            int badgePadV = dp(ctx, 5);
            ColorStateList trackTint = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFF007ACC, 0xFF3A3A44}
            );
            ColorStateList thumbTint = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Color.WHITE, 0xFFB4B8C0}
            );

            for (CLIAgentManager.CLIAgent agent : CLIAgentManager.AGENTS) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackgroundResource(R.drawable.bg_settings_row);
                row.setPadding(padH, padV, padH, padV);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                rowParams.setMargins(0, 0, 0, rowGap);
                row.setLayoutParams(rowParams);

                // Left column: emoji + name and short description
                LinearLayout infoCol = new LinearLayout(ctx);
                infoCol.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f
                );
                infoParams.setMarginEnd(dp(ctx, 8));
                infoCol.setLayoutParams(infoParams);

                TextView tvName = new TextView(ctx);
                String prefix = (agent.iconEmoji != null && !agent.iconEmoji.isEmpty())
                        ? agent.iconEmoji + "  " : "";
                tvName.setText(prefix + agent.name);
                tvName.setTextColor(0xFFF3F4F6);
                tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                tvName.setTypeface(null, Typeface.BOLD);
                tvName.setSingleLine(true);
                tvName.setEllipsize(TextUtils.TruncateAt.END);
                infoCol.addView(tvName);

                if (agent.description != null && !agent.description.isEmpty()) {
                    TextView tvDesc = new TextView(ctx);
                    tvDesc.setText(agent.description);
                    tvDesc.setTextColor(0xFF8E929B);
                    tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                    tvDesc.setSingleLine(true);
                    tvDesc.setEllipsize(TextUtils.TruncateAt.END);
                    infoCol.addView(tvDesc);
                }
                row.addView(infoCol);

                // Middle action pill: Ready badge or Install button
                if (agent.installCommand != null && !agent.installCommand.isEmpty()) {
                    boolean installed = agentManager.isAgentInstalled(agent);
                    TextView pill = new TextView(ctx);
                    pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                    pill.setTypeface(null, Typeface.BOLD);
                    pill.setPadding(badgePadH, badgePadV, badgePadH, badgePadV);
                    LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    pillParams.setMarginEnd(dp(ctx, 10));
                    pill.setLayoutParams(pillParams);

                    if (installed) {
                        pill.setText("✓ Ready");
                        pill.setTextColor(0xFF4ADE80);
                        pill.setBackgroundResource(R.drawable.bg_badge_installed);
                    } else {
                        pill.setText("Install");
                        pill.setTextColor(Color.WHITE);
                        pill.setBackgroundResource(R.drawable.bg_btn_install_pill);
                        pill.setClickable(true);
                        pill.setFocusable(true);
                        pill.setOnClickListener(v -> {
                            android.content.Intent intent = new android.content.Intent(ctx, com.termux.app.TermuxActivity.class);
                            intent.setAction("com.xyzide.app.INSTALL_AGENT");
                            intent.putExtra("agent_id", agent.id);
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            ctx.startActivity(intent);
                        });
                    }
                    row.addView(pill);
                }

                SwitchCompat switchView = new SwitchCompat(ctx);
                switchView.setShowText(false);
                switchView.setTextOn("");
                switchView.setTextOff("");
                switchView.setTrackTintList(trackTint);
                switchView.setThumbTintList(thumbTint);
                switchView.setChecked(agentManager.isAgentEnabled(agent.id));
                switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    agentManager.setAgentEnabled(agent.id, isChecked);
                    updateSpinnerAdapter();
                });
                row.addView(switchView);

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

    private static int dp(Context ctx, int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics()));
    }
}
