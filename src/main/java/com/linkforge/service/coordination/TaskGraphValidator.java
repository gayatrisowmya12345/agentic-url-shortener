package com.linkforge.service.coordination;

import com.linkforge.domain.workflow.PlannedTask;
import com.linkforge.domain.workflow.specialist.exception.MissingDependencyException;
import com.linkforge.domain.workflow.specialist.exception.TaskGraphCycleException;
import com.linkforge.domain.workflow.specialist.exception.TaskGraphException;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

@Component
public class TaskGraphValidator {

    public void validate(List<PlannedTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }

        Set<String> taskIds = new HashSet<>();
        for (PlannedTask task : tasks) {
            if (task.taskId() == null || task.taskId().isBlank()) {
                throw new TaskGraphException("Task ID cannot be null or blank.");
            }
            if (!taskIds.add(task.taskId())) {
                throw new TaskGraphException("Duplicate task ID detected in task graph: " + task.taskId());
            }
        }

        // Validate that all declared dependencies exist
        for (PlannedTask task : tasks) {
            for (String dep : task.dependencies()) {
                if (!taskIds.contains(dep)) {
                    throw new MissingDependencyException(
                            "Task '" + task.taskId() + "' specifies unknown prerequisite dependency: '" + dep + "'."
                    );
                }
            }
        }

        // Cycle detection using Kahn's algorithm
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();

        for (PlannedTask task : tasks) {
            inDegree.put(task.taskId(), task.dependencies().size());
            dependents.put(task.taskId(), new ArrayList<>());
        }

        for (PlannedTask task : tasks) {
            for (String dep : task.dependencies()) {
                dependents.get(dep).add(task.taskId());
            }
        }

        Queue<String> readyQueue = new ArrayDeque<>();
        for (PlannedTask task : tasks) {
            if (inDegree.get(task.taskId()) == 0) {
                readyQueue.add(task.taskId());
            }
        }

        int visitedCount = 0;
        List<String> visitedOrder = new ArrayList<>();

        while (!readyQueue.isEmpty()) {
            String current = readyQueue.poll();
            visitedCount++;
            visitedOrder.add(current);

            for (String depOfCurrent : dependents.get(current)) {
                int newInDegree = inDegree.get(depOfCurrent) - 1;
                inDegree.put(depOfCurrent, newInDegree);
                if (newInDegree == 0) {
                    readyQueue.add(depOfCurrent);
                }
            }
        }

        if (visitedCount < tasks.size()) {
            List<String> unvisited = tasks.stream()
                    .map(PlannedTask::taskId)
                    .filter(id -> !visitedOrder.contains(id))
                    .toList();
            throw new TaskGraphCycleException(
                    "Cycle detected in task dependency graph involving tasks: " + unvisited
            );
        }
    }
}
