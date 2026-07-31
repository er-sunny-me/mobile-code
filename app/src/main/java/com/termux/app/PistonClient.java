package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class PistonClient {

    private static final String API_URL = "https://emkc.org/api/v2/piston/execute";

    public interface PistonCallback {
        void onResult(boolean isError, String output);
    }

    public static void executeCode(String filename, String code, PistonCallback callback) {
        new Thread(() -> {
            try {
                String language = getLanguageFromFilename(filename);
                String version = getVersionForLanguage(language);

                if (language.equals("unknown")) {
                    callback.onResult(true, "Unsupported file type for Piston execution.");
                    return;
                }

                JSONObject payload = new JSONObject();
                payload.put("language", language);
                payload.put("version", version);

                JSONArray files = new JSONArray();
                JSONObject fileObj = new JSONObject();
                fileObj.put("name", filename);
                fileObj.put("content", code);
                files.put(fileObj);
                payload.put("files", files);

                URL url = new URL(API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = payload.toString().getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                int codeResponse = conn.getResponseCode();
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        (codeResponse >= 200 && codeResponse < 300) ? conn.getInputStream() : conn.getErrorStream(), "utf-8"));

                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }

                if (codeResponse >= 200 && codeResponse < 300) {
                    JSONObject jsonResponse = new JSONObject(response.toString());
                    JSONObject run = jsonResponse.getJSONObject("run");
                    int exitCode = run.optInt("code", 0);
                    String stdout = run.optString("stdout", "");
                    String stderr = run.optString("stderr", "");

                    if (exitCode != 0 || !stderr.isEmpty()) {
                        callback.onResult(true, stderr.isEmpty() ? stdout : stderr);
                    } else {
                        callback.onResult(false, stdout);
                    }
                } else {
                    callback.onResult(true, "API Error: " + response.toString());
                }

            } catch (Exception e) {
                callback.onResult(true, "Execution Failed: " + e.getMessage());
            }
        }).start();
    }

    private static String getLanguageFromFilename(String filename) {
        if (filename.endsWith(".py")) return "python";
        if (filename.endsWith(".js")) return "javascript";
        if (filename.endsWith(".cpp")) return "cpp";
        if (filename.endsWith(".c")) return "c";
        if (filename.endsWith(".java")) return "java";
        if (filename.endsWith(".rb")) return "ruby";
        if (filename.endsWith(".rs")) return "rust";
        if (filename.endsWith(".go")) return "go";
        if (filename.endsWith(".php")) return "php";
        return "unknown";
    }

    private static String getVersionForLanguage(String language) {
        switch (language) {
            case "python": return "3.10.0";
            case "javascript": return "18.15.0";
            case "cpp": return "10.2.0";
            case "c": return "10.2.0";
            case "java": return "15.0.2";
            case "ruby": return "3.0.1";
            case "rust": return "1.68.2";
            case "go": return "1.16.2";
            case "php": return "8.2.3";
            default: return "*";
        }
    }
}
