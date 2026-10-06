package com.linkforge.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.domain.workflow.WorkflowCancellation;
import com.linkforge.service.WorkflowRepository;
import com.linkforge.service.retry.WorkflowRetryProperties;
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
class WorkflowRetryAndCancellationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowSecurityProperties securityProperties;

    @Autowired
    private WorkflowRetryProperties retryProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        securityProperties.setEnabled(false);
        securityProperties.setCancellationToken("test-cancellation-token");
        workflowRepository.clear();
    }

    @AfterEach
    void tearDown() {
        securityProperties.setEnabled(false);
        workflowRepository.clear();
    }

    @Test
    @DisplayName("Cancel workflow while awaiting clarification returns 200 OK and records cancellation")
    void cancelWorkflowWhileAwaitingClarification() throws Exception {
        String payload = """
                {
                  "requirement": "make links faster and safer"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_CLARIFICATION"))
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        String cancelPayload = """
                {
                  "reason": "Customer cancelled request",
                  "requestedBy": "operator-alice"
                }
                """;

        mockMvc.perform(post("/api/v1/workflows/" + id + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellation").isMap())
                .andExpect(jsonPath("$.cancellation.cancelledBy").value("operator-alice"))
                .andExpect(jsonPath("$.cancellation.reason").value("Customer cancelled request"))
                .andExpect(jsonPath("$.cancellation.cancelledAt").isNotEmpty());

        // Verify H2 persistence
        Optional<WorkflowCancellation> persisted = workflowRepository.findCancellationByWorkflowId(id);
        assertThat(persisted).isPresent();
        assertThat(persisted.get().cancelledBy()).isEqualTo("operator-alice");
        assertThat(persisted.get().reason()).isEqualTo("Customer cancelled request");

        Integer dbCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_cancellations WHERE workflow_id = ?",
                Integer.class,
                id
        );
        assertThat(dbCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Stop workflow endpoint (/stop alias) while awaiting approval transitions to CANCELLED")
    void stopWorkflowWhileAwaitingApproval() throws Exception {
        String payload = """
                {
                  "requirement": "Build a greenfield REST URL shortener with token generation and redirection"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/v1/workflows/" + id + "/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Stopped by supervisor\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellation.reason").value("Stopped by supervisor"));
    }

    @Test
    @DisplayName("Repeated cancellation requests are idempotent and return HTTP 200 OK")
    void repeatedCancellationIsIdempotent() throws Exception {
        String createPayload = "{\"requirement\": \"make links faster and safer\"}";
        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        // First cancel
        mockMvc.perform(post("/api/v1/workflows/" + id + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Initial cancel\", \"requestedBy\": \"user-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellation.cancelledBy").value("user-1"));

        // Second cancel
        mockMvc.perform(post("/api/v1/workflows/" + id + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Duplicate cancel\", \"requestedBy\": \"user-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellation.cancelledBy").value("user-1")); // Preserves original
    }

    @Test
    @DisplayName("Cancellation on terminal workflow (completed or rejected) is rejected with HTTP 409 Conflict")
    void cancelOnTerminalWorkflowRejected() throws Exception {
        String payload = """
                {
                  "requirement": "Build a greenfield REST URL shortener with token generation and redirection"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        String planHash = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("planHash").asText();

        // Reject plan -> terminal status REJECTED
        String rejectPayload = """
                {
                  "decision": "REJECTED",
                  "planHash": "%s",
                  "comments": "Plan denied"
                }
                """.formatted(planHash);

        mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rejectPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // Try to cancel rejected workflow -> 409 Conflict
        mockMvc.perform(post("/api/v1/workflows/" + id + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"cancel rejected\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_WORKFLOW_STATE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already in terminal state REJECTED")));
    }

    @Test
    @DisplayName("Submitting clarification or approval to cancelled workflow is rejected with HTTP 409 Conflict")
    void resumeCancelledWorkflowIsRejected() throws Exception {
        String payload = "{\"requirement\": \"make links faster and safer\"}";
        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        // Cancel it
        mockMvc.perform(post("/api/v1/workflows/" + id + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Attempting to submit clarification -> 409
        String clarifyPayload = "{\"clarification\": \"Now try to clarify\"}";
        mockMvc.perform(post("/api/v1/workflows/" + id + "/clarifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clarifyPayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_WORKFLOW_STATE"));

        // Attempting to approve plan -> 409
        String approvePayload = "{\"decision\": \"APPROVED\", \"planHash\": \"any-hash\"}";
        mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_WORKFLOW_STATE"));
    }

    @Test
    @DisplayName("Cancellation authorization enforces security tokens when enabled")
    void cancellationAuthorizationEnforcedWhenEnabled() throws Exception {
        securityProperties.setEnabled(true);
        securityProperties.setCancellationToken("secure-cancel-secret-xyz");

        String payload = "{\"requirement\": \"make links faster and safer\"}";
        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        // 1. Missing token -> 401 Unauthorized
        mockMvc.perform(post("/api/v1/workflows/" + id + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Test\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        // 2. Bad token -> 401 Unauthorized
        mockMvc.perform(post("/api/v1/workflows/" + id + "/cancel")
                        .header("X-Auth-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Test\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        // 3. Valid X-Auth-Token -> 200 OK
        mockMvc.perform(post("/api/v1/workflows/" + id + "/cancel")
                        .header("X-Auth-Token", "secure-cancel-secret-xyz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Authorized cancellation\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("Cancellation with Bearer token authorization header succeeds")
    void cancellationWithBearerTokenSucceeds() throws Exception {
        securityProperties.setEnabled(true);
        securityProperties.setCancellationToken("bearer-secret-token");

        String payload = "{\"requirement\": \"make links faster and safer\"}";
        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/v1/workflows/" + id + "/cancel")
                        .header("Authorization", "Bearer bearer-secret-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Bearer token stop\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("Cancel non-existent workflow returns HTTP 404 Not Found")
    void cancelNonExistentWorkflowReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/workflows/non-existent-id-9999/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Cancel\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Workflow response does not expose tokens or secrets in response body or events")
    void tokensAndSecretsNeverExposedInResponses() throws Exception {
        securityProperties.setEnabled(true);
        securityProperties.setCancellationToken("super-sensitive-secret-token-12345");

        String payload = "{\"requirement\": \"make links faster and safer\"}";
        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        MvcResult cancelResult = mockMvc.perform(post("/api/v1/workflows/" + id + "/cancel")
                        .header("X-Auth-Token", "super-sensitive-secret-token-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Cancel with auth\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = cancelResult.getResponse().getContentAsString();
        assertThat(responseBody).doesNotContain("super-sensitive-secret-token-12345");

        // Also check GET
        MvcResult getResult = mockMvc.perform(get("/api/v1/workflows/" + id))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(getResult.getResponse().getContentAsString()).doesNotContain("super-sensitive-secret-token-12345");
    }

    @Test
    @DisplayName("Workflow retry properties are properly bound with active configuration defaults")
    void retryPropertiesProperlyBoundFromApplicationProperties() {
        assertThat(retryProperties).isNotNull();
        assertThat(retryProperties.isEnabled()).isTrue();
        assertThat(retryProperties.getMaxAttempts()).isEqualTo(3);
        assertThat(retryProperties.getInitialBackoffMs()).isEqualTo(500L);
        assertThat(retryProperties.getMaxBackoffMs()).isEqualTo(5000L);
        assertThat(retryProperties.getBackoffMultiplier()).isEqualTo(2.0);
    }
}
