package com.linkforge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.agent.RequirementInterpreterAgent;
import com.linkforge.ai.config.LlmProperties;
import com.linkforge.domain.workflow.WorkflowStatus;
import com.linkforge.service.WorkflowRepository;
import com.linkforge.service.inspection.CodebaseInspectionProperties;
import com.linkforge.service.retry.TransientWorkflowException;
import com.linkforge.service.retry.WorkflowRetryProperties;
import com.linkforge.service.security.WorkflowSecurityProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Milestone 9: Repeatable end-to-end scenario runs demonstrating LinkForge's
 * real agentic engineering workbench across all lifecycle phases through public APIs.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RepeatableWorkflowScenariosIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private CodebaseInspectionProperties inspectionProperties;

    @Autowired
    private WorkflowSecurityProperties securityProperties;

    @Autowired
    private WorkflowRetryProperties retryProperties;

    @Autowired
    private LlmProperties llmProperties;

    @SpyBean
    private RequirementInterpreterAgent requirementInterpreterAgent;

    @TempDir
    Path tempDir;

    private Path testApprovedRoot;

    @BeforeEach
    void setUp() throws IOException {
        testApprovedRoot = tempDir.resolve("approved-root");
        Files.createDirectories(testApprovedRoot);
        inspectionProperties.setApprovedRoot(testApprovedRoot.toString());

        securityProperties.setEnabled(false);
        securityProperties.setApprovalToken("dev-approval-token");
        securityProperties.setClarificationToken("dev-clarification-token");
        securityProperties.setCancellationToken("dev-cancellation-token");
        securityProperties.setOperatorToken("dev-operator-token");

        retryProperties.setInitialBackoffMs(20);
        Mockito.reset(requirementInterpreterAgent);

        llmProperties.setEnabled(false);
        workflowRepository.clear();
    }

    @AfterEach
    void tearDown() {
        securityProperties.setEnabled(false);
        llmProperties.setEnabled(false);
        retryProperties.setInitialBackoffMs(500);
        Mockito.reset(requirementInterpreterAgent);
        workflowRepository.clear();
    }

    // =========================================================================
    // Scenario 1: Clear greenfield URL-shortener requirement
    // =========================================================================
    @Test
    @DisplayName("Scenario 1: Greenfield URL shortener: interpretation, scenario classification, and specialist planning")
    void scenario1_clearGreenfieldUrlShortenerRequirement() throws Exception {
        String payload = """
                {
                  "requirement": "Build a greenfield URL shortener service with Base62 token encoding and click tracking"
                }
                """;

        // Step 1: Submit requirement to create workflow
        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.scenario").value("GREENFIELD"))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.currentStage").value("PLAN_APPROVAL"))
                .andExpect(jsonPath("$.planHash").isNotEmpty())
                .andExpect(jsonPath("$.acceptanceCriteria", not(empty())))
                .andExpect(jsonPath("$.unansweredQuestions", empty()))
                .andExpect(jsonPath("$.tasks", hasSize(5)))
                .andExpect(jsonPath("$.tasks[0].status").value("PENDING"))
                .andExpect(jsonPath("$.tasks[0].specialistRole").value("DATA_PERSISTENCE"))
                .andExpect(jsonPath("$.agentDecisions", hasSize(3)))
                .andExpect(jsonPath("$.agentDecisions[0].agentName").value("scenario-classifier"))
                .andExpect(jsonPath("$.agentDecisions[0].decision").value("GREENFIELD"))
                .andReturn();

        JsonNode createdJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String workflowId = createdJson.get("id").asText();
        String planHash = createdJson.get("planHash").asText();

        // Step 2: Approve the generated plan
        String approvePayload = String.format("""
                {
                  "decision": "APPROVED",
                  "planHash": "%s",
                  "approver": "lead-architect@example.com",
                  "comments": "Plan approved for autonomous specialist coordination"
                }
                """, planHash);

        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentStage").value("FINISHED"))
                .andExpect(jsonPath("$.approval.approver").value("lead-architect@example.com"))
                .andExpect(jsonPath("$.tasks[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.specialistInvocations", hasSize(5)));

        // Step 3: Verify operator audit history endpoint
        mockMvc.perform(get("/api/v1/workflows/" + workflowId + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value(workflowId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.events", not(empty())))
                .andExpect(jsonPath("$.events[*].eventType", hasItem("REQUIREMENT_ACCEPTED")))
                .andExpect(jsonPath("$.events[*].eventType", hasItem("AWAITING_PLAN_APPROVAL")))
                .andExpect(jsonPath("$.events[*].eventType", hasItem("PLAN_APPROVED")))
                .andExpect(jsonPath("$.events[*].eventType", hasItem("WORKFLOW_COMPLETED")));

        // Step 4: Verify operator summary endpoint
        mockMvc.perform(get("/api/v1/workflows/" + workflowId + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value(workflowId))
                .andExpect(jsonPath("$.requirementRevision").value(1))
                .andExpect(jsonPath("$.taskCount").value(5))
                .andExpect(jsonPath("$.completedTaskCount").value(5))
                .andExpect(jsonPath("$.planApproved").value(true))
                .andExpect(jsonPath("$.completeness.allPlannedTasksExecuted").value(true));

        // Step 5: Verify operator evidence & traceability endpoint
        mockMvc.perform(get("/api/v1/workflows/" + workflowId + "/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value(workflowId))
                .andExpect(jsonPath("$.codebaseEvidenceAvailable").value(false))
                .andExpect(jsonPath("$.repositoryPath").doesNotExist())
                .andExpect(jsonPath("$.criteriaEvidence", not(empty())))
                .andExpect(jsonPath("$.criteriaEvidence[0].status").value("ANALYZED"))
                .andExpect(jsonPath("$.verificationStatus.requirementAnalysis").value("COMPLETED"))
                .andExpect(jsonPath("$.verificationStatus.specialistAnalysis").value("COMPLETED"))
                .andExpect(jsonPath("$.verificationStatus.sourceCodeGeneration").value("NOT_SUPPORTED"))
                .andExpect(jsonPath("$.verificationStatus.buildExecution").value("NOT_SUPPORTED"))
                .andExpect(jsonPath("$.verificationStatus.automatedTestExecution").value("UNVERIFIED"))
                .andExpect(jsonPath("$.verificationStatus.deploymentAndRelease").value("NOT_SUPPORTED"));
    }

    // =========================================================================
    // Scenario 2: Ambiguous requirement & operator clarification gate
    // =========================================================================
    @Test
    @DisplayName("Scenario 2: Ambiguous requirement: workflow pauses for clarification, updates revision, and proceeds")
    void scenario2_ambiguousRequirementClarificationGate() throws Exception {
        String ambiguousPayload = """
                {
                  "requirement": "make links faster and safer"
                }
                """;

        // Step 1: Submit ambiguous requirement
        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ambiguousPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.scenario").value("AMBIGUOUS"))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_CLARIFICATION"))
                .andExpect(jsonPath("$.currentStage").value("SCENARIO_CLASSIFICATION"))
                .andExpect(jsonPath("$.unansweredQuestions", hasSize(greaterThan(1))))
                .andExpect(jsonPath("$.acceptanceCriteria", empty()))
                .andExpect(jsonPath("$.tasks", empty()))
                .andReturn();

        String workflowId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        // Step 2: Verify plan approval is blocked while clarification is pending
        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"APPROVED\", \"planHash\": \"none\"}"))
                .andExpect(status().isConflict());

        // Step 3: Operator submits clarification
        String clarificationPayload = """
                {
                  "clarification": "Build a greenfield URL shortener with Base62 tokens and atomic access counter",
                  "submittedBy": "operator@example.com"
                }
                """;

        MvcResult clarifyResult = mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/clarifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clarificationPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario").value("GREENFIELD"))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.currentStage").value("PLAN_APPROVAL"))
                .andExpect(jsonPath("$.clarifications", hasSize(1)))
                .andExpect(jsonPath("$.clarifications[0].submittedBy").value("operator@example.com"))
                .andExpect(jsonPath("$.acceptanceCriteria", not(empty())))
                .andExpect(jsonPath("$.tasks", hasSize(5)))
                .andExpect(jsonPath("$.planHash").isNotEmpty())
                .andReturn();

        JsonNode clarifyJson = objectMapper.readTree(clarifyResult.getResponse().getContentAsString());
        String planHash = clarifyJson.get("planHash").asText();

        // Step 4: Verify duplicate clarification submission is idempotent
        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/clarifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clarificationPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clarifications", hasSize(1)));

        // Step 5: Verify revision count incremented in summary
        mockMvc.perform(get("/api/v1/workflows/" + workflowId + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirementRevision").value(2))
                .andExpect(jsonPath("$.clarificationCount").value(1));

        // Step 6: Approve plan to reach completion
        String approvePayload = String.format("""
                {
                  "decision": "APPROVED",
                  "planHash": "%s",
                  "approver": "architect@example.com"
                }
                """, planHash);

        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    // =========================================================================
    // Scenario 3: Brownfield request with safe repository inspection
    // =========================================================================
    @Test
    @DisplayName("Scenario 3: Brownfield request: safe repository inspection and planning based on disposable fixture")
    void scenario3_brownfieldRequestWithSafeCodebaseInspection() throws Exception {
        // Step 1: Create disposable fixture repository inside approved root
        Path fixtureRepo = testApprovedRoot.resolve("brownfield-sample-service");
        Files.createDirectories(fixtureRepo.resolve("src/main/java/com/example"));
        Files.writeString(fixtureRepo.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId><artifactId>service</artifactId><version>1.0.0</version></project>");
        Files.writeString(fixtureRepo.resolve("src/main/java/com/example/LinkService.java"),
                "package com.example; public class LinkService { public String shorten(String url) { return url; } }");

        String brownfieldPayload = """
                {
                  "requirement": "Extend existing link service with click metrics tracking table",
                  "repositoryPath": "brownfield-sample-service"
                }
                """;

        // Step 2: Submit brownfield workflow request
        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(brownfieldPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.scenario").value("BROWNFIELD"))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.currentStage").value("PLAN_APPROVAL"))
                .andExpect(jsonPath("$.repositoryEvidence.totalFiles", greaterThan(0)))
                .andExpect(jsonPath("$.repositoryEvidence.detectedLanguages", hasItem("Java")))
                .andExpect(jsonPath("$.repositoryEvidence.detectedFrameworks", hasItem("Maven")))
                .andReturn();

        JsonNode json = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String workflowId = json.get("id").asText();
        String planHash = json.get("planHash").asText();

        // Step 3: Verify operator evidence endpoint
        mockMvc.perform(get("/api/v1/workflows/" + workflowId + "/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value(workflowId))
                .andExpect(jsonPath("$.scenario").value("BROWNFIELD"))
                .andExpect(jsonPath("$.codebaseEvidenceAvailable").value(true))
                .andExpect(jsonPath("$.repositoryPath").doesNotExist())
                .andExpect(jsonPath("$.verificationStatus.codebaseInspection").value("COMPLETED"));

        // Step 4: Approve plan and finish coordination
        String approvePayload = String.format("""
                {
                  "decision": "APPROVED",
                  "planHash": "%s",
                  "approver": "senior-engineer"
                }
                """, planHash);

        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    // =========================================================================
    // Scenario 4: Human approval governance gate
    // =========================================================================
    @Test
    @DisplayName("Scenario 4: Approval gate: verify protected actions remain blocked until valid approval is supplied")
    void scenario4_approvalGateBlocksExecutionUntilValidDecision() throws Exception {
        String payload = """
                {
                  "requirement": "Build a greenfield URL shortener service"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andReturn();

        JsonNode json = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String workflowId = json.get("id").asText();
        String realPlanHash = json.get("planHash").asText();

        // Verification 1: Tasks are blocked in PENDING and specialist invocations are not executed
        mockMvc.perform(get("/api/v1/workflows/" + workflowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.tasks[0].status").value("PENDING"))
                .andExpect(jsonPath("$.specialistInvocations", empty()));

        // Verification 2: Approval fails if plan hash does not match (tamper protection)
        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"APPROVED\", \"planHash\": \"tampered-hash-value-1234\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PLAN_HASH"));

        // Verification 3: Approval fails if token is missing when security is enabled
        securityProperties.setEnabled(true);
        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", realPlanHash)))
                .andExpect(status().isUnauthorized());

        // Verification 4: Plan rejection properly transitions to terminal REJECTED state
        String rejectPayload = String.format("""
                {
                  "decision": "REJECTED",
                  "planHash": "%s",
                  "approver": "sec-governance@example.com",
                  "comments": "Rejected: Missing rate-limiting requirements"
                }
                """, realPlanHash);

        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/approve")
                        .header("X-Auth-Token", "dev-approval-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rejectPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.currentStage").value("PLAN_APPROVAL"))
                .andExpect(jsonPath("$.approval.decision").value("REJECTED"));

        // Verification 5: Rejected workflow blocks subsequent approval or clarification attempts
        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/approve")
                        .header("X-Auth-Token", "dev-approval-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", realPlanHash)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/clarifications")
                        .header("X-Auth-Token", "dev-clarification-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clarification\": \"Updated requirement\"}"))
                .andExpect(status().isConflict());
    }

    // =========================================================================
    // Scenario 5: Transient failure recovery and safe stop (cancellation)
    // =========================================================================
    @Test
    @DisplayName("Scenario 5a: Transient workflow-stage failure recovery with bounded exponential retry")
    void scenario5_transientFailureRecoveryWithBoundedRetry() throws Exception {
        // Setup controlled test seam: inject transient failure on 1st invocation of requirement interpreter, succeed on 2nd
        AtomicInteger interpreterCalls = new AtomicInteger(0);
        doAnswer(invocation -> {
            if (interpreterCalls.incrementAndGet() == 1) {
                throw new TransientWorkflowException("Temporary downstream model timeout (transient)");
            }
            return invocation.callRealMethod();
        }).when(requirementInterpreterAgent).interpret(any(), any());

        String payload = """
                {
                  "requirement": "Build a greenfield URL shortener service with resilient routing"
                }
                """;

        // Step 1: Start workflow through public API
        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andReturn();

        String responseBody = createResult.getResponse().getContentAsString();
        String workflowId = objectMapper.readTree(responseBody).get("id").asText();
        String planHash = objectMapper.readTree(responseBody).get("planHash").asText();

        // Verify the controlled mock was invoked twice (initial transient failure + retry attempt)
        assertThat(interpreterCalls.get()).isEqualTo(2);

        // Step 2: Verify persisted audit history contains the transient failure, backoff schedule, and successful retry recovery
        MvcResult historyResult = mockMvc.perform(get("/api/v1/workflows/" + workflowId + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[*].eventType", hasItem("STAGE_EXECUTION_ATTEMPT")))
                .andExpect(jsonPath("$.events[*].eventType", hasItem("STAGE_TRANSIENT_FAILURE")))
                .andExpect(jsonPath("$.events[*].eventType", hasItem("STAGE_RETRY_SCHEDULED")))
                .andExpect(jsonPath("$.events[*].eventType", hasItem("STAGE_RETRY_SUCCEEDED")))
                .andReturn();

        JsonNode historyEvents = objectMapper.readTree(historyResult.getResponse().getContentAsString()).get("events");
        boolean hasTransientFailureEvent = false;
        boolean hasRetryScheduledEvent = false;
        boolean hasRetrySucceededEvent = false;

        for (JsonNode ev : historyEvents) {
            String type = ev.get("eventType").asText();
            String desc = ev.get("description").asText();
            if ("STAGE_TRANSIENT_FAILURE".equals(type) && desc.contains("Temporary downstream model timeout")) {
                hasTransientFailureEvent = true;
            }
            if ("STAGE_RETRY_SCHEDULED".equals(type) && desc.contains("Retry attempt 2 scheduled for stage REQUIREMENT_INTERPRETATION")) {
                hasRetryScheduledEvent = true;
            }
            if ("STAGE_RETRY_SUCCEEDED".equals(type) && desc.contains("Stage REQUIREMENT_INTERPRETATION succeeded on retry attempt 2")) {
                hasRetrySucceededEvent = true;
            }
        }

        assertThat(hasTransientFailureEvent).as("Expected STAGE_TRANSIENT_FAILURE event with transient failure details").isTrue();
        assertThat(hasRetryScheduledEvent).as("Expected STAGE_RETRY_SCHEDULED event for attempt 2").isTrue();
        assertThat(hasRetrySucceededEvent).as("Expected STAGE_RETRY_SUCCEEDED event for attempt 2").isTrue();

        // Step 3: Proceed with valid human approval to verify workflow reaches final completion
        String approvePayload = String.format("""
                {
                  "decision": "APPROVED",
                  "planHash": "%s",
                  "approver": "lead-architect@example.com",
                  "comments": "Plan approved following automatic retry recovery"
                }
                """, planHash);

        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/approve")
                        .header("X-Auth-Token", "dev-approval-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentStage").value("FINISHED"));

        // Step 4: Verify final observable outcome via summary and evidence endpoints
        mockMvc.perform(get("/api/v1/workflows/" + workflowId + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedTaskCount").value(5))
                .andExpect(jsonPath("$.taskCount").value(5));

        mockMvc.perform(get("/api/v1/workflows/" + workflowId + "/evidence")
                        .header("X-Operator-Token", "dev-operator-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value(workflowId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentStage").value("FINISHED"))
                .andExpect(jsonPath("$.planApproved").value(true))
                .andExpect(jsonPath("$.taskTraceability", hasSize(5)));
    }

    @Test
    @DisplayName("Scenario 5b: Safe stop cancellation without dispatching further tasks")
    void scenario5_transientFailureRecoveryAndSafeStop() throws Exception {
        String payload = """
                {
                  "requirement": "Build a greenfield URL shortener service with Base62 tokens"
                }
                """;

        // Step 1: Start workflow
        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andReturn();

        String workflowId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        // Step 2: Safe stop / cancellation request
        String cancelPayload = """
                {
                  "reason": "Cancelled by operator due to updated platform roadmap",
                  "requestedBy": "operations-lead@example.com"
                }
                """;

        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(workflowId))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellation.cancelledBy").value("operations-lead@example.com"))
                .andExpect(jsonPath("$.cancellation.reason").value("Cancelled by operator due to updated platform roadmap"))
                .andExpect(jsonPath("$.cancellation.cancelledAt").isNotEmpty());

        // Step 3: Verify audit event WORKFLOW_CANCELLED recorded
        mockMvc.perform(get("/api/v1/workflows/" + workflowId + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[*].eventType", hasItem("WORKFLOW_CANCELLED")));

        // Step 4: Verify cancellation permanently blocks subsequent execution
        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"APPROVED\", \"planHash\": \"hash\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_WORKFLOW_STATE"));

        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/clarifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clarification\": \"Try resuming\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_WORKFLOW_STATE"));

        // Step 5: Verify cancellation is idempotent
        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // =========================================================================
    // Scenario 6: Optional Live Ollama Check
    // =========================================================================
    @Test
    @DisplayName("Scenario 6 (Optional): Live Ollama execution check when local Ollama model service is available")
    void scenario6_optionalLiveOllamaExecutionCheck() throws Exception {
        boolean ollamaRunning = isOllamaAvailable();
        Assumptions.assumeTrue(ollamaRunning, "Ollama is not running locally with llama3.2; skipping live model check.");

        try {
            llmProperties.setEnabled(true);
            llmProperties.setProvider("ollama");

            String payload = """
                    {
                      "requirement": "Build a URL shortener with Base62 tokens and click analytics"
                    }
                    """;

            MvcResult result = mockMvc.perform(post("/api/v1/workflows")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                    .andReturn();

            JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
            String id = json.get("id").asText();
            String planHash = json.get("planHash").asText();

            // Verify live model or fallback agent decision was recorded
            mockMvc.perform(get("/api/v1/workflows/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.agentDecisions", not(empty())));

            // Approve plan
            mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", planHash)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"));
        } finally {
            llmProperties.setEnabled(false);
        }
    }

    private boolean isOllamaAvailable() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(1))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:11434/api/tags"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("llama3.2");
        } catch (Exception e) {
            return false;
        }
    }
}
