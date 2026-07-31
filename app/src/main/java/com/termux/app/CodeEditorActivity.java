package com.termux.app;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class CodeEditorActivity extends AppCompatActivity {

    private File currentFile;
    private EditText etCode;
    private TextView tvFileName;
    private android.widget.LinearLayout panelProblems;
    private TextView tvProblemsOutput;
    private TextView tvProblemsTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_code_editor);

        etCode = findViewById(R.id.et_code);
        tvFileName = findViewById(R.id.tv_editor_filename);
        ImageButton btnClose = findViewById(R.id.btn_close);
        ImageButton btnSave = findViewById(R.id.btn_save);
        ImageButton btnRun = findViewById(R.id.btn_run);
        panelProblems = findViewById(R.id.panel_problems);
        tvProblemsOutput = findViewById(R.id.tv_problems_output);
        tvProblemsTitle = findViewById(R.id.tv_problems_title);

        String filePath = getIntent().getStringExtra("file_path");
        if (filePath != null) {
            currentFile = new File(filePath);
            tvFileName.setText(currentFile.getName());
            loadFile();
        }

        btnClose.setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> saveFile());
        btnRun.setOnClickListener(v -> runCodeWithPiston());
    }

    private void runCodeWithPiston() {
        if (currentFile == null) return;
        saveFile();
        
        panelProblems.setVisibility(android.view.View.VISIBLE);
        tvProblemsTitle.setText("Running...");
        tvProblemsOutput.setTextColor(android.graphics.Color.parseColor("#AAAAAA"));
        tvProblemsOutput.setText("Sending code to Piston Engine...");

        PistonClient.executeCode(currentFile.getName(), etCode.getText().toString(), (isError, output) -> {
            runOnUiThread(() -> {
                tvProblemsTitle.setText(isError ? "Problems Detected (Stderr)" : "Output (Stdout)");
                tvProblemsOutput.setTextColor(android.graphics.Color.parseColor(isError ? "#FF6B6B" : "#4CAF50"));
                tvProblemsOutput.setText(output.isEmpty() ? "<No Output>" : output);
            });
        });
    }

    private void loadFile() {
        if (currentFile != null && currentFile.exists()) {
            StringBuilder text = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(currentFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    text.append(line).append('\n');
                }
                etCode.setText(text.toString());
            } catch (IOException e) {
                Toast.makeText(this, "Failed to load file", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void saveFile() {
        if (currentFile != null) {
            try (FileWriter fw = new FileWriter(currentFile)) {
                fw.write(etCode.getText().toString());
                Toast.makeText(this, "File saved successfully", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Toast.makeText(this, "Failed to save file", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
