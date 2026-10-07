package com.linkforge.api;

import com.linkforge.ai.provider.FakeLlmModelProvider;
import com.linkforge.ai.provider.LlmModelProvider;
import com.linkforge.ai.provider.LlmProviderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(ModelBackedWorkflowIntegrationTest.TestModelConfig.class)
@TestPropertySource(properties = {
        "linkforge.ai.enabled=true",
        "linkforge.ai.provider=fake"
})
class ModelBackedWorkflowIntegrationTest {

    @TestConfiguration
    static class TestModelConfig {
        @Bean
        @Primary
        public FakeLlmModelProvider testFakeModelProvider() {
            return new FakeLlmModelProvider();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeLlmModelProvider fakeLlmModelProvider;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Test
    @DisplayName("End-to-end model-backed workflow execution with structured output")
    void modelBackedWorkflowExecution() throws Exception {
        String modelOutput = """
                {
                  "summary": "AI specification for distributed URL shortener",
                  "acceptanceCriteria": [
                    "AC-AI-1: Given an input URL, hash with Murmur3 and encode in Base62",
                    "AC-AI-2: Given active hash, return 302 redirect with Cache-Control headers"
                  ],
                  "assumptions": [
                    "Target p99 latency is below 10ms",
                    "Max original URL length is 2048 characters"
                  ],
                  "clarificationQuestions": []
                }
                """;
        fakeLlmModelProvider.setEnabled(true);
        fakeLlmModelProvider.setResponsePayload(modelOutput);

        String payload = """
                {
                  "requirement": "Build a distributed URL shortener"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.currentStage").value("PLAN_APPROVAL"))
                .andExpect(jsonPath("$.acceptanceCriteria", hasSize(2)))
                .andExpect(jsonPath("$.acceptanceCriteria[0]").value("AC-AI-1: Given an input URL, hash with Murmur3 and encode in Base62"))
                .andExpect(jsonPath("$.assumptions", hasSize(2)))
                .andExpect(jsonPath("$.assumptions[0]").value("Target p99 latency is below 10ms"))
                .andExpect(jsonPath("$.agentDecisions[1].agentType").value("MODEL_BACKED_AGENT"))
                .andExpect(jsonPath("$.agentDecisions[1].metadata.provider").value("fake"))
                .andExpect(jsonPath("$.events[*].eventType", hasItem("MODEL_INTERPRETATION_COMPLETED")))
                .andReturn();

        String responseBody = createResult.getResponse().getContentAsString();
        String id = responseBody.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        String planHash = responseBody.replaceAll(".*\"planHash\":\"([^\"]+)\".*", "$1");

        MvcResult approveResult = mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", planHash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.currentStage").value("IMPLEMENTATION_PROPOSAL"))
                .andReturn();

        String proposalHash = objectMapper.readTree(approveResult.getResponse().getContentAsString()).get("planHash").asText();

        mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", proposalHash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentStage").value("FINISHED"));

        mockMvc.perform(get("/api/v1/workflows/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.acceptanceCriteria[0]").value("AC-AI-1: Given an input URL, hash with Murmur3 and encode in Base62"));
    }

    @Test
    @DisplayName("End-to-end fallback when provider fails during HTTP workflow execution")
    void modelFailureTriggersDeterministicFallbackInWorkflow() throws Exception {
        fakeLlmModelProvider.setEnabled(true);
        fakeLlmModelProvider.setExceptionToThrow(new LlmProviderException("Connection timeout contacting LLM host"));

        String payload = """
                {
                  "requirement": "Create a URL shortener with token generation and redirection"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.agentDecisions[1].agentType").value("DETERMINISTIC_SPECIALIST_FALLBACK"))
                .andExpect(jsonPath("$.agentDecisions[1].metadata.fallbackOccurred").value(true))
                .andExpect(jsonPath("$.agentDecisions[1].metadata.fallbackReason", containsString("Connection timeout")))
                .andExpect(jsonPath("$.events[*].eventType", hasItem("LLM_FALLBACK_TRIGGERED")))
                .andReturn();

        String responseBody = createResult.getResponse().getContentAsString();
        String id = responseBody.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        String planHash = responseBody.replaceAll(".*\"planHash\":\"([^\"]+)\".*", "$1");

        MvcResult approveResult = mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", planHash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.currentStage").value("IMPLEMENTATION_PROPOSAL"))
                .andReturn();

        String proposalHash = objectMapper.readTree(approveResult.getResponse().getContentAsString()).get("planHash").asText();

        mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", proposalHash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("End-to-end fallback when provider returns truncated output (unexpected end-of-input)")
    void truncatedModelOutputTriggersDeterministicFallbackInWorkflow() throws Exception {
        fakeLlmModelProvider.setEnabled(true);
        fakeLlmModelProvider.setResponsePayload("{\"summary\": \"Model started generating criteria...\", \"acceptanceCriteria\": [ \"AC-1: incomplete");

        String payload = """
                {
                  "requirement": "Create a URL shortener with token generation and redirection"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.agentDecisions[1].agentType").value("DETERMINISTIC_SPECIALIST_FALLBACK"))
                .andExpect(jsonPath("$.agentDecisions[1].metadata.fallbackOccurred").value(true))
                .andExpect(jsonPath("$.events[*].eventType", hasItem("LLM_FALLBACK_TRIGGERED")))
                .andReturn();

        String responseBody = createResult.getResponse().getContentAsString();
        String id = responseBody.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        String planHash = responseBody.replaceAll(".*\"planHash\":\"([^\"]+)\".*", "$1");

        MvcResult approveResult = mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", planHash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.currentStage").value("IMPLEMENTATION_PROPOSAL"))
                .andReturn();

        String proposalHash = objectMapper.readTree(approveResult.getResponse().getContentAsString()).get("planHash").asText();

        mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", proposalHash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("Model proposing hardcoded 3-30 bounds for min-4 requirement is rejected and safely falls back to trusted requirement-derived proposal")
    void modelProposingHardcodedBoundsFallsBackToTrustedDerivedProposal() throws Exception {
        // Model tries to propose hardcoded 3-30 bounds
        String badModelProposal = """
                {
                  "path": "src/main/java/com/linkforge/service/link/AliasValidator.java",
                  "operation": "MODIFY",
                  "proposedContent": "package com.linkforge.service.link; public final class AliasValidator { public static final int MIN_LENGTH = 3; public static final int MAX_LENGTH = 30; public static void validate(String a) {} }",
                  "description": "Model hardcoded 3-30 proposal"
                }
                """;
        fakeLlmModelProvider.setEnabled(true);
        fakeLlmModelProvider.setResponsePayload(badModelProposal);

        String payload = """
                {
                  "requirement": "Require custom link aliases to have a minimum length of 4 characters and maximum length of 30 characters"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        String planHash = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("planHash").asText();

        // Phase 1 approval: triggers proposal generation
        MvcResult approveResult = mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", planHash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.currentStage").value("IMPLEMENTATION_PROPOSAL"))
                .andReturn();

        // Proposal must NOT contain the model's hardcoded MIN_LENGTH = 3.
        // It must safely fall back to trusted requirement-derived implementation enforcing MIN_LENGTH = 4.
        String proposalJson = approveResult.getResponse().getContentAsString();
        assertThat(proposalJson).doesNotContain("MIN_LENGTH = 3;");
        assertThat(proposalJson).contains("MIN_LENGTH = 4;");
    }
}
