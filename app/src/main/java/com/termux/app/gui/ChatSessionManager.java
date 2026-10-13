package com.termux.app.gui;

import android.content.Context;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages persisting and loading chat sessions for CLI coding agents.
 */
public class ChatSessionManager {

    private final File sessionsDir;

    public ChatSessionManager(Context context) {
        sessionsDir = new File(context.getFilesDir(), "agent_chat_sessions");
        if (!sessionsDir.exists()) {
            sessionsDir.mkdirs();
        }
    }

    public synchronized List<ChatSession> getAllSessions() {
        List<ChatSession> list = new ArrayList<>();
        File[] files = sessionsDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return list;

        for (File file : files) {
            try {
                String content = readFile(file);
                if (content.isEmpty()) continue;
                JSONObject json = new JSONObject(content);
                ChatSession session = ChatSession.fromJson(json);
                if (session != null) {
                    list.add(session);
                }
            } catch (Exception ignored) {
            }
        }

        // Sort descending by last updated
        Collections.sort(list, (a, b) -> Long.compare(b.lastUpdated, a.lastUpdated));
        return list;
    }

    public synchronized ChatSession getSession(String id) {
        if (id == null || id.isEmpty()) return null;
        File file = new File(sessionsDir, id + ".json");
        if (!file.isFile()) return null;
        try {
            String content = readFile(file);
            JSONObject json = new JSONObject(content);
            return ChatSession.fromJson(json);
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized void saveSession(ChatSession session) {
        if (session == null || session.id == null) return;
        session.lastUpdated = System.currentTimeMillis();
        File file = new File(sessionsDir, session.id + ".json");
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(session.toJson().toString(2));
        } catch (Exception ignored) {
        }
    }

    public synchronized void deleteSession(String id) {
        if (id == null || id.isEmpty()) return;
        File file = new File(sessionsDir, id + ".json");
        if (file.exists()) {
            file.delete();
        }
    }

    private static String readFile(File file) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }
}
