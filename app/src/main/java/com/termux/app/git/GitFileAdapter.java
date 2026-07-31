package com.termux.app.git;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;

public class GitFileAdapter extends RecyclerView.Adapter<GitFileAdapter.ViewHolder> {

    private List<GitFile> files = new ArrayList<>();

    public void setFiles(List<GitFile> files) {
        this.files = files;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_git_file, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        GitFile file = files.get(position);
        holder.tvFileName.setText(file.getFilename());
        holder.tvStatus.setText(file.getStatus());
        
        // Simple color coding based on status
        if (file.getStatus().contains("M")) {
            holder.tvStatus.setTextColor(0xFFFFB000); // Orange for modified
        } else if (file.getStatus().contains("A") || file.getStatus().contains("?")) {
            holder.tvStatus.setTextColor(0xFF4CAF50); // Green for added/untracked
        } else if (file.getStatus().contains("D")) {
            holder.tvStatus.setTextColor(0xFFF44336); // Red for deleted
        } else {
            holder.tvStatus.setTextColor(0xFF9E9E9E); // Grey for others
        }
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvFileName;
        TextView tvStatus;

        ViewHolder(View itemView) {
            super(itemView);
            tvFileName = itemView.findViewById(R.id.tv_git_file_name);
            tvStatus = itemView.findViewById(R.id.tv_git_file_status);
        }
    }
}
