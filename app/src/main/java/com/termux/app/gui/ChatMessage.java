package com.termux.app.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * A single entry rendered in the agent chat GUI.
 *
 * <p>Supports user prompts, streaming agent answers, errors, status messages,
 * IDE-style permission cards, and attached images & file tags.</p>
 */
public class ChatMessage {

    public enum Type {
        /** Something the user typed. */
        USER,
        /** Normal stdout from the agent process. */
        AGENT,
        /** stderr / detected error output. */
        ERROR,
        /** Short, centered, non-bubble status line (e.g. "Installing..."). */
        STATUS,
        /** In-chat IDE-style permission request card (Allow/Reject/View Diff). */
        PERMISSION_REQUEST,
        /** Reasoning and thinking process block (collapsible). */
        THINKING
    }

    public enum PermissionDecision {
        PENDING,
        ALLOWED,
        REJECTED
    }

    public Type type;
    /** Bubble body or preview text. */
    public String text;
    /** Display name of the agent that produced this message (null for USER). */
    public final String agentName;
    /** Emoji icon shown next to agent messages. */
    public final String icon;
    /** Wall-clock time the message was created, for the timestamp label. */
    public final long timestamp;
    /** True while this bubble is still being appended to (shows a blinking cursor). */
    public boolean streaming;

    // Fields for Type.THINKING
    public boolean isThinkingExpanded = true;
    public long thinkingDurationMs = 0;

    // Fields for Type.PERMISSION_REQUEST
    public String permissionTarget;
    public String permissionAction;
    public String diffContent;
    public PermissionDecision permissionDecision = PermissionDecision.PENDING;

    // Fields for image and file attachments
    public String attachedImagePath;
    public final List<String> attachedFiles = new ArrayList<>();

    public ChatMessage(Type type, String text) {
        this(type, text, null, null);
    }

    public ChatMessage(Type type, String text, String agentName, String icon) {
        this.type = type;
        this.text = text == null ? "" : text;
        this.agentName = agentName;
        this.icon = icon;
        this.timestamp = System.currentTimeMillis();
        this.streaming = false;
    }

    public static ChatMessage createPermissionRequest(String action, String target, String diff,
                                                      String promptText, String agentName, String icon) {
        ChatMessage msg = new ChatMessage(Type.PERMISSION_REQUEST, promptText, agentName, icon);
        msg.permissionAction = action;
        msg.permissionTarget = target;
        msg.diffContent = diff;
        msg.permissionDecision = PermissionDecision.PENDING;
        return msg;
    }

    public boolean isUser() {
        return type == Type.USER;
    }

    public ChatMessage appendText(String more) {
        if (more != null && !more.isEmpty()) {
            ChatMessage next = new ChatMessage(type, text + more, agentName, icon);
            next.attachedImagePath = this.attachedImagePath;
            next.attachedFiles.addAll(this.attachedFiles);
            return next;
        }
        return this;
    }
}
