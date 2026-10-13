package com.termux.app.terminal;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.termux.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ErrorExplanationSheet extends BottomSheetDialog {

    private Activity activity;
    private ErrorDetector.DetectedError error;
    private String openFileContent;

    public ErrorExplanationSheet(Activity activity, ErrorDetector.DetectedError error, String openFileContent) {
        super(activity);
        this.activity = activity;
        this.error = error;
        this.openFileContent = openFileContent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.sheet_error_explanation);

        TextView tvResponse = findViewById(R.id.tv_ai_response);
        Button btnApplyFix = findViewById(R.id.btn_apply_fix);
        ProgressBar progressBar = findViewById(R.id.progress);

        tvResponse.setText("Analyzing error...");

        new Thread(() -> {
            String prompt = buildErrorPrompt(error, openFileContent);
            String fullResponse = callAI(prompt);

            // The activity may have gone away while the request was in flight.
            if (activity.isFinishing() || activity.isDestroyed()) return;

            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                progressBar.setVisibility(View.GONE);
                tvResponse.setText(fullResponse);
                
                String fix = extractCodeFix(fullResponse);
                if (fix != null && !fix.isEmpty()) {
                    btnApplyFix.setVisibility(View.VISIBLE);
                    btnApplyFix.setOnClickListener(v -> {
                        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("fix", fix);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(activity, "Fix copied to clipboard!", Toast.LENGTH_SHORT).show();
                        dismiss();
                    });
                }
            });
        }).start();
    }

    private String buildErrorPrompt(ErrorDetector.DetectedError error, String fileContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert debugging assistant in Mobile Code (Android terminal IDE).\n\n");
        sb.append("## Error Detected\n");
        sb.append("Type: ").append(error.type).append("\n");
        if (error.command != null) sb.append("Command run: `").append(error.command).append("`\n");
        if (error.file != null) {
            sb.append("Location: ").append(error.file);
            if (error.line != null) sb.append(" line ").append(error.line);
            sb.append("\n");
        }
        sb.append("\n## Terminal Output (error block)\n```\n");
        sb.append(error.fullBlock.length() > 1500 ? error.fullBlock.substring(0, 1500) : error.fullBlock);
        sb.append("\n```\n");

        if (fileContent != null && !fileContent.isEmpty()) {
            sb.append("\n## Currently Open File\n```\n");
            sb.append(fileContent.length() > 2000 ? fileContent.substring(0, 2000) : fileContent);
            sb.append("\n```\n");
        }

        sb.append("\nExplain this error in simple terms (2-3 lines max).\n");
        sb.append("Then provide the exact fix with a code block.\n");
        sb.append("Format your response as:\n");
        sb.append("**What happened:** [brief explanation]\n");
        sb.append("**Fix:**\n```[language]\n[corrected code]\n```\n");
        sb.append("**Why:** [one line reason]\n");
        return sb.toString();
    }

    private String extractCodeFix(String response) {
        if (response == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("```[\\w]*\\n([\\s\\S]+?)```").matcher(response);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private String callAI(String prompt) {
        SharedPreferences prefs = activity.getSharedPreferences("ai_settings", Context.MODE_PRIVATE);
        String provider = prefs.getString("selected_provider", "OpenAI API");
        String apiKey = prefs.getString(provider + "_api_key", "");
        String baseUrl = prefs.getString(provider + "_base_url", "https://api.openai.com/v1/chat/completions");
        String model = prefs.getString(provider + "_model", "gpt-4o-mini");

        if (apiKey.isEmpty()) return "Error: API Key is missing for " + provider + ". Please configure it in AI Settings.";

        try {
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("model", model);
            
            JSONArray messages = new JSONArray();
            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", "You are an expert debugger.");
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);
            
            messages.put(systemMsg);
            messages.put(userMsg);
            
            jsonBody.put("messages", messages);
            
            URL url = new URL(baseUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            if (provider.toLowerCase().contains("anthropic")) {
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
                jsonBody.remove("messages");
                JSONArray claudeMsgs = new JSONArray();
                claudeMsgs.put(userMsg);
                jsonBody.put("messages", claudeMsgs);
                jsonBody.put("system", "You are an expert debugger.");
                jsonBody.put("max_tokens", 1024);
            }
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonBody.toString().getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) response.append(line.trim());
                
                JSONObject res = new JSONObject(response.toString());
                if (provider.toLowerCase().contains("anthropic")) {
                    return res.getJSONArray("content").getJSONObject(0).getString("text");
                } else {
                    return res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                }
            } else {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), "utf-8"));
                StringBuilder err = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) err.append(line.trim());
                return "API Error (" + code + "): " + err.toString();
            }
        } catch (Exception e) {
            return "Error calling AI: " + e.getMessage();
        }
    }
}
