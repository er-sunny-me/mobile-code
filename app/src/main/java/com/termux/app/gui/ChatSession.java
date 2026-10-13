package com.termux.app.gui;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Model representing a persistent chat session between the user and a CLI agent.
 */
public class ChatSession {
    public String id;
    public String agentId;
    public String agentName;
    public String icon;
    public String title;
    public long createdAt;
    public long lastUpdated;
    public final List<ChatMessage> messages = new ArrayList<>();

    public ChatSession() {
        this.id = "session_" + System.currentTimeMillis();
        this.createdAt = System.currentTimeMillis();
        this.lastUpdated = this.createdAt;
    }

    public ChatSession(String id, String agentId, String agentName, String icon, String title) {
        this.id = id;
        this.agentId = agentId;
        this.agentName = agentName;
        this.icon = icon;
        this.title = title;
        this.createdAt = System.currentTimeMillis();
        this.lastUpdated = this.createdAt;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("agentId", agentId);
        obj.put("agentName", agentName);
        obj.put("icon", icon);
        obj.put("title", title);
        obj.put("createdAt", createdAt);
        obj.put("lastUpdated", lastUpdated);

        JSONArray msgs = new JSONArray();
        for (ChatMessage msg : messages) {
            JSONObject m = new JSONObject();
            m.put("type", msg.type.name());
            m.put("text", msg.text);
            m.put("agentName", msg.agentName);
            m.put("icon", msg.icon);
            m.put("timestamp", msg.timestamp);

            if (msg.permissionTarget != null) {
                m.put("permissionTarget", msg.permissionTarget);
            }
            if (msg.permissionAction != null) {
                m.put("permissionAction", msg.permissionAction);
            }
            if (msg.diffContent != null) {
                m.put("diffContent", msg.diffContent);
            }
            if (msg.permissionDecision != null) {
                m.put("permissionDecision", msg.permissionDecision.name());
            }

            if (msg.attachedImagePath != null) {
                m.put("attachedImagePath", msg.attachedImagePath);
            }
            if (!msg.attachedFiles.isEmpty()) {
                JSONArray filesArr = new JSONArray();
                for (String f : msg.attachedFiles) {
                    filesArr.put(f);
                }
                m.put("attachedFiles", filesArr);
            }

            msgs.put(m);
        }
        obj.put("messages", msgs);
        return obj;
    }

    public static ChatSession fromJson(JSONObject obj) {
        if (obj == null) return null;
        ChatSession session = new ChatSession();
        session.id = obj.optString("id", "session_" + System.currentTimeMillis());
        session.agentId = obj.optString("agentId", "antigravity");
        session.agentName = obj.optString("agentName", "Agent");
        session.icon = obj.optString("icon", "🤖");
        session.title = obj.optString("title", "New Chat");
        session.createdAt = obj.optLong("createdAt", System.currentTimeMillis());
        session.lastUpdated = obj.optLong("lastUpdated", session.createdAt);

        JSONArray msgs = obj.optJSONArray("messages");
        if (msgs != null) {
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null) continue;
                String typeStr = m.optString("type", "AGENT");
                ChatMessage.Type type;
                try {
                    type = ChatMessage.Type.valueOf(typeStr);
                } catch (Exception e) {
                    type = ChatMessage.Type.AGENT;
                }
                String text = m.optString("text", "");
                String agentName = m.optString("agentName", null);
                String icon = m.optString("icon", null);
                ChatMessage chatMessage = new ChatMessage(type, text, agentName, icon);

                chatMessage.permissionTarget = m.optString("permissionTarget", null);
                chatMessage.permissionAction = m.optString("permissionAction", null);
                chatMessage.diffContent = m.optString("diffContent", null);
                String decStr = m.optString("permissionDecision", null);
                if (decStr != null) {
                    try {
                        chatMessage.permissionDecision = ChatMessage.PermissionDecision.valueOf(decStr);
                    } catch (Exception ignored) {
                        chatMessage.permissionDecision = ChatMessage.PermissionDecision.PENDING;
                    }
                }

                chatMessage.attachedImagePath = m.optString("attachedImagePath", null);
                JSONArray filesArr = m.optJSONArray("attachedFiles");
                if (filesArr != null) {
                    for (int fi = 0; fi < filesArr.length(); fi++) {
                        String filePath = filesArr.optString(fi);
                        if (filePath != null && !filePath.isEmpty()) {
                            chatMessage.attachedFiles.add(filePath);
                        }
                    }
                }

                session.messages.add(chatMessage);
            }
        }
        return session;
    }
}
