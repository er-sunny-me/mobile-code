package com.termux.app.database;

import java.util.ArrayList;
import java.util.List;

import io.objectbox.Box;
import io.objectbox.BoxStore;
import io.objectbox.query.Query;
import io.objectbox.query.QueryBuilder;

public class AgentRepository {

    private final Box<AgentTask> taskBox;
    private final Box<AgentMemory> memoryBox;

    public AgentRepository() {
        BoxStore store = ObjectBoxStore.get();
        this.taskBox = store.boxFor(AgentTask.class);
        this.memoryBox = store.boxFor(AgentMemory.class);
    }

    // ==================== AgentTask CRUD ====================

    public long saveTask(AgentTask task) {
        return taskBox.put(task);
    }

    public AgentTask getTask(long id) {
        return taskBox.get(id);
    }

    public List<AgentTask> getTasksByAgent(int agentId) {
        Query<AgentTask> query = taskBox.query(AgentTask_.agentId.equal(agentId)).build();
        List<AgentTask> results = query.find();
        query.close();
        return results;
    }

    public List<AgentTask> getTasksByProject(String projectId) {
        if (projectId == null) return new ArrayList<>();
        Query<AgentTask> query = taskBox.query(
                AgentTask_.projectId.equal(projectId, QueryBuilder.StringOrder.CASE_SENSITIVE)
        ).build();
        List<AgentTask> results = query.find();
        query.close();
        return results;
    }

    public List<AgentTask> getTasksByStatus(String status) {
        if (status == null) return new ArrayList<>();
        Query<AgentTask> query = taskBox.query(
                AgentTask_.taskStatus.equal(status, QueryBuilder.StringOrder.CASE_SENSITIVE)
        ).build();
        List<AgentTask> results = query.find();
        query.close();
        return results;
    }

    public List<AgentTask> getTasksByPhase(String projectId, String phase) {
        if (projectId == null || phase == null) return new ArrayList<>();
        Query<AgentTask> query = taskBox.query(
                AgentTask_.projectId.equal(projectId, QueryBuilder.StringOrder.CASE_SENSITIVE)
                        .and(AgentTask_.phase.equal(phase, QueryBuilder.StringOrder.CASE_SENSITIVE))
        ).build();
        List<AgentTask> results = query.find();
        query.close();
        return results;
    }

    public void updateTaskStatus(long taskId, String status, String output) {
        AgentTask task = taskBox.get(taskId);
        if (task != null) {
            task.taskStatus = status;
            task.taskOutput = output;
            taskBox.put(task);
        }
    }

    public void deleteTask(long id) {
        taskBox.remove(id);
    }

    public void deleteAllTasksForProject(String projectId) {
        List<AgentTask> tasks = getTasksByProject(projectId);
        taskBox.remove(tasks);
    }

    // ==================== AgentMemory CRUD ====================

    public long saveMemory(AgentMemory memory) {
        memory.updatedAt = System.currentTimeMillis();
        return memoryBox.put(memory);
    }

    public AgentMemory getMemory(long id) {
        return memoryBox.get(id);
    }

    public List<AgentMemory> getMemoriesByAgent(int agentId) {
        Query<AgentMemory> query = memoryBox.query(AgentMemory_.agentId.equal(agentId)).build();
        List<AgentMemory> results = query.find();
        query.close();
        return results;
    }

    public List<AgentMemory> getMemoriesByAgentAndType(int agentId, String memoryType) {
        if (memoryType == null) return new ArrayList<>();
        Query<AgentMemory> query = memoryBox.query(
                AgentMemory_.agentId.equal(agentId)
                        .and(AgentMemory_.memoryType.equal(memoryType, QueryBuilder.StringOrder.CASE_SENSITIVE))
        ).build();
        List<AgentMemory> results = query.find();
        query.close();
        return results;
    }

    public AgentMemory getMemoryByKey(int agentId, String key) {
        if (key == null) return null;
        Query<AgentMemory> query = memoryBox.query(
                AgentMemory_.agentId.equal(agentId)
                        .and(AgentMemory_.key.equal(key, QueryBuilder.StringOrder.CASE_SENSITIVE))
        ).build();
        List<AgentMemory> results = query.find();
        query.close();
        return results.isEmpty() ? null : results.get(0);
    }

    public AgentMemory getMemoryByKeyAndProject(int agentId, String key, String projectId) {
        if (key == null) return null;
        QueryBuilder<AgentMemory> qb = memoryBox.query(
                AgentMemory_.agentId.equal(agentId)
                        .and(AgentMemory_.key.equal(key, QueryBuilder.StringOrder.CASE_SENSITIVE))
        );
        if (projectId != null) {
            qb = memoryBox.query(
                    AgentMemory_.agentId.equal(agentId)
                            .and(AgentMemory_.key.equal(key, QueryBuilder.StringOrder.CASE_SENSITIVE))
                            .and(AgentMemory_.projectId.equal(projectId, QueryBuilder.StringOrder.CASE_SENSITIVE))
            );
        }
        Query<AgentMemory> query = qb.build();
        List<AgentMemory> results = query.find();
        query.close();
        return results.isEmpty() ? null : results.get(0);
    }

    public void upsertMemory(int agentId, String key, String value, String memoryType, String projectId) {
        AgentMemory existing = getMemoryByKeyAndProject(agentId, key, projectId);
        if (existing != null) {
            existing.value = value;
            existing.updatedAt = System.currentTimeMillis();
            memoryBox.put(existing);
        } else {
            memoryBox.put(new AgentMemory(agentId, memoryType, key, value, projectId));
        }
    }

    public List<AgentMemory> searchMemoryByTags(String keyword) {
        if (keyword == null) return new ArrayList<>();
        Query<AgentMemory> query = memoryBox.query(
                AgentMemory_.tags.contains(keyword, QueryBuilder.StringOrder.CASE_INSENSITIVE)
                        .or(AgentMemory_.value.contains(keyword, QueryBuilder.StringOrder.CASE_INSENSITIVE))
        ).build();
        List<AgentMemory> results = query.find();
        query.close();
        return results;
    }

    public List<AgentMemory> getProjectMemories(String projectId) {
        if (projectId == null) return new ArrayList<>();
        Query<AgentMemory> query = memoryBox.query(
                AgentMemory_.projectId.equal(projectId, QueryBuilder.StringOrder.CASE_SENSITIVE)
        ).build();
        List<AgentMemory> results = query.find();
        query.close();
        return results;
    }

    public void deleteMemory(long id) {
        memoryBox.remove(id);
    }

    public void clearAgentMemory(int agentId) {
        List<AgentMemory> memories = getMemoriesByAgent(agentId);
        memoryBox.remove(memories);
    }

    public void clearAllMemory() {
        memoryBox.removeAll();
    }

    // ==================== Memory Manager Helpers ====================

    /**
     * Get filtered memory for a requesting agent based on ACL rules.
     * The Memory Manager (Agent 00) uses this to enforce scope isolation.
     */
    public String getFilteredContextForAgent(int requestingAgentId, String projectId) {
        StringBuilder context = new StringBuilder();

        // Get project-scoped memories (only if projectId is not null)
        if (projectId != null) {
            Query<AgentMemory> query = memoryBox.query(
                    AgentMemory_.agentId.equal(requestingAgentId)
                            .and(AgentMemory_.projectId.equal(projectId, QueryBuilder.StringOrder.CASE_SENSITIVE))
            ).build();
            List<AgentMemory> agentMemories = query.find();
            query.close();

            for (AgentMemory mem : agentMemories) {
                context.append("[").append(mem.key).append("]: ").append(mem.value).append("\n");
            }
        }

        // Also get global memories for this agent
        Query<AgentMemory> globalQuery = memoryBox.query(
                AgentMemory_.agentId.equal(requestingAgentId)
                        .and(AgentMemory_.projectId.isNull())
        ).build();
        List<AgentMemory> globalMemories = globalQuery.find();
        globalQuery.close();

        for (AgentMemory mem : globalMemories) {
            context.append("[GLOBAL:").append(mem.key).append("]: ").append(mem.value).append("\n");
        }

        return context.toString();
    }
}
