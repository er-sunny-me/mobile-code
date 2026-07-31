package com.termux.app.database;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;
import io.objectbox.annotation.Index;

@Entity
public class AgentMemory {
    @Id
    public long id;

    /** Agent ID (0-25) that owns this memory */
    @Index
    public int agentId;

    /** Memory category: DURABLE, EPHEMERAL, CONTEXT, PREFERENCE */
    public String memoryType;

    /** Key for lookup (e.g. "user_ui_preference", "tech_stack") */
    @Index
    public String key;

    /** The actual memory content (text, JSON, markdown) */
    public String value;

    /** Project ID scope (null = global memory) */
    @Index
    public String projectId;

    /** Unix timestamp of creation */
    public long createdAt;

    /** Unix timestamp of last update */
    public long updatedAt;

    /** Relevance score (for RAG ranking, 0.0 to 1.0) */
    public double relevanceScore;

    /** Tags for keyword search (comma-separated) */
    public String tags;

    public AgentMemory() {}

    public AgentMemory(int agentId, String memoryType, String key, String value, String projectId) {
        this.agentId = agentId;
        this.memoryType = memoryType;
        this.key = key;
        this.value = value;
        this.projectId = projectId;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
        this.relevanceScore = 1.0;
    }
}
