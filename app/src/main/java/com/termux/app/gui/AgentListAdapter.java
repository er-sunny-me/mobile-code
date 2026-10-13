package com.termux.app.gui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.app.terminal.CLIAgentManager;

import java.util.ArrayList;
import java.util.List;

/** Renders one card per CLI agent in the Agent Dashboard. */
public class AgentListAdapter extends RecyclerView.Adapter<AgentListAdapter.Holder> {

    public interface Listener {
        void onInstall(CLIAgentManager.CLIAgent agent);

        void onLogin(CLIAgentManager.CLIAgent agent);

        void onUseInChat(CLIAgentManager.CLIAgent agent);

        void onToggleEnabled(CLIAgentManager.CLIAgent agent);

        void onEditCommand(CLIAgentManager.CLIAgent agent);
    }

    private final List<CLIAgentManager.AgentStatus> items = new ArrayList<>();
    private final CLIAgentManager manager;
    private final Listener listener;

    /** id of the agent currently being installed, or null. */
    private String installingAgentId;

    public AgentListAdapter(CLIAgentManager manager, Listener listener) {
        this.manager = manager;
        this.listener = listener;
    }

    public void setItems(List<CLIAgentManager.AgentStatus> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    public void setInstalling(String agentId) {
        this.installingAgentId = agentId;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_cli_agent, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        CLIAgentManager.AgentStatus status = items.get(position);
        CLIAgentManager.CLIAgent agent = status.agent;
        boolean enabled = manager.isAgentEnabled(agent.id);
        boolean isDefault = manager.getSelectedAgent().id.equals(agent.id);
        boolean installing = agent.id.equals(installingAgentId);
        boolean hasCommand = agent.id.equals("custom")
                || (manager.getGuiCommand(agent) != null && !manager.getGuiCommand(agent).trim().isEmpty());

        holder.icon.setText(agent.iconEmoji);
        holder.name.setText(agent.name);
        holder.description.setText(agent.description);

        holder.defaultBadge.setVisibility(isDefault ? View.VISIBLE : View.GONE);

        if (installing) {
            holder.status.setText("INSTALLING…");
            holder.status.setBackgroundResource(R.drawable.bg_badge_missing);
            holder.status.setTextColor(holder.status.getResources().getColor(R.color.zc_badge_missing_text));
        } else if (status.installed) {
            holder.status.setText("READY");
            holder.status.setBackgroundResource(R.drawable.bg_badge_installed);
            holder.status.setTextColor(holder.status.getResources().getColor(R.color.zc_badge_installed_text));
        } else if (status.installedElsewhere) {
            holder.status.setText("REINSTALL");
            holder.status.setBackgroundResource(R.drawable.bg_badge_missing);
            holder.status.setTextColor(holder.status.getResources().getColor(R.color.zc_badge_missing_text));
        } else {
            holder.status.setText("NOT INSTALLED");
            holder.status.setBackgroundResource(R.drawable.bg_badge_missing);
            holder.status.setTextColor(holder.status.getResources().getColor(R.color.zc_badge_missing_text));
        }

        // Install button: missing entirely, or present only in the wrong environment
        // (classic "install hai phir bhi install bol raha" — reinstall into the run env).
        // Hidden only when READY; otherwise Install (fresh) or Reinstall (wrong env).
        boolean canInstall = !installing
                && !agent.id.equals("custom")
                && agent.installCommand != null
                && !agent.installCommand.isEmpty()
                && !status.installed;
        holder.installButton.setVisibility(canInstall ? View.VISIBLE : View.GONE);
        holder.installButton.setEnabled(canInstall);
        if (canInstall) {
            holder.installButton.setText(status.installedElsewhere ? "Reinstall" : "Install");
            holder.installButton.setOnClickListener(v -> listener.onInstall(agent));
        } else {
            holder.installButton.setOnClickListener(null);
        }

        // Login: needs the binary present and an interactive terminal for OAuth / API keys.
        boolean canLogin = !installing
                && !agent.id.equals("custom")
                && status.installed
                && CLIAgentManager.needsLogin(agent);
        holder.loginButton.setVisibility(canLogin ? View.VISIBLE : View.GONE);
        if (canLogin) {
            holder.loginButton.setOnClickListener(v -> listener.onLogin(agent));
        } else {
            holder.loginButton.setOnClickListener(null);
        }

        holder.toggleButton.setText(enabled ? "Disable" : "Enable");
        holder.toggleButton.setOnClickListener(v -> listener.onToggleEnabled(agent));

        holder.defaultButton.setText(isDefault ? "In use" : "Use in Chat");
        holder.defaultButton.setEnabled(!isDefault);
        holder.defaultButton.setAlpha(isDefault ? 0.5f : 1f);
        if (isDefault) {
            holder.defaultButton.setOnClickListener(null);
        } else {
            holder.defaultButton.setOnClickListener(v -> listener.onUseInChat(agent));
        }

        holder.commandButton.setOnClickListener(v -> listener.onEditCommand(agent));

        // Show the exact command the chat screen will run, so a wrong flag is visible,
        // plus where the binary was actually found (kills "install loop" confusion).
        String command = agent.id.equals("custom")
                ? manager.getCustomCommand()
                : manager.getGuiCommand(agent);
        String location = status.foundPath;
        if (command == null || command.trim().isEmpty()) {
            holder.commandPreview.setVisibility(View.VISIBLE);
            holder.commandPreview.setText(manager.hasCustomGuiCommand(agent.id)
                    ? "no GUI command set — opens in terminal"
                    : "no GUI command known — opens in terminal");
        } else if (status.installedElsewhere && location != null) {
            holder.commandPreview.setVisibility(View.VISIBLE);
            holder.commandPreview.setText("$ " + command + "\n⚠ found only at " + location
                    + " — tap Reinstall to put it where agents run.");
        } else if (status.installed && location != null) {
            holder.commandPreview.setVisibility(View.VISIBLE);
            holder.commandPreview.setText("$ " + command + "\n✓ " + location);
        } else {
            holder.commandPreview.setVisibility(View.VISIBLE);
            holder.commandPreview.setText("$ " + command);
        }

        holder.itemView.setAlpha(enabled ? 1f : 0.55f);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView icon;
        final TextView name;
        final TextView description;
        final TextView status;
        final TextView defaultBadge;
        final TextView installButton;
        final TextView loginButton;
        final TextView toggleButton;
        final TextView defaultButton;
        final TextView commandButton;
        final TextView commandPreview;

        Holder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.agent_icon);
            name = itemView.findViewById(R.id.agent_name);
            description = itemView.findViewById(R.id.agent_description);
            status = itemView.findViewById(R.id.agent_status);
            defaultBadge = itemView.findViewById(R.id.agent_default_badge);
            installButton = itemView.findViewById(R.id.btn_agent_install);
            loginButton = itemView.findViewById(R.id.btn_agent_login);
            toggleButton = itemView.findViewById(R.id.btn_agent_toggle);
            defaultButton = itemView.findViewById(R.id.btn_agent_default);
            commandButton = itemView.findViewById(R.id.btn_agent_gui_command);
            commandPreview = itemView.findViewById(R.id.agent_command_preview);
        }
    }
}
