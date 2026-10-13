package com.termux.app.setup;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.app.terminal.CLIAgentManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Coding-agent picker for the setup wizard: every available agent with a checkbox,
 * its source (bundled-offline vs download) and live state (installed ✓).
 * Toggling syncs straight into {@link CLIAgentManager} enabled flags so the
 * dashboard and chat stay consistent with the wizard choice.
 */
public class SetupAgentAdapter extends RecyclerView.Adapter<SetupAgentAdapter.Holder> {

    public interface Listener {
        void onToggled(CLIAgentManager.CLIAgent agent, boolean checked);
    }

    public static class Row {
        public final CLIAgentManager.CLIAgent agent;
        public boolean checked;
        public final boolean bundled;
        public boolean installed;

        Row(CLIAgentManager.CLIAgent agent, boolean checked, boolean bundled) {
            this.agent = agent;
            this.checked = checked;
            this.bundled = bundled;
        }
    }

    private final List<Row> items = new ArrayList<>();
    private final Listener listener;
    private final Set<String> installedIds = new HashSet<>();

    public SetupAgentAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<Row> rows) {
        items.clear();
        if (rows != null) items.addAll(rows);
        notifyDataSetChanged();
    }

    public List<Row> getItems() {
        return items;
    }

    /** Mark agents freshly installed so rows show ✓ without rebuilding. */
    public void setInstalled(String agentId) {
        installedIds.add(agentId);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).agent.id.equals(agentId)) {
                items.get(i).installed = true;
                notifyItemChanged(i);
                break;
            }
        }
    }

    public void refreshStates(Set<String> nowInstalled) {
        installedIds.clear();
        if (nowInstalled != null) installedIds.addAll(nowInstalled);
        for (Row row : items) {
            row.installed = installedIds.contains(row.agent.id);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_setup_agent, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Row row = items.get(position);
        holder.icon.setText(row.agent.iconEmoji);
        holder.name.setText(row.agent.name);
        holder.source.setText(row.bundled ? "in-app bundle (offline)" : "downloads on setup");

        holder.checkbox.setOnCheckedChangeListener(null);
        holder.checkbox.setChecked(row.checked);
        holder.checkbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            row.checked = isChecked;
            if (listener != null) listener.onToggled(row.agent, isChecked);
        });
        holder.itemView.setOnClickListener(v -> holder.checkbox.toggle());
        holder.itemView.setAlpha(row.checked ? 1f : 0.55f);

        if (row.installed) {
            holder.state.setText("✓ READY");
            holder.state.setTextColor(0xFF00E676);
        } else if (row.bundled) {
            holder.state.setText("BUNDLED");
            holder.state.setTextColor(0xFFFFC107);
        } else {
            holder.state.setText("ONLINE");
            holder.state.setTextColor(0xFF888888);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final CheckBox checkbox;
        final TextView icon;
        final TextView name;
        final TextView source;
        final TextView state;

        Holder(View itemView) {
            super(itemView);
            checkbox = itemView.findViewById(R.id.agent_checkbox);
            icon = itemView.findViewById(R.id.agent_icon);
            name = itemView.findViewById(R.id.agent_name);
            source = itemView.findViewById(R.id.agent_source);
            state = itemView.findViewById(R.id.agent_state);
        }
    }
}
