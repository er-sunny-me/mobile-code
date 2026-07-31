package com.termux.app;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ProjectMapActivity extends AppCompatActivity {

    private WebView webView;
    private LinearLayout layoutLoading;
    private TextView tvLoadingStatus;
    private String workspacePath;
    
    private SharedPreferences aiPrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_project_map);

        workspacePath = getIntent().getStringExtra("workspace_path");
        if (workspacePath == null) {
            Toast.makeText(this, "No workspace path provided", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        aiPrefs = getSharedPreferences("ZeroCodeAI", MODE_PRIVATE);

        webView = findViewById(R.id.webview_map);
        layoutLoading = findViewById(R.id.layout_map_loading);
        tvLoadingStatus = findViewById(R.id.tv_loading_status);

        setupWebView();

        findViewById(R.id.btn_map_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_map_refresh).setOnClickListener(v -> generateMap());

        generateMap();
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        webView.setWebChromeClient(new WebChromeClient());
    }

    private void generateMap() {
        webView.setVisibility(View.GONE);
        layoutLoading.setVisibility(View.VISIBLE);
        tvLoadingStatus.setText("Scanning workspace files...");

        new Thread(() -> {
            try {
                String projectSummary = scanWorkspace(new File(workspacePath), 0);
                
                runOnUiThread(() -> tvLoadingStatus.setText("Asking AI to generate map..."));
                
                String mermaidCode = requestMapFromAI(projectSummary);
                
                if (mermaidCode != null && !mermaidCode.isEmpty()) {
                    runOnUiThread(() -> renderMermaid(mermaidCode));
                } else {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Failed to generate map from AI", Toast.LENGTH_LONG).show();
                        layoutLoading.setVisibility(View.GONE);
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    layoutLoading.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    private String scanWorkspace(File dir, int depth) {
        if (depth > 4) return ""; // Limit depth to prevent massive prompts
        if (dir == null || !dir.exists() || !dir.isDirectory()) return "";

        StringBuilder sb = new StringBuilder();
        File[] files = dir.listFiles();
        if (files == null) return "";

        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(".") || name.equals("build") || name.equals("node_modules")) continue;
            
            if (f.isDirectory()) {
                sb.append(getIndent(depth)).append("📁 ").append(name).append("/\n");
                sb.append(scanWorkspace(f, depth + 1));
            } else {
                sb.append(getIndent(depth)).append("📄 ").append(name).append("\n");
                // For code files, try to extract a tiny bit of context
                if (name.endsWith(".java") || name.endsWith(".py") || name.endsWith(".js") || name.endsWith(".ts")) {
                    String snippet = extractSnippet(f);
                    if (!snippet.isEmpty()) {
                        sb.append(getIndent(depth + 1)).append("↳ ").append(snippet).append("\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    private String getIndent(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        return sb.toString();
    }

    private String extractSnippet(File file) {
        try {
            BufferedReader br = new BufferedReader(new java.io.FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            int count = 0;
            while ((line = br.readLine()) != null && count < 20) {
                line = line.trim();
                if (line.startsWith("class") || line.startsWith("function") || line.startsWith("def ")) {
                    sb.append(line).append(" | ");
                }
                count++;
            }
            br.close();
            String res = sb.toString();
            if (res.endsWith(" | ")) res = res.substring(0, res.length() - 3);
            return res;
        } catch (Exception e) {
            return "";
        }
    }

    private String requestMapFromAI(String projectSummary) throws Exception {
        String provider = aiPrefs.getString("selected_provider", "OpenAI API");
        String apiKey = getApiKeyForProvider(provider);
        String model = aiPrefs.getString(provider + "_model", "default");
        
        String urlStr = getBaseUrlForProvider(provider);
        if (urlStr == null) return null;

        JSONObject jsonBody = new JSONObject();
        jsonBody.put("model", model);
        
        JSONArray messages = new JSONArray();
        JSONObject systemMsg = new JSONObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", "You are an architecture assistant. Given the following project file structure and snippets, generate a detailed Mermaid.js flowchart mapping the project architecture, file relationships, and key functions/APIs. Only return the mermaid code block, starting with ```mermaid and ending with ```.");
        messages.put(systemMsg);
        
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", "Project Structure:\n" + projectSummary);
        messages.put(userMsg);
        
        jsonBody.put("messages", messages);
        jsonBody.put("temperature", 0.5);
        
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        if (!apiKey.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        
        // Handle custom headers
        JSONArray customHeaders = getCustomHeadersForProvider(provider);
        if (customHeaders != null) {
            for (int i = 0; i < customHeaders.length(); i++) {
                JSONObject h = customHeaders.optJSONObject(i);
                if (h != null && h.has("key") && h.has("value")) {
                    conn.setRequestProperty(h.optString("key"), h.optString("value"));
                }
            }
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
            
            JSONObject jsonResponse = new JSONObject(response.toString());
            String aiContent = jsonResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
            
            // Extract mermaid block
            int startIndex = aiContent.indexOf("```mermaid");
            if (startIndex != -1) {
                startIndex += 10;
                int endIndex = aiContent.indexOf("```", startIndex);
                if (endIndex != -1) {
                    return aiContent.substring(startIndex, endIndex).trim();
                }
            }
            // fallback, just return everything
            return aiContent.replace("```mermaid", "").replace("```", "").trim();
        }
        return null;
    }

    private void renderMermaid(String mermaidCode) {
        layoutLoading.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        
        String html = "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=5.0, minimum-scale=1.0\">\n" +
                "  <script type=\"module\">\n" +
                "    import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.esm.min.mjs';\n" +
                "    mermaid.initialize({ startOnLoad: true, theme: 'dark' });\n" +
                "  </script>\n" +
                "  <style>\n" +
                "    body { background-color: #121212; color: #ffffff; margin: 0; padding: 16px; display: flex; justify-content: center; }\n" +
                "    .mermaid { width: 100%; max-width: 100%; overflow: auto; }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <div class=\"mermaid\">\n" +
                mermaidCode + "\n" +
                "  </div>\n" +
                "</body>\n" +
                "</html>";
                
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private String getBaseUrlForProvider(String provider) {
        String customStr = aiPrefs.getString("custom_provider_config", null);
        if (customStr != null) {
            try {
                JSONObject obj = new JSONObject(customStr);
                if (provider.equals(obj.optString("name", "Custom Provider"))) {
                    return obj.optString("baseUrl");
                }
            } catch (JSONException e) {}
        }
        
        if ("Custom Provider".equals(provider)) {
            return aiPrefs.getString("Custom Provider_base_url", "https://api.openai.com/v1/chat/completions");
        }
        
        switch (provider) {
            case "Google Gemini API": return "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
            case "OpenAI API": return "https://api.openai.com/v1/chat/completions";
            case "Anthropic Claude API": return "https://api.anthropic.com/v1/messages";
            case "Microsoft Azure AI": return "https://models.inference.ai.azure.com/chat/completions";
            case "NVIDIA NIM": return "https://integrate.api.nvidia.com/v1/chat/completions";
            case "AgentRouter": return "https://agentrouter.org/v1/chat/completions";
            case "GitHub Models": return "https://models.inference.ai.azure.com/chat/completions";
            case "swiftrouter": return "https://api.swiftrouter.com/v1/chat/completions";
            case "Together AI": return "https://api.together.xyz/v1/chat/completions";
            case "groq": return "https://api.groq.com/openai/v1/chat/completions";
            case "Fireworks AI": return "https://api.fireworks.ai/inference/v1/chat/completions";
            case "openrouter": return "https://openrouter.ai/api/v1/chat/completions";
            case "Amazon Bedrock": return "https://bedrock-runtime.us-east-1.amazonaws.com/model/invoke";
            default: return null;
        }
    }
    
    private String getApiKeyForProvider(String provider) {
        String customStr = aiPrefs.getString("custom_provider_config", null);
        if (customStr != null) {
            try {
                JSONObject obj = new JSONObject(customStr);
                if (provider.equals(obj.optString("name", "Custom Provider"))) {
                    return obj.optString("apiKey");
                }
            } catch (JSONException e) {}
        }
        
        if ("Custom Provider".equals(provider)) {
            return aiPrefs.getString("Custom Provider_api_key", "");
        }
        return aiPrefs.getString(provider + "_api_key", "");
    }

    private JSONArray getCustomHeadersForProvider(String provider) {
        String customStr = aiPrefs.getString("custom_provider_config", null);
        if (customStr != null) {
            try {
                JSONObject obj = new JSONObject(customStr);
                if (provider.equals(obj.optString("name", "Custom Provider"))) {
                    return obj.optJSONArray("headers");
                }
            } catch (JSONException e) {}
        }
        return null;
    }
}
