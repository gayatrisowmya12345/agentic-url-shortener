package com.linkforge.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.domain.workflow.WorkflowApproval;
import com.linkforge.domain.workflow.WorkflowClarification;
import com.linkforge.service.WorkflowRepository;
import com.linkforge.service.security.WorkflowSecurityProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WorkflowClarificationAndApprovalIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowSecurityProperties securityProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        securityProperties.setEnabled(false);
        securityProperties.setClarificationToken("test-clarification-token");
        securityProperties.setApprovalToken("test-approval-token");
        workflowRepository.clear();
    }

    @AfterEach
    void tearDown() {
        securityProperties.setEnabled(false);
        workflowRepository.clear();
    }

    @Test
    @DisplayName("Ambiguous requirement pauses in WAITING_FOR_CLARIFICATION and exposes pending questions")
    void ambiguousRequirementPausesAndExposesQuestions() throws Exception {
        String payload = """
                {
                  "requirement": "make links faster and safer"
                }
                """;

        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_CLARIFICATION"))
                .andExpect(jsonPath("$.currentStage").value("SCENARIO_CLASSIFICATION"))
                .andExpect(jsonPath("$.unansweredQuestions").isNotEmpty())
                .andExpect(jsonPath("$.tasks").isEmpty())
                .andExpect(jsonPath("$.planHash").isEmpty());
    }

    @Test
    @DisplayName("Blank clarification submission is rejected with HTTP 400 Bad Request")
    void blankClarificationSubmissionRejected() throws Exception {
        String initPayload = "{\"requirement\": \"make links faster and safer\"}";
        MvcResult initResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initPayload))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(initResult.getResponse().getContentAsString()).get("id").asText();

        // 1. Blank string
        String blankPayload = "{\"clarification\": \"   \"}";
        mockMvc.perform(post("/api/v1/workflows/" + id + "/clarifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blankPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        // 2. Empty string
        String emptyPayload = "{\"clarification\": \"\"}";
        mockMvc.perform(post("/api/v1/workflows/" + id + "/clarify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("Tamper-proofing: Caller-supplied state and output fields in clarification are rejected")
    void callerSuppliedFieldsInClarificationRejected() throws Exception {
        String initPayload = "{\"requirement\": \"make links faster and safer\"}";
        MvcResult initResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initPayload))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(initResult.getResponse().getContentAsString()).get("id").asText();

        // Attempt to smuggle caller-controlled status, tasks, or invocations
        String tamperedPayload = """
                {
                  "clarification": "Build a greenfield URL shortener",
                  "status": "COMPLETED",
                  "tasks": [{"taskId": "INJECTED-TASK"}],
                  "specialistInvocations": []
                }
                """;

        mockMvc.perform(post("/api/v1/workflows/" + id + "/clarifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tamperedPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNRECOGNIZED_PROPERTY"));
    }

    @Test
    @DisplayName("Tamper-proofing: Caller-supplied fields in plan approval are rejected")
    void callerSuppliedFieldsInApprovalRejected() throws Exception {
        String initPayload = "{\"requirement\": \"Build a greenfield URL shortener REST service\"}";
        MvcResult initResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initPayload))
                .andExpect(status().isCreated())
                .andReturn();

        String responseJson = initResult.getResponse().getContentAsString();
        String id = objectMapper.readTree(responseJson).get("id").asText();
        String planHash = objectMapper.readTree(responseJson).get("planHash").asText();

        String tamperedPayload = String.format("""
                {
                  "decision": "APPROVED",
                  "planHash": "%s",
                  "status": "COMPLETED",
                  "validationData": "arbitrary"
                }
                """, planHash);

        mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tamperedPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNRECOGNIZED_PROPERTY"));
    }

    @Test
    @DisplayName("Valid clarification re-analyzes requirement, preserves history, and pauses for human approval")
    void validClarificationReanalyzesAndPausesForApproval() throws Exception {
        String initPayload = "{\"requirement\": \"make links faster and safer\"}";
        MvcResult initResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initPayload))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(initResult.getResponse().getContentAsString()).get("id").asText();

        String clarifyPayload = """
                {
                  "clarification": "Build a new greenfield REST service for URL shortening with HTTP 302 redirects",
                  "submittedBy": "lead-operator"
                }
                """;

        mockMvc.perform(post("/api/v1/workflows/" + id + "/clarifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clarifyPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.originalRequirement").value("make links faster and safer"))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.currentStage").value("PLAN_APPROVAL"))
                .andExpect(jsonPath("$.planHash").isNotEmpty())
                .andExpect(jsonPath("$.tasks", hasSize(5)))
                .andExpect(jsonPath("$.clarifications", hasSize(1)))
                .andExpect(jsonPath("$.clarifications[0].submittedBy").value("lead-operator"))
                .andExpect(jsonPath("$.clarifications[0].clarificationText").isNotEmpty())
                .andExpect(jsonPath("$.specialistInvocations").isEmpty());
    }

    @Test
    @DisplayName("Approval API with valid plan hash unblocks specialist coordination to completion")
    void validApprovalUnblocksSpecialistCoordination() throws Exception {
        String initPayload = "{\"requirement\": \"Build a REST API to shorten URLs and redirect requests\"}";
        MvcResult initResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andReturn();

        String responseJson = initResult.getResponse().getContentAsString();
        String id = objectMapper.readTree(responseJson).get("id").asText();
        String planHash = objectMapper.readTree(responseJson).get("planHash").asText();

        String approvePayload = String.format("""
                {
                  "decision": "APPROVED",
                  "planHash": "%s",
                  "approver": "principal-architect",
                  "comments": "Architecture reviewed and confirmed valid"
                }
                """, planHash);

        mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentStage").value("FINISHED"))
                .andExpect(jsonPath("$.approval.decision").value("APPROVED"))
                .andExpect(jsonPath("$.approval.approver").value("principal-architect"))
                .andExpect(jsonPath("$.approval.planHash").value(planHash))
                .andExpect(jsonPath("$.approval.comments").value("Architecture reviewed and confirmed valid"))
                .andExpect(jsonPath("$.specialistInvocations", hasSize(5)));

        // Verify repeat/duplicate approval is idempotent
        mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("Approval API with REJECTED decision marks workflow REJECTED without coordination")
    void rejectionMarksWorkflowRejectedWithoutCoordination() throws Exception {
        String initPayload = "{\"requirement\": \"Build a REST API to shorten URLs and redirect requests\"}";
        MvcResult initResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initPayload))
                .andExpect(status().isCreated())
                .andReturn();

        String responseJson = initResult.getResponse().getContentAsString();
        String id = objectMapper.readTree(responseJson).get("id").asText();
        String planHash = objectMapper.readTree(responseJson).get("planHash").asText();

        String rejectPayload = String.format("""
                {
                  "decision": "REJECTED",
                  "planHash": "%s",
                  "approver": "sec-ops-officer",
                  "comments": "Plan does not satisfy security posture"
                }
                """, planHash);

        mockMvc.perform(post("/api/v1/workflows/" + id + "/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rejectPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.currentStage").value("PLAN_APPROVAL"))
                .andExpect(jsonPath("$.approval.decision").value("REJECTED"))
                .andExpect(jsonPath("$.approval.approver").value("sec-ops-officer"))
                .andExpect(jsonPath("$.specialistInvocations").isEmpty());

        // Verify GET returns rejected state
        mockMvc.perform(get("/api/v1/workflows/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    @DisplayName("Approval API rejects mismatched or stale plan hash with HTTP 400")
    void mismatchedOrStalePlanHashIsRejected() throws Exception {
        String initPayload = "{\"requirement\": \"Build a REST API to shorten URLs and redirect requests\"}";
        MvcResult initResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initPayload))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(initResult.getResponse().getContentAsString()).get("id").asText();

        String badHashPayload = """
                {
                  "decision": "APPROVED",
                  "planHash": "invalid-wrong-hash-9999"
                }
                """;

        mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badHashPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PLAN_HASH"));
    }

    @Test
    @DisplayName("Configurable security enforces authorization token when enabled")
    void configurableSecurityEnforcesAuthorizationTokens() throws Exception {
        securityProperties.setEnabled(true);
        securityProperties.setClarificationToken("secret-clarify-token");
        securityProperties.setApprovalToken("secret-approve-token");

        // 1. Create ambiguous workflow
        MvcResult initResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\": \"make links faster and safer\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(initResult.getResponse().getContentAsString()).get("id").asText();

        // 2. Submit clarification without token -> 401
        String clarifyPayload = "{\"clarification\": \"Build greenfield URL shortener\"}";
        mockMvc.perform(post("/api/v1/workflows/" + id + "/clarifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clarifyPayload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        // 3. Submit clarification with wrong token -> 401
        mockMvc.perform(post("/api/v1/workflows/" + id + "/clarifications")
                        .header("X-Auth-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clarifyPayload))
                .andExpect(status().isUnauthorized());

        // 4. Submit clarification with valid token -> 200
        MvcResult clarifyResult = mockMvc.perform(post("/api/v1/workflows/" + id + "/clarifications")
                        .header("X-Auth-Token", "secret-clarify-token")
                        .header("X-Actor-Id", "authorized-operator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clarifyPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andReturn();

        String planHash = objectMapper.readTree(clarifyResult.getResponse().getContentAsString()).get("planHash").asText();

        // 5. Submit approval without token -> 401
        String approvePayload = String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", planHash);
        mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isUnauthorized());

        // 6. Submit approval with valid Bearer token -> 200
        mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .header("Authorization", "Bearer secret-approve-token")
                        .header("X-Actor-Id", "security-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.approval.approver").value("security-admin"));
    }

    @Test
    @DisplayName("Clarifications and approvals are persisted in H2 database tables")
    void clarificationsAndApprovalsPersistedInH2() throws Exception {
        // 1. Create ambiguous workflow
        MvcResult initResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\": \"make links faster and safer\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(initResult.getResponse().getContentAsString()).get("id").asText();

        // 2. Submit clarification
        mockMvc.perform(post("/api/v1/workflows/" + id + "/clarifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clarification\": \"Build a greenfield REST URL shortener\", \"submittedBy\": \"db-test-user\"}"))
                .andExpect(status().isOk());

        // Verify clarification row in H2 table workflow_clarifications
        List<WorkflowClarification> clarificationsFromDb = workflowRepository.findClarificationsByWorkflowId(id);
        assertThat(clarificationsFromDb).hasSize(1);
        assertThat(clarificationsFromDb.get(0).submittedBy()).isEqualTo("db-test-user");
        assertThat(clarificationsFromDb.get(0).clarificationText()).contains("Build a greenfield REST URL shortener");

        Integer clarifyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_clarifications WHERE workflow_id = ?",
                Integer.class,
                id
        );
        assertThat(clarifyCount).isEqualTo(1);

        // 3. Approve plan
        String planHash = workflowRepository.findById(id).orElseThrow().getCurrentPlanHash();
        mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\", \"approver\": \"lead-dba\"}", planHash)))
                .andExpect(status().isOk());

        // Verify approval row in H2 table workflow_approvals
        Optional<WorkflowApproval> approvalFromDb = workflowRepository.findApprovalByWorkflowId(id);
        assertThat(approvalFromDb).isPresent();
        assertThat(approvalFromDb.get().isApproved()).isTrue();
        assertThat(approvalFromDb.get().approver()).isEqualTo("lead-dba");
        assertThat(approvalFromDb.get().planHash()).isEqualTo(planHash);

        Integer approvalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_approvals WHERE workflow_id = ?",
                Integer.class,
                id
        );
        assertThat(approvalCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Duplicate clarification while waiting for clarification is treated as idempotent retry")
    void duplicateClarificationWhileWaitingIsIdempotent() throws Exception {
        String initPayload = "{\"requirement\": \"make links faster and safer\"}";
        MvcResult initResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_CLARIFICATION"))
                .andReturn();

        String id = objectMapper.readTree(initResult.getResponse().getContentAsString()).get("id").asText();

        // 1. Submit first unresolved clarification
        String vagueClarification = "{\"clarification\": \"please make links faster and optimize\", \"submittedBy\": \"op-1\"}";
        mockMvc.perform(post("/api/v1/workflows/" + id + "/clarifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vagueClarification))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_CLARIFICATION"))
                .andExpect(jsonPath("$.clarifications", hasSize(1)));

        // 2. Resubmit exact same clarification
        mockMvc.perform(post("/api/v1/workflows/" + id + "/clarifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vagueClarification))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_CLARIFICATION"))
                .andExpect(jsonPath("$.clarifications", hasSize(1)));

        // 3. Submit different clarification
        String differentClarification = "{\"clarification\": \"Build a greenfield REST service with token generation\", \"submittedBy\": \"op-2\"}";
        mockMvc.perform(post("/api/v1/workflows/" + id + "/clarifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(differentClarification))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.clarifications", hasSize(2)));
    }
}
