package com.termux.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.termux.R;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class FileExplorerAdapter extends RecyclerView.Adapter<FileExplorerAdapter.FileViewHolder> {

    private List<File> files = new ArrayList<>();
    private File currentDir;
    private final OnFileClickListener listener;

    public interface OnFileClickListener {
        void onFileClick(File file);
    }

    public FileExplorerAdapter(File initialDir, OnFileClickListener listener) {
        this.listener = listener;
        loadDirectory(initialDir);
    }

    public void loadDirectory(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        this.currentDir = dir;
        this.files.clear();
        
        File[] listedFiles = dir.listFiles();
        if (listedFiles != null) {
            List<File> fileList = new ArrayList<>(Arrays.asList(listedFiles));
            Collections.sort(fileList, (f1, f2) -> {
                if (f1.isDirectory() && !f2.isDirectory()) return -1;
                if (!f1.isDirectory() && f2.isDirectory()) return 1;
                return f1.getName().compareToIgnoreCase(f2.getName());
            });
            
            // Add '..' to go up if not at root
            if (!dir.getAbsolutePath().equals("/data/data/com.termux/files/home")) {
                this.files.add(new File(dir, ".."));
            }
            
            this.files.addAll(fileList);
        }
        notifyDataSetChanged();
    }

    public File getCurrentDir() {
        return currentDir;
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file_explorer, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        File file = files.get(position);
        holder.tvFileName.setText(file.getName());
        
        if (file.isDirectory()) {
            holder.ivIcon.setImageResource(android.R.drawable.ic_menu_sort_by_size); // Folder icon substitute
            holder.ivIcon.setColorFilter(0xFFFACC15); // Yellow tint
        } else {
            String fileName = file.getName().toLowerCase();
            if (fileName.endsWith(".py")) {
                holder.ivIcon.setImageResource(R.drawable.ic_python);
                holder.ivIcon.setColorFilter(null);
            } else if (fileName.endsWith(".js")) {
                holder.ivIcon.setImageResource(R.drawable.ic_javascript);
                holder.ivIcon.setColorFilter(null);
            } else if (fileName.endsWith(".ts")) {
                holder.ivIcon.setImageResource(R.drawable.ic_typescript);
                holder.ivIcon.setColorFilter(null);
            } else if (fileName.endsWith(".java")) {
                holder.ivIcon.setImageResource(R.drawable.ic_java);
                holder.ivIcon.setColorFilter(null);
            } else if (fileName.endsWith(".cpp") || fileName.endsWith(".c") || fileName.endsWith(".h") || fileName.endsWith(".hpp")) {
                holder.ivIcon.setImageResource(R.drawable.ic_cpp);
                holder.ivIcon.setColorFilter(null);
            } else if (fileName.endsWith(".html") || fileName.endsWith(".htm")) {
                holder.ivIcon.setImageResource(R.drawable.ic_html);
                holder.ivIcon.setColorFilter(null);
            } else if (fileName.endsWith(".css")) {
                holder.ivIcon.setImageResource(R.drawable.ic_css);
                holder.ivIcon.setColorFilter(null);
            } else if (fileName.endsWith(".kt") || fileName.endsWith(".kts")) {
                holder.ivIcon.setImageResource(R.drawable.ic_kotlin);
                holder.ivIcon.setColorFilter(null);
            } else if (fileName.endsWith(".rs")) {
                holder.ivIcon.setImageResource(R.drawable.ic_rust);
                holder.ivIcon.setColorFilter(null);
            } else {
                holder.ivIcon.setImageResource(android.R.drawable.ic_menu_help); // File icon substitute
                holder.ivIcon.setColorFilter(0xFF9CA3AF); // Gray tint
            }
        }

        holder.itemView.setOnClickListener(v -> listener.onFileClick(file));
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    static class FileViewHolder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvFileName;

        FileViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.iv_file_icon);
            tvFileName = itemView.findViewById(R.id.tv_file_name);
        }
    }
}
