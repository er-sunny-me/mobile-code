package com.termux.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.termux.R;
import com.termux.shared.termux.TermuxConstants;
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
            if (!dir.getAbsolutePath().equals(TermuxConstants.TERMUX_HOME_DIR_PATH)) {
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
        bindFileIcon(holder.ivIcon, file);
        holder.itemView.setOnClickListener(v -> listener.onFileClick(file));
    }

    /**
     * Resolves a VS Code-style file or folder icon and optional tint for an explorer row.
     */
    public static void bindFileIcon(@NonNull ImageView iconView, @NonNull File file) {
        String name = file.getName();
        if ("..".equals(name)) {
            iconView.setImageResource(R.drawable.ic_folder);
            iconView.setColorFilter(0xFF9CA3AF);
            return;
        }
        if (file.isDirectory()) {
            iconView.setImageResource(R.drawable.ic_folder);
            iconView.clearColorFilter();
            return;
        }

        String lower = name.toLowerCase();
        if ("gradlew".equals(lower) || "gradlew.bat".equals(lower)
            || lower.endsWith(".gradle") || lower.endsWith(".gradle.kts")) {
            iconView.setImageResource(R.drawable.ic_gradle);
            iconView.clearColorFilter();
        } else if (lower.endsWith(".md") || lower.endsWith(".mdx") || lower.endsWith(".markdown")) {
            iconView.setImageResource(R.drawable.ic_markdown);
            iconView.clearColorFilter();
        } else if (lower.endsWith(".py")) {
            iconView.setImageResource(R.drawable.ic_python);
            iconView.clearColorFilter();
        } else if (lower.endsWith(".js") || lower.endsWith(".mjs")
            || lower.endsWith(".cjs") || lower.endsWith(".jsx")) {
            iconView.setImageResource(R.drawable.ic_javascript);
            iconView.clearColorFilter();
        } else if (lower.endsWith(".ts") || lower.endsWith(".tsx")) {
            iconView.setImageResource(R.drawable.ic_typescript);
            iconView.clearColorFilter();
        } else if (lower.endsWith(".java")) {
            iconView.setImageResource(R.drawable.ic_java);
            iconView.clearColorFilter();
        } else if (lower.endsWith(".kt") || lower.endsWith(".kts")) {
            iconView.setImageResource(R.drawable.ic_kotlin);
            iconView.clearColorFilter();
        } else if (lower.endsWith(".cpp") || lower.endsWith(".c")
            || lower.endsWith(".h") || lower.endsWith(".hpp")) {
            iconView.setImageResource(R.drawable.ic_cpp);
            iconView.clearColorFilter();
        } else if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            iconView.setImageResource(R.drawable.ic_html);
            iconView.clearColorFilter();
        } else if (lower.endsWith(".css") || lower.endsWith(".scss") || lower.endsWith(".sass")) {
            iconView.setImageResource(R.drawable.ic_css);
            iconView.clearColorFilter();
        } else if (lower.endsWith(".rs")) {
            iconView.setImageResource(R.drawable.ic_rust);
            iconView.clearColorFilter();
        } else if (lower.endsWith(".json") || lower.endsWith(".jsonc")) {
            iconView.setImageResource(R.drawable.ic_json);
            iconView.clearColorFilter();
        } else if (lower.endsWith(".xml")) {
            iconView.setImageResource(R.drawable.ic_xml);
            iconView.clearColorFilter();
        } else if (lower.endsWith(".sh") || lower.endsWith(".bash")
            || lower.endsWith(".zsh") || lower.endsWith(".bat") || lower.endsWith(".cmd")) {
            iconView.setImageResource(R.drawable.ic_shell);
            iconView.clearColorFilter();
        } else if (lower.endsWith(".db") || lower.endsWith(".sqlite")
            || lower.endsWith(".sqlite3") || lower.endsWith(".db3")) {
            iconView.setImageResource(R.drawable.ic_database);
            iconView.clearColorFilter();
        } else if (lower.endsWith(".yml") || lower.endsWith(".yaml")
            || lower.endsWith(".toml") || lower.endsWith(".properties")
            || lower.endsWith(".ini") || lower.endsWith(".cfg")
            || lower.endsWith(".conf") || lower.endsWith(".env")
            || ".gitignore".equals(lower) || ".gitattributes".equals(lower)) {
            iconView.setImageResource(R.drawable.ic_config);
            iconView.clearColorFilter();
        } else if (lower.endsWith(".png") || lower.endsWith(".jpg")
            || lower.endsWith(".jpeg") || lower.endsWith(".webp")
            || lower.endsWith(".svg") || lower.endsWith(".gif")
            || lower.endsWith(".ico")) {
            iconView.setImageResource(R.drawable.ic_file_image);
            iconView.clearColorFilter();
        } else if (lower.endsWith(".apk") || lower.endsWith(".aab") || lower.endsWith(".jar")) {
            iconView.setImageResource(R.drawable.ic_file);
            iconView.setColorFilter(0xFF3DDC84);
        } else if (lower.endsWith(".txt") || lower.endsWith(".log")) {
            iconView.setImageResource(R.drawable.ic_file);
            iconView.setColorFilter(0xFF9CDCFE);
        } else {
            iconView.setImageResource(R.drawable.ic_file);
            iconView.setColorFilter(0xFF9CA3AF);
        }
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
