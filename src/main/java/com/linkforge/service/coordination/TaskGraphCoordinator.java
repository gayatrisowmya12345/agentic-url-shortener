package com.linkforge.service.coordination;

import com.linkforge.agent.specialist.SpecialistAgent;
import com.linkforge.agent.specialist.SpecialistRegistry;
import com.linkforge.domain.workflow.PlannedTask;
import com.linkforge.domain.workflow.scenario.RepositoryEvidence;
import com.linkforge.domain.workflow.scenario.Scenario;
import com.linkforge.domain.workflow.specialist.SpecialistInvocation;
import com.linkforge.domain.workflow.specialist.SpecialistTaskInput;
import com.linkforge.domain.workflow.specialist.SpecialistTaskResult;
import com.linkforge.domain.workflow.specialist.exception.SpecialistExecutionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class TaskGraphCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TaskGraphCoordinator.class);

    private final TaskGraphValidator validator;
    private final SpecialistRegistry specialistRegistry;
    private final SpecialistCoordinationProperties properties;
    private final ObjectMapper invocationObjectMapper;

    public TaskGraphCoordinator(
            TaskGraphValidator validator,
            SpecialistRegistry specialistRegistry,
            SpecialistCoordinationProperties properties
    ) {
        this.validator = validator;
        this.specialistRegistry = specialistRegistry;
        this.properties = properties;
        this.invocationObjectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    public CoordinationResult coordinate(
            List<PlannedTask> tasks,
            String requirement,
            List<String> acceptanceCriteria,
            Scenario scenario,
            RepositoryEvidence evidence
    ) {
        if (tasks == null || tasks.isEmpty()) {
            return new CoordinationResult(List.of(), List.of(), true, 0, 0, 0);
        }

        // 1. Validate task graph for cycles and missing dependencies
        validator.validate(tasks);

        // 2. Bound total agent invocations
        if (tasks.size() > properties.getMaxInvocations()) {
            throw new SpecialistExecutionException(
                    "Task count (" + tasks.size() + ") exceeds maximum allowed invocations (" + properties.getMaxInvocations() + ")."
            );
        }

        // 3. Prepare coordination tracking
        int concurrency = Math.max(1, properties.getMaxConcurrency());
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);

        Map<String, SpecialistTaskResult> taskResults = new ConcurrentHashMap<>();
        Map<String, String> dependencySummaries = new ConcurrentHashMap<>();
        Set<String> completedTaskIds = Collections.synchronizedSet(new HashSet<>());
        Set<String> failedTaskIds = Collections.synchronizedSet(new HashSet<>());
        Set<String> skippedTaskIds = Collections.synchronizedSet(new HashSet<>());
        Set<String> pendingTaskIds = new HashSet<>();
        Map<String, PlannedTask> taskMap = new HashMap<>();

        for (PlannedTask task : tasks) {
            pendingTaskIds.add(task.taskId());
            taskMap.put(task.taskId(), task);
        }

        try {
            // 4. Wave-based topological execution with concurrency
            while (!pendingTaskIds.isEmpty()) {
                // Find all tasks whose dependencies are satisfied
                List<PlannedTask> readyTasks = new ArrayList<>();
                for (String taskId : pendingTaskIds) {
                    PlannedTask task = taskMap.get(taskId);
                    boolean allDepsSucceeded = task.dependencies().stream().allMatch(completedTaskIds::contains);
                    boolean anyDepFailed = task.dependencies().stream().anyMatch(dep -> failedTaskIds.contains(dep) || skippedTaskIds.contains(dep));

                    if (anyDepFailed) {
                        // Mark task as skipped because a prerequisite failed
                        skippedTaskIds.add(taskId);
                    } else if (allDepsSucceeded) {
                        readyTasks.add(task);
                    }
                }

                // If some tasks were skipped, remove them from pending
                if (!skippedTaskIds.isEmpty()) {
                    pendingTaskIds.removeAll(skippedTaskIds);
                }

                if (readyTasks.isEmpty()) {
                    // No tasks can make progress; skip any remaining pending tasks
                    for (String remainingId : pendingTaskIds) {
                        skippedTaskIds.add(remainingId);
                    }
                    pendingTaskIds.clear();
                    break;
                }

                // Execute ready tasks in parallel up to maxConcurrency
                List<CompletableFuture<SpecialistTaskResult>> futures = new ArrayList<>();
                for (PlannedTask readyTask : readyTasks) {
                    pendingTaskIds.remove(readyTask.taskId());

                    CompletableFuture<SpecialistTaskResult> future = CompletableFuture.supplyAsync(() -> {
                        SpecialistAgent agent = specialistRegistry.resolveAgentForTask(readyTask);
                        Map<String, Object> relevantEvidence = extractRelevantEvidence(readyTask, evidence);

                        Map<String, String> depOutputs = new HashMap<>();
                        for (String dep : readyTask.dependencies()) {
                            if (dependencySummaries.containsKey(dep)) {
                                depOutputs.put(dep, dependencySummaries.get(dep));
                            }
                        }

                        SpecialistTaskInput input = new SpecialistTaskInput(
                                readyTask.taskId(),
                                readyTask.title(),
                                readyTask.description(),
                                readyTask.dependencies(),
                                depOutputs,
                                requirement,
                                acceptanceCriteria,
                                scenario,
                                relevantEvidence
                        );

                        return agent.execute(input);
                    }, executor);

                    futures.add(future);
                }

                // Wait for this wave to complete with per-task timeout bounds
                for (int i = 0; i < readyTasks.size(); i++) {
                    PlannedTask readyTask = readyTasks.get(i);
                    CompletableFuture<SpecialistTaskResult> future = futures.get(i);
                    SpecialistTaskResult result;
                    try {
                        result = future.get(properties.getTaskTimeoutSeconds(), TimeUnit.SECONDS);
                    } catch (TimeoutException te) {
                        log.warn("Specialist task {} timed out after {}s", readyTask.taskId(), properties.getTaskTimeoutSeconds());
                        SpecialistAgent agent = specialistRegistry.resolveAgentForTask(readyTask);
                        result = new SpecialistTaskResult(
                                readyTask.taskId(),
                                agent.getRole(),
                                agent.getAgentName(),
                                "FAILED",
                                "Task timed out after " + properties.getTaskTimeoutSeconds() + " seconds.",
                                List.of(),
                                List.of(),
                                List.of(),
                                Map.of("timeout", true),
                                true,
                                "TimeoutException",
                                Instant.now(),
                                Instant.now()
                        );
                    } catch (Exception ex) {
                        log.warn("Specialist task {} encountered execution error: {}", readyTask.taskId(), ex.getMessage());
                        SpecialistAgent agent = specialistRegistry.resolveAgentForTask(readyTask);
                        result = new SpecialistTaskResult(
                                readyTask.taskId(),
                                agent.getRole(),
                                agent.getAgentName(),
                                "FAILED",
                                "Task execution failed: " + ex.getMessage(),
                                List.of(),
                                List.of(),
                                List.of(),
                                Map.of("error", true),
                                true,
                                ex.getClass().getSimpleName(),
                                Instant.now(),
                                Instant.now()
                        );
                    }

                    taskResults.put(readyTask.taskId(), result);
                    if ("SUCCESS".equalsIgnoreCase(result.status())) {
                        completedTaskIds.add(readyTask.taskId());
                        dependencySummaries.put(readyTask.taskId(), result.summary());
                    } else {
                        failedTaskIds.add(readyTask.taskId());
                    }
                }
            }
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // 5. Construct deterministically ordered output matching the original plan
        List<SpecialistInvocation> invocations = new ArrayList<>();
        List<PlannedTask> updatedTasks = new ArrayList<>();

        for (PlannedTask originalTask : tasks) {
            String taskId = originalTask.taskId();
            if (completedTaskIds.contains(taskId)) {
                SpecialistTaskResult res = taskResults.get(taskId);
                SpecialistInvocation inv = boundAndCreateInvocation(res, originalTask.title());
                invocations.add(inv);
                updatedTasks.add(originalTask.withStatus("COMPLETED"));
            } else if (failedTaskIds.contains(taskId)) {
                SpecialistTaskResult res = taskResults.get(taskId);
                SpecialistInvocation inv = boundAndCreateInvocation(res, originalTask.title());
                invocations.add(inv);
                updatedTasks.add(originalTask.withStatus("FAILED"));
            } else {
                // Task was skipped due to prerequisite failure
                updatedTasks.add(originalTask.withStatus("SKIPPED"));
            }
        }

        boolean allSuccessful = failedTaskIds.isEmpty() && skippedTaskIds.isEmpty();
        return new CoordinationResult(
                invocations,
                updatedTasks,
                allSuccessful,
                completedTaskIds.size(),
                failedTaskIds.size(),
                skippedTaskIds.size()
        );
    }

    private SpecialistInvocation boundAndCreateInvocation(SpecialistTaskResult result, String taskTitle) {
        String invocationId = UUID.randomUUID().toString();
        int maxBudget = properties.getMaxOutputChars();

        // 1. Enforce initial per-field and list-count limits before persistence
        String inputSummary = "Task " + result.taskId() + ": " + (taskTitle != null ? taskTitle : "");
        if (inputSummary.length() > 500) {
            inputSummary = inputSummary.substring(0, 497) + "...";
        }

        String summary = result.summary() != null ? result.summary() : "";
        if (summary.length() > 1000) {
            summary = summary.substring(0, 997) + "...";
        }

        List<String> rawRecs = result.recommendations() != null ? result.recommendations() : List.of();
        List<String> boundedRecs = rawRecs.stream()
                .filter(r -> r != null && !r.isBlank())
                .limit(10)
                .map(r -> r.length() > 500 ? r.substring(0, 497) + "..." : r)
                .toList();

        List<String> rawTests = result.testIdeas() != null ? result.testIdeas() : List.of();
        List<String> boundedTests = rawTests.stream()
                .filter(t -> t != null && !t.isBlank())
                .limit(10)
                .map(t -> t.length() > 500 ? t.substring(0, 497) + "..." : t)
                .toList();

        List<String> rawCriteria = result.addressedCriteria() != null ? result.addressedCriteria() : List.of();
        List<String> boundedCriteria = rawCriteria.stream()
                .filter(c -> c != null && !c.isBlank())
                .limit(10)
                .map(c -> c.length() > 50 ? c.substring(0, 47) + "..." : c)
                .toList();

        String provider = result.metadata() != null ? String.valueOf(result.metadata().getOrDefault("provider", "deterministic")) : "deterministic";
        if (provider.length() > 100) {
            provider = provider.substring(0, 97) + "...";
        }

        String model = result.metadata() != null ? String.valueOf(result.metadata().getOrDefault("model", "rules")) : "rules";
        if (model.length() > 100) {
            model = model.substring(0, 97) + "...";
        }

        String fallbackReason = result.fallbackReason();
        if (fallbackReason != null && fallbackReason.length() > 500) {
            fallbackReason = fallbackReason.substring(0, 497) + "...";
        }

        SpecialistInvocation invocation = createInvocation(
                invocationId, result, inputSummary, summary,
                boundedRecs, boundedTests, boundedCriteria,
                provider, model, fallbackReason
        );

        String json = serializeInvocation(invocation);

        if (json.length() > maxBudget) {
            // Priority order of variable-length field trimming to reach budget:
            // 1. recommendations
            List<String> curRecs = new ArrayList<>(boundedRecs);
            while (json.length() > maxBudget && !curRecs.isEmpty()) {
                curRecs.remove(curRecs.size() - 1);
                invocation = createInvocation(invocationId, result, inputSummary, summary, curRecs, boundedTests, boundedCriteria, provider, model, fallbackReason);
                json = serializeInvocation(invocation);
            }
            boundedRecs = curRecs;

            // 2. testIdeas
            List<String> curTests = new ArrayList<>(boundedTests);
            while (json.length() > maxBudget && !curTests.isEmpty()) {
                curTests.remove(curTests.size() - 1);
                invocation = createInvocation(invocationId, result, inputSummary, summary, boundedRecs, curTests, boundedCriteria, provider, model, fallbackReason);
                json = serializeInvocation(invocation);
            }
            boundedTests = curTests;

            // 3. outputSummary
            if (json.length() > maxBudget && !summary.isEmpty()) {
                int excess = json.length() - maxBudget;
                int targetLen = Math.max(0, summary.length() - excess);
                summary = targetLen > 15 ? summary.substring(0, targetLen - 3) + "..." : (targetLen > 0 ? summary.substring(0, targetLen) : "");
                invocation = createInvocation(invocationId, result, inputSummary, summary, boundedRecs, boundedTests, boundedCriteria, provider, model, fallbackReason);
                json = serializeInvocation(invocation);
            }

            // 4. fallbackReason
            if (json.length() > maxBudget && fallbackReason != null && !fallbackReason.isEmpty()) {
                int excess = json.length() - maxBudget;
                int targetLen = Math.max(0, fallbackReason.length() - excess);
                fallbackReason = targetLen > 15 ? fallbackReason.substring(0, targetLen - 3) + "..." : (targetLen > 0 ? fallbackReason.substring(0, targetLen) : "");
                invocation = createInvocation(invocationId, result, inputSummary, summary, boundedRecs, boundedTests, boundedCriteria, provider, model, fallbackReason);
                json = serializeInvocation(invocation);
            }

            // 5. inputSummary
            if (json.length() > maxBudget && !inputSummary.isEmpty()) {
                int excess = json.length() - maxBudget;
                int targetLen = Math.max(0, inputSummary.length() - excess);
                inputSummary = targetLen > 15 ? inputSummary.substring(0, targetLen - 3) + "..." : (targetLen > 0 ? inputSummary.substring(0, targetLen) : "");
                invocation = createInvocation(invocationId, result, inputSummary, summary, boundedRecs, boundedTests, boundedCriteria, provider, model, fallbackReason);
                json = serializeInvocation(invocation);
            }

            // 6. addressedCriteria
            List<String> curCriteria = new ArrayList<>(boundedCriteria);
            while (json.length() > maxBudget && !curCriteria.isEmpty()) {
                curCriteria.remove(curCriteria.size() - 1);
                invocation = createInvocation(invocationId, result, inputSummary, summary, boundedRecs, boundedTests, curCriteria, provider, model, fallbackReason);
                json = serializeInvocation(invocation);
            }
            boundedCriteria = curCriteria;

            // 7. provider
            if (json.length() > maxBudget && !provider.isEmpty()) {
                int excess = json.length() - maxBudget;
                int targetLen = Math.max(0, provider.length() - excess);
                provider = targetLen > 0 ? provider.substring(0, targetLen) : "";
                invocation = createInvocation(invocationId, result, inputSummary, summary, boundedRecs, boundedTests, boundedCriteria, provider, model, fallbackReason);
                json = serializeInvocation(invocation);
            }

            // 8. model
            if (json.length() > maxBudget && !model.isEmpty()) {
                int excess = json.length() - maxBudget;
                int targetLen = Math.max(0, model.length() - excess);
                model = targetLen > 0 ? model.substring(0, targetLen) : "";
                invocation = createInvocation(invocationId, result, inputSummary, summary, boundedRecs, boundedTests, boundedCriteria, provider, model, fallbackReason);
                json = serializeInvocation(invocation);
            }

            // 9. Fail safely if even minimal fixed fields exceed maxBudget
            if (json.length() > maxBudget) {
                throw new SpecialistExecutionException(
                        "Configured output budget (" + maxBudget + " chars) is too small to fit specialist invocation (minimal serialized size is " + json.length() + " chars)."
                );
            }
        }

        return invocation;
    }

    private SpecialistInvocation createInvocation(
            String invocationId,
            SpecialistTaskResult result,
            String inputSummary,
            String outputSummary,
            List<String> recommendations,
            List<String> testIdeas,
            List<String> addressedCriteria,
            String provider,
            String model,
            String fallbackReason
    ) {
        return new SpecialistInvocation(
                invocationId,
                result.taskId(),
                result.agentName(),
                result.role() != null ? result.role().name() : "SPECIALIST",
                result.status(),
                inputSummary,
                outputSummary,
                recommendations,
                testIdeas,
                addressedCriteria,
                provider,
                model,
                result.fallbackOccurred(),
                fallbackReason,
                result.startedAt(),
                result.completedAt()
        );
    }

    private String serializeInvocation(SpecialistInvocation invocation) {
        try {
            return invocationObjectMapper.writeValueAsString(invocation);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SpecialistExecutionException("Failed to serialize specialist invocation: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> extractRelevantEvidence(PlannedTask task, RepositoryEvidence evidence) {
        if (evidence == null || !evidence.hasEvidence()) {
            return Map.of();
        }

        Map<String, Object> relevant = new HashMap<>();
        String text = (task.title() + " " + task.description()).toLowerCase();

        if (text.contains("baseline") || text.contains("inspection") || text.contains("structure")) {
            relevant.put("projectFileNames", evidence.projectFileNames());
            relevant.put("detectedFrameworks", evidence.detectedFrameworks());
            relevant.put("detectedLanguages", evidence.detectedLanguages());
            relevant.put("totalFiles", evidence.totalFiles());
        } else if (text.contains("extension") || text.contains("architecture") || text.contains("alignment")) {
            relevant.put("detectedFrameworks", evidence.detectedFrameworks());
            relevant.put("detectedLanguages", evidence.detectedLanguages());
        } else if (text.contains("regression") || text.contains("compatibility") || text.contains("test")) {
            relevant.put("sampleSourcePaths", evidence.sampleSourcePaths());
            relevant.put("detectedFrameworks", evidence.detectedFrameworks());
        } else {
            relevant.put("detectedFrameworks", evidence.detectedFrameworks());
        }

        return relevant;
    }
}
