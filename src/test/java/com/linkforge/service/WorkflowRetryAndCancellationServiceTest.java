package com.linkforge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.agent.DependencyAwarePlannerAgent;
import com.linkforge.agent.RequirementInterpreterAgent;
import com.linkforge.agent.RequirementInterpretationResult;
import com.linkforge.agent.ScenarioClassifierAgent;
import com.linkforge.agent.specialist.SpecialistAgent;
import com.linkforge.agent.specialist.SpecialistRegistry;
import com.linkforge.domain.workflow.specialist.SpecialistRole;
import com.linkforge.domain.workflow.specialist.SpecialistTaskInput;
import com.linkforge.domain.workflow.specialist.SpecialistTaskResult;
import com.linkforge.domain.workflow.PlannedTask;
import com.linkforge.domain.workflow.WorkflowApproval;
import com.linkforge.domain.workflow.WorkflowCancellation;
import com.linkforge.domain.workflow.WorkflowEvent;
import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.domain.workflow.WorkflowStage;
import com.linkforge.domain.workflow.WorkflowStatus;
import com.linkforge.domain.workflow.scenario.RepositoryEvidence;
import com.linkforge.domain.workflow.scenario.Scenario;
import com.linkforge.domain.workflow.scenario.exception.InspectionException;
import com.linkforge.domain.workflow.specialist.SpecialistInvocation;
import com.linkforge.domain.workflow.specialist.exception.TaskGraphCycleException;
import com.linkforge.service.coordination.SpecialistCoordinationProperties;
import com.linkforge.service.coordination.TaskGraphCoordinator;
import com.linkforge.service.coordination.TaskGraphValidator;
import com.linkforge.service.inspection.CodebaseInspectionProperties;
import com.linkforge.service.inspection.CodebaseInspector;
import com.linkforge.service.retry.FailureClassification;
import com.linkforge.service.retry.FailureClassifier;
import com.linkforge.service.retry.RetriesExhaustedException;
import com.linkforge.service.retry.Sleeper;
import com.linkforge.service.retry.TransientWorkflowException;
import com.linkforge.service.retry.WorkflowRetryProperties;
import com.linkforge.service.security.WorkflowAuthorizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowRetryAndCancellationServiceTest {

    private WorkflowOrchestrator orchestrator;
    private WorkflowRepository repository;
    private RecordingSleeper sleeper;
    private WorkflowRetryProperties retryProperties;

    @BeforeEach
    void setUp() {
        repository = new WorkflowRepository();
        sleeper = new RecordingSleeper();
        retryProperties = new WorkflowRetryProperties();
        retryProperties.setMaxAttempts(3);
        retryProperties.setInitialBackoffMs(100);
        retryProperties.setMaxBackoffMs(1000);
        retryProperties.setBackoffMultiplier(2.0);

        orchestrator = new WorkflowOrchestrator(
                new ScenarioClassifierAgent(),
                new CodebaseInspector(new CodebaseInspectionProperties()),
                new RequirementInterpreterAgent(),
                new DependencyAwarePlannerAgent(),
                repository
        ).withRetryProperties(retryProperties).withSleeper(sleeper);
    }

    // ==========================================
    // 1. BOUNDED RETRIES & EXPONENTIAL BACKOFF
    // ==========================================

    @Test
    @DisplayName("Default retry properties match intended application and documentation defaults")
    void defaultRetryPropertiesMatchIntendedDefaults() {
        WorkflowRetryProperties defaults = new WorkflowRetryProperties();
        assertThat(defaults.isEnabled()).isTrue();
        assertThat(defaults.getMaxAttempts()).isEqualTo(3);
        assertThat(defaults.getInitialBackoffMs()).isEqualTo(500L);
        assertThat(defaults.getMaxBackoffMs()).isEqualTo(5000L);
        assertThat(defaults.getBackoffMultiplier()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Exponential backoff calculates bounded delays with multiplier and cap")
    void exponentialBackoffCalculatesBoundedDelays() {
        WorkflowRetryProperties props = new WorkflowRetryProperties();
        props.setInitialBackoffMs(500);
        props.setBackoffMultiplier(2.0);
        props.setMaxBackoffMs(3000);

        assertThat(props.calculateBackoffMs(1)).isEqualTo(500);
        assertThat(props.calculateBackoffMs(2)).isEqualTo(1000);
        assertThat(props.calculateBackoffMs(3)).isEqualTo(2000);
        assertThat(props.calculateBackoffMs(4)).isEqualTo(3000); // capped at max
        assertThat(props.calculateBackoffMs(5)).isEqualTo(3000); // capped at max
    }

    @Test
    @DisplayName("FailureClassifier correctly distinguishes transient from non-retryable exceptions")
    void failureClassifierDistinguishesTransientFromNonRetryable() {
        // Transient
        assertThat(FailureClassifier.classify(new TransientWorkflowException("Connection lost")))
                .isEqualTo(FailureClassification.TRANSIENT);
        assertThat(FailureClassifier.classify(new TimeoutException("Operation timed out")))
                .isEqualTo(FailureClassification.TRANSIENT);
        assertThat(FailureClassifier.classify(new SocketTimeoutException("Read timed out")))
                .isEqualTo(FailureClassification.TRANSIENT);
        assertThat(FailureClassifier.classify(new ConnectException("Connection refused")))
                .isEqualTo(FailureClassification.TRANSIENT);
        assertThat(FailureClassifier.classify(new RuntimeException("Rate limit exceeded: 429 Too Many Requests")))
                .isEqualTo(FailureClassification.TRANSIENT);
        assertThat(FailureClassifier.classify(new RuntimeException("Wrapped", new TimeoutException("nested timeout"))))
                .isEqualTo(FailureClassification.TRANSIENT);

        // Non-retryable
        assertThat(FailureClassifier.classify(new IllegalArgumentException("Invalid input")))
                .isEqualTo(FailureClassification.NON_RETRYABLE);
        assertThat(FailureClassifier.classify(new IllegalStateException("Invalid state")))
                .isEqualTo(FailureClassification.NON_RETRYABLE);
        assertThat(FailureClassifier.classify(new WorkflowAuthorizationException("Unauthorized token")))
                .isEqualTo(FailureClassification.NON_RETRYABLE);
        assertThat(FailureClassifier.classify(new InspectionException("Traversal error")))
                .isEqualTo(FailureClassification.NON_RETRYABLE);
        assertThat(FailureClassifier.classify(new TaskGraphCycleException("Cycle detected")))
                .isEqualTo(FailureClassification.NON_RETRYABLE);
    }

    @Test
    @DisplayName("Transient stage failure retries with backoff and succeeds on subsequent attempt")
    void transientStageFailureRetriesAndSucceeds() {
        AtomicInteger attemptCounter = new AtomicInteger(0);

        RequirementInterpreterAgent flakyInterpreter = new RequirementInterpreterAgent() {
            @Override
            public RequirementInterpretationResult interpret(String text, RepositoryEvidence evidence) {
                int count = attemptCounter.incrementAndGet();
                if (count == 1) {
                    throw new TransientWorkflowException("Temporary Ollama connection timeout");
                }
                return super.interpret(text, evidence);
            }
        };

        WorkflowOrchestrator customOrchestrator = new WorkflowOrchestrator(
                new ScenarioClassifierAgent(),
                new CodebaseInspector(new CodebaseInspectionProperties()),
                flakyInterpreter,
                new DependencyAwarePlannerAgent(),
                repository
        ).withRetryProperties(retryProperties).withSleeper(sleeper);

        WorkflowRun run = customOrchestrator.startWorkflow("Create a standard URL shortening service");

        assertThat(attemptCounter.get()).isEqualTo(2);
        assertThat(sleeper.delays).containsExactly(100L); // attempt 1 backoff: 100ms
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_APPROVAL);

        // Verify audit trail
        List<String> eventTypes = run.getEvents().stream().map(WorkflowEvent::eventType).toList();
        assertThat(eventTypes).contains(
                "STAGE_EXECUTION_ATTEMPT",
                "STAGE_TRANSIENT_FAILURE",
                "STAGE_RETRY_SCHEDULED",
                "STAGE_RETRY_SUCCEEDED"
        );

        WorkflowEvent retryScheduled = run.getEvents().stream()
                .filter(e -> "STAGE_RETRY_SCHEDULED".equals(e.eventType()))
                .findFirst().orElseThrow();
        assertThat(retryScheduled.description()).contains("100ms backoff");

        WorkflowEvent retrySucceeded = run.getEvents().stream()
                .filter(e -> "STAGE_RETRY_SUCCEEDED".equals(e.eventType()))
                .findFirst().orElseThrow();
        assertThat(retrySucceeded.description()).contains("attempt 2");
    }

    @Test
    @DisplayName("Retries exhausted transitions workflow to FAILED with clear audit records")
    void retriesExhaustedTransitionsWorkflowToFailed() {
        AtomicInteger attemptCounter = new AtomicInteger(0);

        RequirementInterpreterAgent persistentlyFailingInterpreter = new RequirementInterpreterAgent() {
            @Override
            public RequirementInterpretationResult interpret(String text, RepositoryEvidence evidence) {
                attemptCounter.incrementAndGet();
                throw new TransientWorkflowException("Persistent downstream model unavailability");
            }
        };

        WorkflowOrchestrator customOrchestrator = new WorkflowOrchestrator(
                new ScenarioClassifierAgent(),
                new CodebaseInspector(new CodebaseInspectionProperties()),
                persistentlyFailingInterpreter,
                new DependencyAwarePlannerAgent(),
                repository
        ).withRetryProperties(retryProperties).withSleeper(sleeper);

        WorkflowRun run = customOrchestrator.startWorkflow("Create a standard URL shortening service");

        assertThat(attemptCounter.get()).isEqualTo(3);
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(run.getCurrentStage()).isEqualTo(WorkflowStage.REQUIREMENT_INTERPRETATION);
        assertThat(sleeper.delays).containsExactly(100L, 200L); // attempts 1 and 2 backoffs

        List<String> eventTypes = run.getEvents().stream().map(WorkflowEvent::eventType).toList();
        assertThat(eventTypes).contains("RETRY_EXHAUSTED");

        WorkflowEvent exhaustedEvent = run.getEvents().stream()
                .filter(e -> "RETRY_EXHAUSTED".equals(e.eventType()))
                .findFirst().orElseThrow();
        assertThat(exhaustedEvent.description()).contains("3/3");
        assertThat(exhaustedEvent.description()).contains("Persistent downstream model unavailability");
    }

    @Test
    @DisplayName("Non-retryable failure fails immediately on attempt 1 without retry or backoff")
    void nonRetryableFailureFailsImmediatelyWithoutRetry() {
        WorkflowRun run = orchestrator.startWorkflow(
                "Modify existing codebase with new endpoints",
                "invalid/path/traversal/attempt/../../escape"
        );

        // Brownfield traversal rejected -> InspectionException (non-retryable)
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(run.getCurrentStage()).isEqualTo(WorkflowStage.CODEBASE_INSPECTION);
        assertThat(sleeper.delays).isEmpty(); // No backoff sleeps scheduled!

        List<String> eventTypes = run.getEvents().stream().map(WorkflowEvent::eventType).toList();
        assertThat(eventTypes).contains("INSPECTION_FAILED");
        assertThat(eventTypes).doesNotContain("STAGE_RETRY_SCHEDULED");
    }

    @Test
    @DisplayName("When retries are disabled, transient failure fails immediately on attempt 1 without retry or backoff")
    void whenRetryDisabledTransientFailureFailsImmediatelyWithoutRetry() {
        retryProperties.setEnabled(false);
        AtomicInteger attemptCounter = new AtomicInteger(0);

        RequirementInterpreterAgent flakyInterpreter = new RequirementInterpreterAgent() {
            @Override
            public RequirementInterpretationResult interpret(String text, RepositoryEvidence evidence) {
                attemptCounter.incrementAndGet();
                throw new TransientWorkflowException("Transient model network hiccup");
            }
        };

        WorkflowOrchestrator customOrchestrator = new WorkflowOrchestrator(
                new ScenarioClassifierAgent(),
                new CodebaseInspector(new CodebaseInspectionProperties()),
                flakyInterpreter,
                new DependencyAwarePlannerAgent(),
                repository
        ).withRetryProperties(retryProperties).withSleeper(sleeper);

        WorkflowRun run = customOrchestrator.startWorkflow("Create a standard URL shortening service");

        assertThat(attemptCounter.get()).isEqualTo(1); // Exactly 1 attempt, no retry!
        assertThat(sleeper.delays).isEmpty(); // No backoff sleeps
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(run.getCurrentStage()).isEqualTo(WorkflowStage.REQUIREMENT_INTERPRETATION);

        List<String> eventTypes = run.getEvents().stream().map(WorkflowEvent::eventType).toList();
        assertThat(eventTypes).contains(
                "STAGE_EXECUTION_ATTEMPT",
                "STAGE_TRANSIENT_FAILURE",
                "STAGE_RETRY_DISABLED"
        );
        assertThat(eventTypes).doesNotContain(
                "STAGE_RETRY_SCHEDULED",
                "STAGE_RETRY_SUCCEEDED",
                "RETRY_EXHAUSTED"
        );

        WorkflowEvent disabledEvent = run.getEvents().stream()
                .filter(e -> "STAGE_RETRY_DISABLED".equals(e.eventType()))
                .findFirst().orElseThrow();
        assertThat(disabledEvent.description()).contains("linkforge.workflow.retry.enabled=false");
    }

    @Test
    @DisplayName("When retries are explicitly enabled, transient failure is retried according to configured limits")
    void whenRetryEnabledTransientFailureRetriedAccordingToLimits() {
        retryProperties.setEnabled(true);
        retryProperties.setMaxAttempts(2);
        AtomicInteger attemptCounter = new AtomicInteger(0);

        RequirementInterpreterAgent retryableInterpreter = new RequirementInterpreterAgent() {
            @Override
            public RequirementInterpretationResult interpret(String text, RepositoryEvidence evidence) {
                if (attemptCounter.incrementAndGet() == 1) {
                    throw new TransientWorkflowException("Transient timeout");
                }
                return super.interpret(text, evidence);
            }
        };

        WorkflowOrchestrator customOrchestrator = new WorkflowOrchestrator(
                new ScenarioClassifierAgent(),
                new CodebaseInspector(new CodebaseInspectionProperties()),
                retryableInterpreter,
                new DependencyAwarePlannerAgent(),
                repository
        ).withRetryProperties(retryProperties).withSleeper(sleeper);

        WorkflowRun run = customOrchestrator.startWorkflow("Create a standard URL shortening service");

        assertThat(attemptCounter.get()).isEqualTo(2);
        assertThat(sleeper.delays).containsExactly(100L);
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_APPROVAL);

        List<String> eventTypes = run.getEvents().stream().map(WorkflowEvent::eventType).toList();
        assertThat(eventTypes).contains(
                "STAGE_EXECUTION_ATTEMPT",
                "STAGE_TRANSIENT_FAILURE",
                "STAGE_RETRY_SCHEDULED",
                "STAGE_RETRY_SUCCEEDED"
        );
        assertThat(eventTypes).doesNotContain("STAGE_RETRY_DISABLED");
    }

    @Test
    @DisplayName("Specialist retry is idempotent: does not repeat completed tasks and preserves prior results")
    void specialistRetryPreservesPriorCompletedTasks() {
        AtomicInteger task1Invocations = new AtomicInteger(0);
        AtomicInteger task2Invocations = new AtomicInteger(0);

        SpecialistAgent customAgent = new SpecialistAgent() {
            @Override
            public SpecialistRole getRole() {
                return SpecialistRole.API_BEHAVIOR;
            }

            @Override
            public String getAgentName() {
                return "idempotent-test-specialist";
            }

            @Override
            public SpecialistTaskResult execute(SpecialistTaskInput input) {
                if ("TASK-1".equals(input.taskId())) {
                    task1Invocations.incrementAndGet();
                    return new SpecialistTaskResult(
                            input.taskId(), getRole(), getAgentName(), "SUCCESS",
                            "Summary for task 1", List.of("Rec 1"), List.of("Test 1"), List.of("AC-1"),
                            Map.of(), false, null, Instant.now(), Instant.now()
                    );
                } else if ("TASK-2".equals(input.taskId())) {
                    int count = task2Invocations.incrementAndGet();
                    if (count == 1) {
                        throw new TransientWorkflowException("Transient timeout on task 2 execution");
                    }
                    return new SpecialistTaskResult(
                            input.taskId(), getRole(), getAgentName(), "SUCCESS",
                            "Summary for task 2", List.of("Rec 2"), List.of("Test 2"), List.of("AC-1"),
                            Map.of(), false, null, Instant.now(), Instant.now()
                    );
                }
                return new SpecialistTaskResult(
                        input.taskId(), getRole(), getAgentName(), "SUCCESS",
                        "Summary for default", List.of(), List.of(), List.of("AC-1"),
                        Map.of(), false, null, Instant.now(), Instant.now()
                );
            }
        };

        SpecialistRegistry registry = new SpecialistRegistry(List.of(customAgent));
        SpecialistCoordinationProperties coordProps = new SpecialistCoordinationProperties();
        TaskGraphCoordinator coordinator = new TaskGraphCoordinator(new TaskGraphValidator(), registry, coordProps);

        WorkflowOrchestrator customOrch = new WorkflowOrchestrator(
                new ScenarioClassifierAgent(),
                new CodebaseInspector(new CodebaseInspectionProperties()),
                new RequirementInterpreterAgent(),
                new DependencyAwarePlannerAgent(),
                repository,
                coordinator
        ).withRetryProperties(retryProperties).withSleeper(sleeper);

        // Start workflow
        WorkflowRun run = customOrch.startWorkflow("Create a standard URL shortening service");
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_APPROVAL);

        // Manually replace planned tasks with TASK-1 (independent) and TASK-2 (depends on TASK-1)
        List<PlannedTask> plannedTasks = List.of(
                new PlannedTask("TASK-1", "Task 1", "Desc 1", List.of(), "PENDING"),
                new PlannedTask("TASK-2", "Task 2", "Desc 2", List.of("TASK-1"), "PENDING")
        );
        run.setTasks(plannedTasks);
        repository.save(run);

        // Approve plan
        Optional<WorkflowRun> approved = customOrch.approvePlan(
                run.getId(),
                "APPROVED",
                run.getCurrentPlanHash(),
                "approver-1",
                "Proceed with tasks"
        );

        assertThat(approved).isPresent();
        WorkflowRun finalRun = approved.get();
        assertThat(finalRun.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);

        // Verification of idempotency:
        // TASK-1 should have been executed EXACTLY ONCE!
        assertThat(task1Invocations.get()).isEqualTo(1);
        // TASK-2 failed once then succeeded on retry 2 -> executed TWICE
        assertThat(task2Invocations.get()).isEqualTo(2);

        // Both specialist invocations are present in final results
        assertThat(finalRun.getSpecialistInvocations()).hasSize(2);
        assertThat(finalRun.getSpecialistInvocations().get(0).taskId()).isEqualTo("TASK-1");
        assertThat(finalRun.getSpecialistInvocations().get(1).taskId()).isEqualTo("TASK-2");
    }

    // ==========================================
    // 2. SAFE STOP / CANCELLATION
    // ==========================================

    @Test
    @DisplayName("Cancellation while awaiting clarification transitions to CANCELLED and records audit event")
    void cancelWhileAwaitingClarification() {
        WorkflowRun run = orchestrator.startWorkflow("make links faster and safer");
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_CLARIFICATION);

        Optional<WorkflowRun> stopped = orchestrator.cancelWorkflow(
                run.getId(),
                "operator-alice",
                "User withdrew ambiguous request"
        );

        assertThat(stopped).isPresent();
        WorkflowRun cancelledRun = stopped.get();
        assertThat(cancelledRun.getStatus()).isEqualTo(WorkflowStatus.CANCELLED);
        assertThat(cancelledRun.isCancelled()).isTrue();
        assertThat(cancelledRun.getCancellation()).isNotNull();
        assertThat(cancelledRun.getCancellation().cancelledBy()).isEqualTo("operator-alice");
        assertThat(cancelledRun.getCancellation().reason()).isEqualTo("User withdrew ambiguous request");

        WorkflowEvent cancelEvent = cancelledRun.getEvents().stream()
                .filter(e -> "WORKFLOW_CANCELLED".equals(e.eventType()))
                .findFirst().orElseThrow();
        assertThat(cancelEvent.description()).contains("operator-alice");
        assertThat(cancelEvent.description()).contains("User withdrew ambiguous request");

        // Subsequent attempt to clarify is rejected
        assertThatThrownBy(() -> orchestrator.submitClarification(run.getId(), "clarification text", null, "operator-alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Cancellation while awaiting approval transitions to CANCELLED and rejects subsequent approval")
    void cancelWhileAwaitingApproval() {
        WorkflowRun run = orchestrator.startWorkflow("Create a standard URL shortening service");
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_APPROVAL);

        Optional<WorkflowRun> stopped = orchestrator.cancelWorkflow(
                run.getId(),
                "admin-bob",
                "Budget reallocated; abort plan"
        );

        assertThat(stopped).isPresent();
        WorkflowRun cancelledRun = stopped.get();
        assertThat(cancelledRun.getStatus()).isEqualTo(WorkflowStatus.CANCELLED);

        // Subsequent attempt to approve plan is rejected
        assertThatThrownBy(() -> orchestrator.approvePlan(
                run.getId(),
                "APPROVED",
                cancelledRun.getCurrentPlanHash(),
                "admin-bob",
                "try to approve anyway"
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Cancellation before work starts in CREATED state stops workflow immediately")
    void cancelBeforeWorkStarts() {
        WorkflowRun run = new WorkflowRun("Create link service");
        repository.save(run);
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.CREATED);

        Optional<WorkflowRun> cancelled = orchestrator.cancelWorkflow(
                run.getId(),
                "initiator",
                "Stopped before intake"
        );

        assertThat(cancelled).isPresent();
        assertThat(cancelled.get().getStatus()).isEqualTo(WorkflowStatus.CANCELLED);
    }

    @Test
    @DisplayName("Cancellation during running coordination stops pending tasks without launching remaining work")
    void cancelDuringRunningWorkStopsPendingTasks() throws InterruptedException {
        CountDownLatch task1StartedLatch = new CountDownLatch(1);
        CountDownLatch cancellationTriggeredLatch = new CountDownLatch(1);
        AtomicBoolean task2WasExecuted = new AtomicBoolean(false);

        SpecialistAgent cancellingAgent = new SpecialistAgent() {
            @Override public SpecialistRole getRole() { return SpecialistRole.API_BEHAVIOR; }
            @Override public String getAgentName() { return "cancellable-specialist"; }
            @Override
            public SpecialistTaskResult execute(SpecialistTaskInput input) {
                if ("TASK-1".equals(input.taskId())) {
                    task1StartedLatch.countDown();
                    try {
                        // Wait until cancellation has been requested by the test
                        cancellationTriggeredLatch.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {}

                    return new SpecialistTaskResult(
                            input.taskId(), getRole(), getAgentName(), "SUCCESS",
                            "Summary 1", List.of(), List.of(), List.of("AC-1"),
                            Map.of(), false, null, Instant.now(), Instant.now()
                    );
                } else if ("TASK-2".equals(input.taskId())) {
                    task2WasExecuted.set(true);
                    return new SpecialistTaskResult(
                            input.taskId(), getRole(), getAgentName(), "SUCCESS",
                            "Summary 2", List.of(), List.of(), List.of("AC-1"),
                            Map.of(), false, null, Instant.now(), Instant.now()
                    );
                }
                return new SpecialistTaskResult(
                        input.taskId(), getRole(), getAgentName(), "SUCCESS",
                        "Default", List.of(), List.of(), List.of("AC-1"),
                        Map.of(), false, null, Instant.now(), Instant.now()
                );
            }
        };

        SpecialistRegistry registry = new SpecialistRegistry(List.of(cancellingAgent));
        SpecialistCoordinationProperties coordProps = new SpecialistCoordinationProperties();
        coordProps.setMaxConcurrency(1); // Execute tasks sequentially in 2 waves
        TaskGraphCoordinator coordinator = new TaskGraphCoordinator(new TaskGraphValidator(), registry, coordProps);

        WorkflowOrchestrator customOrch = new WorkflowOrchestrator(
                new ScenarioClassifierAgent(),
                new CodebaseInspector(new CodebaseInspectionProperties()),
                new RequirementInterpreterAgent(),
                new DependencyAwarePlannerAgent(),
                repository,
                coordinator
        ).withRetryProperties(retryProperties).withSleeper(sleeper);

        WorkflowRun run = customOrch.startWorkflow("Create a standard URL shortening service");
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_APPROVAL);

        List<PlannedTask> plannedTasks = List.of(
                new PlannedTask("TASK-1", "Task 1", "Desc 1", List.of(), "PENDING"),
                new PlannedTask("TASK-2", "Task 2", "Desc 2", List.of("TASK-1"), "PENDING")
        );
        run.setTasks(plannedTasks);
        repository.save(run);

        // Run approval in a background thread
        Thread runnerThread = new Thread(() -> {
            customOrch.approvePlan(run.getId(), "APPROVED", run.getCurrentPlanHash(), "approver", "Go");
        });
        runnerThread.start();

        // Wait for TASK-1 to start executing
        boolean started = task1StartedLatch.await(3, TimeUnit.SECONDS);
        assertThat(started).isTrue();

        // Trigger safe stop / cancellation while TASK-1 is running
        customOrch.cancelWorkflow(run.getId(), "safety-operator", "Emergency shutdown");
        cancellationTriggeredLatch.countDown();

        runnerThread.join(4000);

        WorkflowRun finalRun = repository.findById(run.getId()).orElseThrow();
        assertThat(finalRun.getStatus()).isEqualTo(WorkflowStatus.CANCELLED);
        // TASK-2 must NOT have been executed!
        assertThat(task2WasExecuted.get()).isFalse();
    }

    @Test
    @DisplayName("Repeated stop requests on already cancelled workflow are idempotent")
    void repeatedStopRequestsAreIdempotent() {
        WorkflowRun run = orchestrator.startWorkflow("make links faster and safer");
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_CLARIFICATION);

        Optional<WorkflowRun> firstCancel = orchestrator.cancelWorkflow(run.getId(), "op-1", "Stop reason");
        assertThat(firstCancel).isPresent();
        assertThat(firstCancel.get().getStatus()).isEqualTo(WorkflowStatus.CANCELLED);

        // Second stop request
        Optional<WorkflowRun> secondCancel = orchestrator.cancelWorkflow(run.getId(), "op-2", "Another reason");
        assertThat(secondCancel).isPresent();
        assertThat(secondCancel.get().getStatus()).isEqualTo(WorkflowStatus.CANCELLED);
        // Preserves original cancellation
        assertThat(secondCancel.get().getCancellation().cancelledBy()).isEqualTo("op-1");

        // Verify only 1 WORKFLOW_CANCELLED event exists
        long cancelEventCount = secondCancel.get().getEvents().stream()
                .filter(e -> "WORKFLOW_CANCELLED".equals(e.eventType()))
                .count();
        assertThat(cancelEventCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Stop requests on completed, failed, or rejected workflows are rejected with IllegalStateException")
    void stopRequestsOnTerminalWorkflowsAreRejected() {
        // 1. Rejected workflow
        WorkflowRun run = orchestrator.startWorkflow("Create a standard URL shortening service");
        orchestrator.approvePlan(run.getId(), "REJECTED", run.getCurrentPlanHash(), "approver", "Not acceptable");
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.REJECTED);

        assertThatThrownBy(() -> orchestrator.cancelWorkflow(run.getId(), "op", "Cancel rejected"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in terminal state REJECTED");

        // 2. Completed workflow
        WorkflowRun completedRun = orchestrator.startWorkflow("Create a standard URL shortening service");
        orchestrator.approvePlan(completedRun.getId(), "APPROVED", completedRun.getCurrentPlanHash(), "approver", "Approved");
        assertThat(completedRun.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);

        assertThatThrownBy(() -> orchestrator.cancelWorkflow(completedRun.getId(), "op", "Cancel completed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in terminal state COMPLETED");

        // 3. Failed workflow
        WorkflowRun failedRun = orchestrator.startWorkflow("Modify existing codebase with new endpoints", "invalid/../escape");
        assertThat(failedRun.getStatus()).isEqualTo(WorkflowStatus.FAILED);

        assertThatThrownBy(() -> orchestrator.cancelWorkflow(failedRun.getId(), "op", "Cancel failed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in terminal state FAILED");
    }

    // Recording Sleeper test double
    private static class RecordingSleeper implements Sleeper {
        final List<Long> delays = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            delays.add(millis);
        }
    }
}
