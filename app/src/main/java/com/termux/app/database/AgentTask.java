package com.termux.app.database;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;
import io.objectbox.annotation.Index;

@Entity
public class AgentTask {
    @Id
    public long id;

    /** Agent ID (0-25) */
    @Index
    public int agentId;

    /** Agent Name (e.g. "Orchestrator", "Frontend Developer") */
    public String agentName;

    /** The prompt/instruction given to the agent */
    public String taskPrompt;

    /** Status: PENDING, RUNNING, COMPLETED, FAILED, BLOCKED */
    @Index
    public String taskStatus;

    /** Unix timestamp */
    public long timestamp;

    /** Project ID this task belongs to */
    @Index
    public String projectId;

    /** Output result from the agent */
    public String taskOutput;

    /** The phase this task belongs to (PLANNING, DESIGN, DEV, QA, DEPLOY) */
    @Index
    public String phase;

    public AgentTask() {}

    public AgentTask(int agentId, String agentName, String taskPrompt, String projectId, String phase) {
        this.agentId = agentId;
        this.agentName = agentName;
        this.taskPrompt = taskPrompt;
        this.taskStatus = "PENDING";
        this.timestamp = System.currentTimeMillis();
        this.projectId = projectId;
        this.phase = phase;
    }
}
