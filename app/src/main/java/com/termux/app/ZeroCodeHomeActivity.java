package com.termux.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.FileObserver;
import android.view.View;
import android.widget.Toast;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.app.AlertDialog;
import android.widget.PopupMenu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.git.GitFile;
import com.termux.app.git.GitFileAdapter;
import com.termux.app.git.GitManager;
import com.termux.shared.activity.ActivityUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ZeroCodeHomeActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;
    private File workspaceDir;
    private File currentWorkspaceDir;
    private RecyclerView recyclerWorkspace;
    private FileAdapter fileAdapter;
    private View emptyStateContainer;
    private View editorContainer;
    private TextView tvAppTitle;
    private LineNumberEditText etHomeCode;
    private File currentEditingFile;
    private FileObserver workspaceObserver;

    private void startWorkspaceObserver() {
        if (workspaceObserver != null) {
            workspaceObserver.stopWatching();
        }
        if (currentWorkspaceDir != null && currentWorkspaceDir.exists()) {
            workspaceObserver = new FileObserver(currentWorkspaceDir.getAbsolutePath(), FileObserver.CREATE | FileObserver.DELETE | FileObserver.MOVED_TO | FileObserver.MOVED_FROM) {
                @Override
                public void onEvent(int event, String path) {
                    if (path != null) {
                        runOnUiThread(() -> loadFiles());
                    }
                }
            };
            workspaceObserver.startWatching();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (workspaceObserver != null) {
            workspaceObserver.startWatching();
        }
        loadFiles();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (workspaceObserver != null) {
            workspaceObserver.stopWatching();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        requestManageExternalStorageIfNeeded();

        android.content.SharedPreferences prefs = getSharedPreferences("ZeroCodePrefs", MODE_PRIVATE);
        String savedWorkspace = prefs.getString("workspace_path", null);
        
        if (savedWorkspace != null) {
            workspaceDir = new File(savedWorkspace);
            if (!workspaceDir.exists()) {
                workspaceDir.mkdirs();
            }
        } else {
            workspaceDir = null;
        }

        drawerLayout = findViewById(R.id.drawer_layout);
        emptyStateContainer = findViewById(R.id.empty_state_container);
        editorContainer = findViewById(R.id.editor_container);
        tvAppTitle = findViewById(R.id.tv_app_title);
        etHomeCode = findViewById(R.id.et_home_code);
        tabContainer = findViewById(R.id.tab_container);

        btnHomeEditorRun = findViewById(R.id.btn_home_editor_run);
        if (btnHomeEditorRun != null) btnHomeEditorRun.setOnClickListener(v -> runCurrentCode());

        btnHomeEditorSave = findViewById(R.id.btn_home_editor_save);
        if (btnHomeEditorSave != null) btnHomeEditorSave.setOnClickListener(v -> saveCurrentFile());

        btnHomeEditorClose = findViewById(R.id.btn_home_editor_close);
        if (btnHomeEditorClose != null) btnHomeEditorClose.setOnClickListener(v -> closeTab(currentEditingFile));

        btnSettings = findViewById(R.id.btn_settings);
        
        setupActions();
        setupWorkspaceDrawer();
        
        if (savedWorkspace != null && new File(savedWorkspace).exists()) {
            View btnMain = findViewById(R.id.btn_open_folder_main);
            if (btnMain != null) btnMain.setVisibility(View.GONE);
            
            TextView tvEmptyText = findViewById(R.id.tv_empty_state_text);
            if (tvEmptyText != null) {
                tvEmptyText.setText("Workspace: " + workspaceDir.getName());
            }
        }
    }

    private void requestManageExternalStorageIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.addCategory("android.intent.category.DEFAULT");
                    intent.setData(android.net.Uri.parse(String.format("package:%s", getApplicationContext().getPackageName())));
                    startActivity(intent);
                    Toast.makeText(this, "Please grant All Files Access to use Workspaces", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Intent intent = new Intent();
                    intent.setAction(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            }
        }
    }

    private static final int REQUEST_CODE_OPEN_DIRECTORY = 1001;

    private void setupActions() {
        View btnMenu = findViewById(R.id.btn_menu);
        if (btnMenu != null) {
            btnMenu.setOnClickListener(v -> openDrawer());
        }

        View btnSettings = findViewById(R.id.btn_settings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class)));
        }

        View btnOpenFolderMain = findViewById(R.id.btn_open_folder_main);
        if (btnOpenFolderMain != null) {
            btnOpenFolderMain.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                startActivityForResult(intent, REQUEST_CODE_OPEN_DIRECTORY);
            });
        }

        View btnOpenFolderDrawer = findViewById(R.id.btn_open_folder);
        if (btnOpenFolderDrawer != null) {
            btnOpenFolderDrawer.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                startActivityForResult(intent, REQUEST_CODE_OPEN_DIRECTORY);
            });
        }

        View btnTerminalFab = findViewById(R.id.btn_terminal_fab);
        if (btnTerminalFab != null) {
            btnTerminalFab.setOnClickListener(v -> launchTerminal());
        }


    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_OPEN_DIRECTORY && resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                String path = UriToPathUtils.getPathFromTreeUri(data.getData());
                if (path != null) {
                    workspaceDir = new File(path);
                    currentWorkspaceDir = workspaceDir;
                    
                    getSharedPreferences("ZeroCodePrefs", MODE_PRIVATE)
                            .edit()
                            .putString("workspace_path", workspaceDir.getAbsolutePath())
                            .apply();
                            
                    loadFiles();
                    openDrawer();
                    Toast.makeText(this, "Workspace Opened: " + path, Toast.LENGTH_SHORT).show();
                    

                    
                    // Update Empty State UI to show Workspace is opened
                    View btnMain = findViewById(R.id.btn_open_folder_main);
                    if (btnMain != null) btnMain.setVisibility(View.GONE);
                    
                    TextView tvEmptyText = findViewById(R.id.tv_empty_state_text);
                    if (tvEmptyText != null) {
                        tvEmptyText.setText("Workspace: " + workspaceDir.getName());
                    }
                    
                    TextView tvEmptySubtext = findViewById(R.id.tv_empty_state_subtext);
                    if (tvEmptySubtext != null) {
                        tvEmptySubtext.setText("Workspace loaded successfully.\nSwipe right or click top-left menu to browse files.");
                    }
                } else {
                    Toast.makeText(this, "Could not resolve folder path. Using default Workspace.", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void openDrawer() {
        if (drawerLayout != null) {
            drawerLayout.openDrawer(GravityCompat.START);
        }
    }

    private void closeDrawer() {
        if (drawerLayout != null) {
            drawerLayout.closeDrawer(GravityCompat.START);
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else if (editorContainer != null && editorContainer.getVisibility() == View.VISIBLE) {
            if (currentEditingFile != null) {
                closeTab(currentEditingFile);
            } else {
                closeCodeEditor();
            }
        } else {
            super.onBackPressed();
        }
    }

    private void launchTerminal() {
        Intent intent = new Intent(this, TermuxActivity.class);
        if (workspaceDir != null && workspaceDir.exists()) {
            intent.putExtra("com.termux.execute.cwd", workspaceDir.getAbsolutePath());
        }
        startActivity(intent);
        overridePendingTransition(R.anim.slide_up, R.anim.stay);
    }

    private View btnHomeEditorSave;
    private View btnHomeEditorClose;
    private View btnHomeEditorRun;
    private View btnSettings;
    
    private LinearLayout tabContainer;
    private java.util.LinkedHashMap<File, String> openFiles = new java.util.LinkedHashMap<>();

    private void launchCodeEditor(File fileToOpen) {
        if (fileToOpen == null || !fileToOpen.exists()) return;

        if (openFiles.containsKey(fileToOpen)) {
            switchTab(fileToOpen);
            closeDrawer();
            return;
        }

        StringBuilder text = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(fileToOpen))) {
            String line;
            while ((line = br.readLine()) != null) {
                text.append(line).append('\n');
            }
            openFiles.put(fileToOpen, text.toString());
        } catch (Exception e) {
            Toast.makeText(this, "Error opening file: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        switchTab(fileToOpen);
        closeDrawer();
    }

    private void switchTab(File fileToOpen) {
        if (currentEditingFile != null && openFiles.containsKey(currentEditingFile)) {
            openFiles.put(currentEditingFile, etHomeCode.getText().toString());
        }

        currentEditingFile = fileToOpen;
        if (tvAppTitle != null) {
            tvAppTitle.setText(fileToOpen.getName());
        }
        
        etHomeCode.setText(openFiles.get(fileToOpen));

        emptyStateContainer.setVisibility(View.GONE);
        editorContainer.setVisibility(View.VISIBLE);
        
        if (btnHomeEditorSave != null) btnHomeEditorSave.setVisibility(View.VISIBLE);
        if (btnHomeEditorClose != null) btnHomeEditorClose.setVisibility(View.VISIBLE);
        if (btnHomeEditorRun != null) btnHomeEditorRun.setVisibility(View.VISIBLE);
        if (btnSettings != null) btnSettings.setVisibility(View.GONE);

        renderTabs();
    }

    private void renderTabs() {
        if (tabContainer == null) return;
        tabContainer.removeAllViews();

        for (File file : openFiles.keySet()) {
            View tabView = getLayoutInflater().inflate(R.layout.item_editor_tab, tabContainer, false);
            TextView tvTabName = tabView.findViewById(R.id.tv_tab_filename);
            View btnTabClose = tabView.findViewById(R.id.btn_tab_close);

            tvTabName.setText(file.getName());

            if (file.equals(currentEditingFile)) {
                tabView.setBackgroundColor(getResources().getColor(R.color.zc_bg_card));
                tvTabName.setTextColor(getResources().getColor(R.color.zc_text_primary));
            } else {
                tabView.setBackgroundColor(getResources().getColor(R.color.zc_bg_dark));
                tvTabName.setTextColor(getResources().getColor(R.color.zc_text_secondary));
            }

            tabView.setOnClickListener(v -> switchTab(file));
            btnTabClose.setOnClickListener(v -> closeTab(file));

            tabContainer.addView(tabView);
        }
    }

    private void closeTab(File file) {
        openFiles.remove(file);
        
        if (openFiles.isEmpty()) {
            closeCodeEditor();
        } else {
            if (file.equals(currentEditingFile)) {
                File nextFile = null;
                for (File f : openFiles.keySet()) {
                    nextFile = f;
                }
                switchTab(nextFile);
            } else {
                renderTabs();
            }
        }
    }

    private void closeCodeEditor() {
        emptyStateContainer.setVisibility(View.VISIBLE);
        editorContainer.setVisibility(View.GONE);
        currentEditingFile = null;
        if (tvAppTitle != null) {
            tvAppTitle.setText("Novus IDE");
        }
        
        if (btnHomeEditorSave != null) btnHomeEditorSave.setVisibility(View.GONE);
        if (btnHomeEditorClose != null) btnHomeEditorClose.setVisibility(View.GONE);
        if (btnHomeEditorRun != null) btnHomeEditorRun.setVisibility(View.GONE);
        if (btnSettings != null) btnSettings.setVisibility(View.VISIBLE);
    }

    private void saveCurrentFile() {
        if (currentEditingFile != null) {
            try (java.io.FileWriter fw = new java.io.FileWriter(currentEditingFile)) {
                fw.write(etHomeCode.getText().toString());
                openFiles.put(currentEditingFile, etHomeCode.getText().toString());
                Toast.makeText(this, "File saved successfully", Toast.LENGTH_SHORT).show();
                

            } catch (Exception e) {
                Toast.makeText(this, "Failed to save file", Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    private void runCurrentCode() {
        if (currentEditingFile == null) return;
        saveCurrentFile();
        
        Intent intent = new Intent(this, TermuxActivity.class);
        intent.putExtra("com.termux.execute.cwd", currentEditingFile.getParent());
        intent.putExtra("com.termux.execute.file_to_run", currentEditingFile.getAbsolutePath());
        startActivity(intent);
        overridePendingTransition(R.anim.slide_up, R.anim.stay);
    }

    // -----------------------------------------------------
    // Workspace Drawer Logic
    // -----------------------------------------------------

    private View layoutGitWorkspace;
    private RecyclerView recyclerGitFiles;
    private GitFileAdapter gitFileAdapter;
    private EditText etGitCommitMessage;
    private View btnGitCommit;
    private View btnGitRefresh;
    private TextView tvTabFiles;
    private TextView tvTabGit;

    private void setupWorkspaceDrawer() {
        recyclerWorkspace = findViewById(R.id.recycler_workspace);
        if (recyclerWorkspace != null) {
            recyclerWorkspace.setLayoutManager(new LinearLayoutManager(this));
            fileAdapter = new FileAdapter();
            recyclerWorkspace.setAdapter(fileAdapter);
        }

        // Git Setup
        layoutGitWorkspace = findViewById(R.id.layout_git_workspace);
        recyclerGitFiles = findViewById(R.id.recycler_git_files);
        if (recyclerGitFiles != null) {
            recyclerGitFiles.setLayoutManager(new LinearLayoutManager(this));
            gitFileAdapter = new GitFileAdapter();
            recyclerGitFiles.setAdapter(gitFileAdapter);
        }

        tvTabFiles = findViewById(R.id.tab_files);
        tvTabGit = findViewById(R.id.tab_git);
        if (tvTabFiles != null && tvTabGit != null) {
            tvTabFiles.setOnClickListener(v -> showWorkspaceTab(true));
            tvTabGit.setOnClickListener(v -> showWorkspaceTab(false));
        }

        etGitCommitMessage = findViewById(R.id.et_git_commit_message);
        btnGitCommit = findViewById(R.id.btn_git_commit);
        if (btnGitCommit != null) {
            btnGitCommit.setOnClickListener(v -> {
                String message = etGitCommitMessage.getText().toString().trim();
                if (message.isEmpty()) {
                    Toast.makeText(this, "Please enter a commit message", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (workspaceDir == null) return;
                Toast.makeText(this, "Committing and pushing...", Toast.LENGTH_SHORT).show();
                GitManager.commitAndPush(workspaceDir.getAbsolutePath(), message, new GitManager.GitCommandCallback() {
                    @Override
                    public void onSuccess(String output) {
                        Toast.makeText(ZeroCodeHomeActivity.this, "Successfully Pushed!", Toast.LENGTH_LONG).show();
                        etGitCommitMessage.setText("");
                        refreshGitStatus();
                    }

                    @Override
                    public void onError(String error) {
                        Toast.makeText(ZeroCodeHomeActivity.this, "Git Error: " + error, Toast.LENGTH_LONG).show();
                    }
                });
            });
        }

        btnGitRefresh = findViewById(R.id.btn_git_refresh);
        if (btnGitRefresh != null) {
            btnGitRefresh.setOnClickListener(v -> refreshGitStatus());
        }

        View btnExit = findViewById(R.id.btn_workspace_exit);
        if (btnExit != null) {
            btnExit.setOnClickListener(v -> {
                workspaceDir = null;
                currentWorkspaceDir = null;
                getSharedPreferences("ZeroCodePrefs", MODE_PRIVATE).edit().remove("workspace_path").apply();
                
                View btnMain = findViewById(R.id.btn_open_folder_main);
                if (btnMain != null) btnMain.setVisibility(View.VISIBLE);
                
                TextView tvEmptyText = findViewById(R.id.tv_empty_state_text);
                if (tvEmptyText != null) tvEmptyText.setText("Novus IDE");
                
                TextView tvEmptySubtext = findViewById(R.id.tv_empty_state_subtext);
                if (tvEmptySubtext != null) tvEmptySubtext.setText("Swipe right or tap the top-left menu to browse your workspace.");
                
                if (fileAdapter != null) fileAdapter.setFiles(new java.util.ArrayList<>());
                closeDrawer();
                Toast.makeText(this, "Workspace Closed", Toast.LENGTH_SHORT).show();
            });
        }

        View btnNewFolder = findViewById(R.id.btn_workspace_new_folder);
        if (btnNewFolder != null) {
            btnNewFolder.setOnClickListener(v -> showCreateDialog(true));
        }

        View btnNewFile = findViewById(R.id.btn_workspace_new_file);
        if (btnNewFile != null) {
            btnNewFile.setOnClickListener(v -> showCreateDialog(false));
        }

        View btnProjectMap = findViewById(R.id.btn_workspace_project_map);
        if (btnProjectMap != null) {
            btnProjectMap.setOnClickListener(v -> {
                if (workspaceDir != null && workspaceDir.exists()) {
                    android.content.Intent intent = new android.content.Intent(this, ProjectMapActivity.class);
                    intent.putExtra("workspace_path", workspaceDir.getAbsolutePath());
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "No workspace opened", Toast.LENGTH_SHORT).show();
                }
            });
        }

        currentWorkspaceDir = workspaceDir;
        loadFiles();
        refreshGitStatus();
        startWorkspaceObserver();
    }

    private void showWorkspaceTab(boolean showFiles) {
        if (showFiles) {
            tvTabFiles.setBackgroundColor(0xFF333333); // Highlight
            tvTabFiles.setTextColor(0xFFE0E0E0);
            tvTabGit.setBackgroundColor(0x00000000); // Transparent
            tvTabGit.setTextColor(0xFF808080);
            
            recyclerWorkspace.setVisibility(View.VISIBLE);
            layoutGitWorkspace.setVisibility(View.GONE);
            findViewById(R.id.btn_workspace_new_folder).setVisibility(View.VISIBLE);
            findViewById(R.id.btn_workspace_new_file).setVisibility(View.VISIBLE);
            findViewById(R.id.btn_workspace_project_map).setVisibility(View.VISIBLE);
        } else {
            tvTabGit.setBackgroundColor(0xFF333333); // Highlight
            tvTabGit.setTextColor(0xFFE0E0E0);
            tvTabFiles.setBackgroundColor(0x00000000); // Transparent
            tvTabFiles.setTextColor(0xFF808080);
            
            recyclerWorkspace.setVisibility(View.GONE);
            layoutGitWorkspace.setVisibility(View.VISIBLE);
            findViewById(R.id.btn_workspace_new_folder).setVisibility(View.GONE);
            findViewById(R.id.btn_workspace_new_file).setVisibility(View.GONE);
            findViewById(R.id.btn_workspace_project_map).setVisibility(View.GONE);
            
            refreshGitStatus();
        }
    }

    private void refreshGitStatus() {
        if (workspaceDir != null && workspaceDir.exists()) {
            GitManager.getStatus(workspaceDir.getAbsolutePath(), new GitManager.GitStatusCallback() {
                @Override
                public void onStatusResult(List<GitFile> files) {
                    if (gitFileAdapter != null) {
                        gitFileAdapter.setFiles(files);
                    }
                    TextView tvCount = findViewById(R.id.tv_git_changes_count);
                    if (tvCount != null) {
                        tvCount.setText("CHANGES (" + files.size() + ")");
                    }
                }

                @Override
                public void onError(String error) {
                    // Ignore or show subtle error if not a git repo
                }
            });
        }
    }

    private void loadFiles() {
        if (fileAdapter != null && currentWorkspaceDir != null && currentWorkspaceDir.exists()) {
            File[] files = currentWorkspaceDir.listFiles();
            List<File> fileList = new ArrayList<>();
            
            if (workspaceDir != null && !currentWorkspaceDir.getAbsolutePath().equals(workspaceDir.getAbsolutePath())) {
                fileList.add(new File(currentWorkspaceDir, ".."));
            }
            
            if (files != null) {
                List<File> actualFiles = new ArrayList<>(Arrays.asList(files));
                actualFiles.sort((f1, f2) -> {
                    if (f1.isDirectory() && !f2.isDirectory()) return -1;
                    if (!f1.isDirectory() && f2.isDirectory()) return 1;
                    return f1.getName().compareToIgnoreCase(f2.getName());
                });
                fileList.addAll(actualFiles);
            }
            fileAdapter.setFiles(fileList);
            
            TextView tvEmptyText = findViewById(R.id.tv_empty_state_text);
            if (tvEmptyText != null) {
                tvEmptyText.setText("Workspace: " + currentWorkspaceDir.getName());
            }
        }
    }

    private void showCreateDialog(boolean isFolder) {
        if (currentWorkspaceDir == null) {
            Toast.makeText(this, "Please open a workspace folder first", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(isFolder ? "New Folder" : "New File");

        final EditText input = new EditText(this);
        input.setHint("Name");
        builder.setView(input);

        builder.setPositiveButton("Create", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()) {
                File newFile = new File(currentWorkspaceDir, name);
                try {
                    boolean success = false;
                    if (isFolder) {
                        success = newFile.mkdir();
                    } else {
                        success = newFile.createNewFile();
                    }
                    if (success) {
                        loadFiles();
                    } else {
                        Toast.makeText(this, "Failed to create", Toast.LENGTH_SHORT).show();
                    }
                } catch (IOException e) {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void showRenameDialog(File file) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename");

        final EditText input = new EditText(this);
        input.setText(file.getName());
        builder.setView(input);

        builder.setPositiveButton("Rename", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty() && !newName.equals(file.getName())) {
                File renamedFile = new File(file.getParentFile(), newName);
                if (file.renameTo(renamedFile)) {
                    loadFiles();
                } else {
                    Toast.makeText(this, "Failed to rename", Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void deleteFileOrFolder(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteFileOrFolder(child);
                }
            }
        }
        file.delete();
    }

    // -----------------------------------------------------
    // RecyclerView Adapter for Files
    // -----------------------------------------------------

    private class FileAdapter extends RecyclerView.Adapter<FileAdapter.FileViewHolder> {

        private List<File> files = new ArrayList<>();

        public void setFiles(List<File> newFiles) {
            this.files = newFiles;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file, parent, false);
            return new FileViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
            File file = files.get(position);
            holder.itemName.setText(file.getName());

            if (file.getName().equals("..") || file.isDirectory()) {
                holder.itemIcon.setImageResource(R.drawable.ic_folder_new);
                holder.itemIcon.setColorFilter(0xFFFACC15); // Yellow tint for folders
            } else {
                String fileName = file.getName().toLowerCase();
                if (fileName.endsWith(".py")) {
                    holder.itemIcon.setImageResource(R.drawable.ic_python);
                    holder.itemIcon.clearColorFilter();
                } else if (fileName.endsWith(".js")) {
                    holder.itemIcon.setImageResource(R.drawable.ic_javascript);
                    holder.itemIcon.clearColorFilter();
                } else if (fileName.endsWith(".ts")) {
                    holder.itemIcon.setImageResource(R.drawable.ic_typescript);
                    holder.itemIcon.clearColorFilter();
                } else if (fileName.endsWith(".java")) {
                    holder.itemIcon.setImageResource(R.drawable.ic_java);
                    holder.itemIcon.clearColorFilter();
                } else if (fileName.endsWith(".cpp") || fileName.endsWith(".c") || fileName.endsWith(".h") || fileName.endsWith(".hpp")) {
                    holder.itemIcon.setImageResource(R.drawable.ic_cpp);
                    holder.itemIcon.clearColorFilter();
                } else if (fileName.endsWith(".html") || fileName.endsWith(".htm")) {
                    holder.itemIcon.setImageResource(R.drawable.ic_html);
                    holder.itemIcon.clearColorFilter();
                } else if (fileName.endsWith(".css")) {
                    holder.itemIcon.setImageResource(R.drawable.ic_css);
                    holder.itemIcon.clearColorFilter();
                } else if (fileName.endsWith(".kt") || fileName.endsWith(".kts")) {
                    holder.itemIcon.setImageResource(R.drawable.ic_kotlin);
                    holder.itemIcon.clearColorFilter();
                } else if (fileName.endsWith(".rs")) {
                    holder.itemIcon.setImageResource(R.drawable.ic_rust);
                    holder.itemIcon.clearColorFilter();
                } else {
                    holder.itemIcon.setImageResource(R.drawable.ic_file);
                    holder.itemIcon.setColorFilter(0xFFA0A0A0); // Grey tint for generic files
                }
            }

            holder.itemView.setOnClickListener(v -> {
                if (file.getName().equals("..")) {
                    currentWorkspaceDir = currentWorkspaceDir.getParentFile();
                    loadFiles();
                } else if (file.isDirectory()) {
                    currentWorkspaceDir = file;
                    loadFiles();
                } else if (file.getName().endsWith(".db") || file.getName().endsWith(".sqlite") || file.getName().endsWith(".db3")) {
                    Toast.makeText(ZeroCodeHomeActivity.this, "Opening Database Viewer...", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(ZeroCodeHomeActivity.this, com.termux.app.database.DatabaseViewerActivity.class);
                    intent.putExtra("db_path", file.getAbsolutePath());
                    startActivity(intent);
                } else {
                    Toast.makeText(ZeroCodeHomeActivity.this, "Opening file in editor...", Toast.LENGTH_SHORT).show();
                    launchCodeEditor(file);
                }
            });

            holder.itemMenu.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(ZeroCodeHomeActivity.this, holder.itemMenu);
                popup.getMenu().add(0, 1, 0, "Rename");
                popup.getMenu().add(0, 2, 1, "Delete");
                popup.setOnMenuItemClickListener(item -> {
                    switch (item.getItemId()) {
                        case 1:
                            showRenameDialog(file);
                            return true;
                        case 2:
                            new AlertDialog.Builder(ZeroCodeHomeActivity.this)
                                    .setTitle("Delete " + file.getName() + "?")
                                    .setPositiveButton("Delete", (d, w) -> {
                                        deleteFileOrFolder(file);
                                        loadFiles();
                                        
                                    })
                                    .setNegativeButton("Cancel", null)
                                    .show();
                            return true;
                        default:
                            return false;
                    }
                });
                popup.show();
            });
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        class FileViewHolder extends RecyclerView.ViewHolder {
            ImageView itemIcon;
            TextView itemName;
            View itemMenu;

            public FileViewHolder(@NonNull View itemView) {
                super(itemView);
                itemIcon = itemView.findViewById(R.id.item_icon);
                itemName = itemView.findViewById(R.id.item_name);
                itemMenu = itemView.findViewById(R.id.item_menu);
            }
        }
    }
}
