package com.termux.app.gui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Renders the agent chat transcript: user prompts on the right with attached images and file tags,
 * agent output on the left, errors highlighted, status lines centred, and IDE-style permission cards.
 */
public class ChatMessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_USER = 0;
    private static final int TYPE_AGENT = 1;
    private static final int TYPE_ERROR = 2;
    private static final int TYPE_STATUS = 3;
    private static final int TYPE_PERMISSION = 4;
    private static final int TYPE_THINKING = 5;

    public interface OnPermissionActionListener {
        void onAllow(ChatMessage message, int position);
        void onReject(ChatMessage message, int position);
        void onViewDiff(ChatMessage message, int position);
    }

    private final List<ChatMessage> messages = new ArrayList<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private OnPermissionActionListener permissionListener;

    public void setOnPermissionActionListener(OnPermissionActionListener listener) {
        this.permissionListener = listener;
    }

    public void add(ChatMessage message) {
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    public void addAll(List<ChatMessage> newMessages) {
        if (newMessages == null || newMessages.isEmpty()) return;
        int start = messages.size();
        messages.addAll(newMessages);
        notifyItemRangeInserted(start, newMessages.size());
    }

    /**
     * Append output to the last bubble when it is of the same kind, otherwise start a new one.
     * This keeps a streaming agent answer inside a single bubble instead of one bubble per line.
     */
    public void appendOrAdd(ChatMessage message) {
        if (messages.isEmpty()) {
            add(message);
            return;
        }
        ChatMessage last = messages.get(messages.size() - 1);
        if (last.type == message.type && last.type != ChatMessage.Type.USER
                && last.type != ChatMessage.Type.STATUS
                && last.type != ChatMessage.Type.PERMISSION_REQUEST) {
            last.text = last.text.isEmpty() ? message.text : last.text + "\n" + message.text;
            last.streaming = message.streaming;
            if (message.thinkingDurationMs > 0) last.thinkingDurationMs = message.thinkingDurationMs;
            notifyItemChanged(messages.size() - 1);
        } else {
            add(message);
        }
    }

    /**
     * Mark the most recent agent message as an error if the process exited with a failure.
     */
    public void markLastAsError() {
        if (!messages.isEmpty()) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage msg = messages.get(i);
                if (msg.type == ChatMessage.Type.AGENT) {
                    msg.type = ChatMessage.Type.ERROR;
                    notifyItemChanged(i);
                    break;
                }
            }
        }
    }

    public int size() {
        return messages.size();
    }

    public List<ChatMessage> getMessages() {
        return new ArrayList<>(messages);
    }

    public void clear() {
        int count = messages.size();
        messages.clear();
        if (count > 0) notifyItemRangeRemoved(0, count);
    }

    @Override
    public int getItemViewType(int position) {
        switch (messages.get(position).type) {
            case USER:
                return TYPE_USER;
            case ERROR:
                return TYPE_ERROR;
            case STATUS:
                return TYPE_STATUS;
            case PERMISSION_REQUEST:
                return TYPE_PERMISSION;
            case THINKING:
                return TYPE_THINKING;
            case AGENT:
            default:
                return TYPE_AGENT;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_USER:
                return new UserHolder(inflater.inflate(R.layout.item_chat_message_user, parent, false));
            case TYPE_STATUS:
                return new StatusHolder(inflater.inflate(R.layout.item_chat_status, parent, false));
            case TYPE_PERMISSION:
                return new PermissionHolder(inflater.inflate(R.layout.item_chat_permission_card, parent, false));
            case TYPE_THINKING:
                return new ThinkingHolder(inflater.inflate(R.layout.item_chat_thinking, parent, false));
            case TYPE_ERROR:
            case TYPE_AGENT:
            default:
                return new AgentHolder(inflater.inflate(R.layout.item_chat_message, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMessage message = messages.get(position);

        if (holder instanceof UserHolder) {
            bindUserHolder((UserHolder) holder, message);
            return;
        }

        if (holder instanceof StatusHolder) {
            ((StatusHolder) holder).text.setText(message.text);
            return;
        }

        if (holder instanceof PermissionHolder) {
            bindPermissionHolder((PermissionHolder) holder, message, position);
            return;
        }

        if (holder instanceof ThinkingHolder) {
            bindThinkingHolder((ThinkingHolder) holder, message);
            return;
        }

        AgentHolder agentHolder = (AgentHolder) holder;
        agentHolder.text.setText(message.text);

        if (message.type == ChatMessage.Type.ERROR) {
            agentHolder.bubble.setBackgroundResource(R.drawable.bg_bubble_error);
        } else {
            agentHolder.bubble.setBackgroundResource(R.drawable.bg_bubble_agent);
        }

        if (message.icon != null && !message.icon.isEmpty()) {
            agentHolder.icon.setText(message.icon);
        }

        String name = message.agentName == null ? "Agent" : message.agentName;
        agentHolder.meta.setText(name + " · " + timeFormat.format(new Date(message.timestamp)));
    }

    private void bindUserHolder(UserHolder holder, ChatMessage message) {
        holder.text.setText(message.text);

        // Attached image preview
        if (message.attachedImagePath != null && !message.attachedImagePath.isEmpty()) {
            try {
                File imgFile = new File(message.attachedImagePath);
                if (imgFile.exists()) {
                    Bitmap bmp = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                    if (bmp != null) {
                        holder.imagePreview.setImageBitmap(bmp);
                        holder.imagePreview.setVisibility(View.VISIBLE);
                        final String finalImgPath = imgFile.getAbsolutePath();
                        holder.imagePreview.setOnClickListener(v -> showFullScreenImage(holder.itemView.getContext(), finalImgPath));
                    } else {
                        holder.imagePreview.setVisibility(View.GONE);
                    }
                } else {
                    holder.imagePreview.setVisibility(View.GONE);
                }
            } catch (Throwable t) {
                holder.imagePreview.setVisibility(View.GONE);
            }
        } else {
            holder.imagePreview.setVisibility(View.GONE);
        }

        // Attached file tags
        holder.tagsContainer.removeAllViews();
        if (message.attachedFiles != null && !message.attachedFiles.isEmpty()) {
            holder.tagsContainer.setVisibility(View.VISIBLE);
            LayoutInflater inflater = LayoutInflater.from(holder.itemView.getContext());
            for (String file : message.attachedFiles) {
                View chip = inflater.inflate(R.layout.item_attachment_chip, holder.tagsContainer, false);
                TextView chipText = chip.findViewById(R.id.chip_text);
                TextView chipIcon = chip.findViewById(R.id.chip_icon);
                View removeBtn = chip.findViewById(R.id.chip_remove);

                chipIcon.setText("📄");
                chipText.setText(file);
                removeBtn.setVisibility(View.GONE);
                chip.setBackgroundResource(R.drawable.bg_chip_tag);

                holder.tagsContainer.addView(chip);
            }
        } else {
            holder.tagsContainer.setVisibility(View.GONE);
        }
    }

    private void showFullScreenImage(android.content.Context context, String path) {
        if (context == null || path == null) return;
        try {
            android.app.Dialog dialog = new android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            ImageView iv = new ImageView(context);
            Bitmap bmp = BitmapFactory.decodeFile(path);
            if (bmp != null) {
                iv.setImageBitmap(bmp);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setBackgroundColor(0xFF000000);
                iv.setPadding(16, 16, 16, 16);
                iv.setOnClickListener(v -> dialog.dismiss());
                dialog.setContentView(iv);
                dialog.show();
            }
        } catch (Throwable ignored) {}
    }

    private void bindPermissionHolder(PermissionHolder holder, ChatMessage message, int position) {
        String icon = message.icon != null ? message.icon : "📝";
        holder.icon.setText(icon);

        String title = message.text != null && !message.text.trim().isEmpty()
                ? message.text
                : "Agent wants to edit " + (message.permissionTarget != null ? message.permissionTarget : "file");
        holder.title.setText(title);

        String action = message.permissionAction != null ? message.permissionAction : "edit_file";
        String target = message.permissionTarget != null ? message.permissionTarget : "Project File";
        holder.meta.setText("Action: " + action + " • Target: " + target);

        // Preview snippet
        if (message.diffContent != null && !message.diffContent.trim().isEmpty()) {
            String preview = message.diffContent.trim();
            if (preview.length() > 300) {
                preview = preview.substring(0, 300) + "\n…";
            }
            holder.preview.setText(preview);
            holder.preview.setVisibility(View.VISIBLE);
            holder.btnDiff.setVisibility(View.VISIBLE);
        } else {
            holder.preview.setText("Target: " + target);
            holder.preview.setVisibility(View.VISIBLE);
            holder.btnDiff.setVisibility(View.GONE);
        }

        ChatMessage.PermissionDecision decision = message.permissionDecision != null
                ? message.permissionDecision
                : ChatMessage.PermissionDecision.PENDING;

        if (decision == ChatMessage.PermissionDecision.ALLOWED) {
            holder.statusPill.setText("✅ Allowed");
            holder.statusPill.setBackgroundResource(R.drawable.bg_pill_allowed);
            holder.statusPill.setTextColor(0xFF2EA043);
            holder.actionsRow.setVisibility(View.GONE);
            holder.decisionText.setVisibility(View.VISIBLE);
            holder.decisionText.setText("✅ Allowed by you");
            holder.decisionText.setTextColor(0xFF3FB950);
        } else if (decision == ChatMessage.PermissionDecision.REJECTED) {
            holder.statusPill.setText("❌ Rejected");
            holder.statusPill.setBackgroundResource(R.drawable.bg_pill_rejected);
            holder.statusPill.setTextColor(0xFFDA3633);
            holder.actionsRow.setVisibility(View.GONE);
            holder.decisionText.setVisibility(View.VISIBLE);
            holder.decisionText.setText("❌ Rejected by you");
            holder.decisionText.setTextColor(0xFFFF7B72);
        } else {
            // PENDING
            holder.statusPill.setText("⏳ Pending");
            holder.statusPill.setBackgroundResource(R.drawable.bg_pill_pending);
            holder.statusPill.setTextColor(0xFFD29922);
            holder.actionsRow.setVisibility(View.VISIBLE);
            holder.decisionText.setVisibility(View.GONE);

            holder.btnAllow.setOnClickListener(v -> {
                if (permissionListener != null) {
                    permissionListener.onAllow(message, position);
                }
            });
            holder.btnReject.setOnClickListener(v -> {
                if (permissionListener != null) {
                    permissionListener.onReject(message, position);
                }
            });
            holder.btnDiff.setOnClickListener(v -> {
                if (permissionListener != null) {
                    permissionListener.onViewDiff(message, position);
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class UserHolder extends RecyclerView.ViewHolder {
        final TextView text;
        final ImageView imagePreview;
        final LinearLayout tagsContainer;

        UserHolder(View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.message_text);
            imagePreview = itemView.findViewById(R.id.user_image_preview);
            tagsContainer = itemView.findViewById(R.id.user_tags_container);
        }
    }

    static class AgentHolder extends RecyclerView.ViewHolder {
        final TextView text;
        final TextView meta;
        final TextView icon;
        final View bubble;

        AgentHolder(View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.message_text);
            meta = itemView.findViewById(R.id.message_meta);
            icon = itemView.findViewById(R.id.message_icon);
            bubble = itemView.findViewById(R.id.message_bubble);
        }
    }

    static class StatusHolder extends RecyclerView.ViewHolder {
        final TextView text;

        StatusHolder(View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.message_text);
        }
    }

    static class PermissionHolder extends RecyclerView.ViewHolder {
        final TextView icon;
        final TextView title;
        final TextView statusPill;
        final TextView meta;
        final TextView preview;
        final TextView decisionText;
        final View actionsRow;
        final Button btnDiff;
        final Button btnReject;
        final Button btnAllow;

        PermissionHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.perm_icon);
            title = itemView.findViewById(R.id.perm_title);
            statusPill = itemView.findViewById(R.id.perm_status_pill);
            meta = itemView.findViewById(R.id.perm_meta);
            preview = itemView.findViewById(R.id.perm_preview);
            decisionText = itemView.findViewById(R.id.perm_decision_text);
            actionsRow = itemView.findViewById(R.id.perm_actions_row);
            btnDiff = itemView.findViewById(R.id.btn_perm_diff);
            btnReject = itemView.findViewById(R.id.btn_perm_reject);
            btnAllow = itemView.findViewById(R.id.btn_perm_allow);
        }
    }

    private void bindThinkingHolder(ThinkingHolder holder, ChatMessage message) {
        if (message.icon != null && !message.icon.isEmpty()) {
            holder.icon.setText(message.icon);
        } else {
            holder.icon.setText("🧠");
        }
        String name = message.agentName == null ? "Agent" : message.agentName;
        holder.meta.setText(name + " · " + timeFormat.format(new Date(message.timestamp)));

        if (message.streaming) {
            holder.indicator.setText("🧠 Thinking in progress…");
        } else {
            holder.indicator.setText("💭 Thought Process");
        }

        if (message.thinkingDurationMs > 0) {
            holder.duration.setText(String.format(Locale.getDefault(), "(%.1fs)", message.thinkingDurationMs / 1000.0));
        } else {
            holder.duration.setText("");
        }

        holder.content.setText(message.text);
        holder.content.setVisibility(message.isThinkingExpanded ? View.VISIBLE : View.GONE);
        holder.toggle.setText(message.isThinkingExpanded ? "▲" : "▼");

        holder.header.setOnClickListener(v -> {
            message.isThinkingExpanded = !message.isThinkingExpanded;
            holder.content.setVisibility(message.isThinkingExpanded ? View.VISIBLE : View.GONE);
            holder.toggle.setText(message.isThinkingExpanded ? "▲" : "▼");
        });
    }

    static class ThinkingHolder extends RecyclerView.ViewHolder {
        final TextView icon;
        final TextView meta;
        final View header;
        final TextView indicator;
        final TextView duration;
        final TextView toggle;
        final TextView content;

        ThinkingHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.message_icon);
            meta = itemView.findViewById(R.id.message_meta);
            header = itemView.findViewById(R.id.thinking_header);
            indicator = itemView.findViewById(R.id.tv_thinking_indicator);
            duration = itemView.findViewById(R.id.tv_thinking_duration);
            toggle = itemView.findViewById(R.id.tv_thinking_toggle);
            content = itemView.findViewById(R.id.tv_thinking_content);
        }
    }
}
