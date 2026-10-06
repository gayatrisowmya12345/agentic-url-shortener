package com.linkforge.service;

import com.linkforge.domain.workflow.WorkflowRun;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class WorkflowRepository {

    private final Map<String, WorkflowRun> storage = new ConcurrentHashMap<>();

    public WorkflowRun save(WorkflowRun run) {
        if (run == null) {
            throw new IllegalArgumentException("WorkflowRun cannot be null");
        }
        storage.put(run.getId(), run);
        return run;
    }

    public Optional<WorkflowRun> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(storage.get(id));
    }

    public List<WorkflowRun> findAll() {
        return new ArrayList<>(storage.values());
    }

    public void clear() {
        storage.clear();
    }
}
