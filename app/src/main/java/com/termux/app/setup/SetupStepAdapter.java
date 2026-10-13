package com.termux.app.setup;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;

/** Renders the setup wizard checklist. */
public class SetupStepAdapter extends RecyclerView.Adapter<SetupStepAdapter.Holder> {

    private final List<SetupStep> items = new ArrayList<>();

    public void setItems(List<SetupStep> steps) {
        items.clear();
        if (steps != null) items.addAll(steps);
        notifyDataSetChanged();
    }

    public void refresh() {
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_setup_step, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        SetupStep step = items.get(position);
        holder.title.setText(step.title);
        holder.detail.setText(step.detail == null ? "" : step.detail);

        int color;
        String glyph;
        String label;
        switch (step.status) {
            case RUNNING:
                glyph = "◌";
                label = "WORKING";
                color = 0xFFFFC107;
                break;
            case DONE:
                glyph = "✓";
                label = "DONE";
                color = 0xFF00E676;
                break;
            case FAILED:
                glyph = "✕";
                label = "FAILED";
                color = 0xFFFF3B30;
                break;
            case SKIPPED:
                glyph = "–";
                label = "SKIPPED";
                color = 0xFF888888;
                break;
            case PENDING:
            default:
                glyph = "○";
                label = "QUEUED";
                color = 0xFF555555;
                break;
        }
        holder.icon.setText(glyph);
        holder.icon.setTextColor(color);
        holder.status.setText(label);
        holder.status.setTextColor(color);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView icon;
        final TextView title;
        final TextView detail;
        final TextView status;

        Holder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.setup_step_icon);
            title = itemView.findViewById(R.id.setup_step_title);
            detail = itemView.findViewById(R.id.setup_step_detail);
            status = itemView.findViewById(R.id.setup_step_status);
        }
    }
}
