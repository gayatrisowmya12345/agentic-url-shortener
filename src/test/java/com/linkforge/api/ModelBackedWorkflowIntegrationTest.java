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

        MvcResult result = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentStage").value("FINISHED"))
                .andExpect(jsonPath("$.acceptanceCriteria", hasSize(2)))
                .andExpect(jsonPath("$.acceptanceCriteria[0]").value("AC-AI-1: Given an input URL, hash with Murmur3 and encode in Base62"))
                .andExpect(jsonPath("$.assumptions", hasSize(2)))
                .andExpect(jsonPath("$.assumptions[0]").value("Target p99 latency is below 10ms"))
                .andExpect(jsonPath("$.agentDecisions[0].agentType").value("MODEL_BACKED_AGENT"))
                .andExpect(jsonPath("$.agentDecisions[0].metadata.provider").value("fake"))
                .andExpect(jsonPath("$.events[*].eventType", hasItem("MODEL_INTERPRETATION_COMPLETED")))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        String id = responseBody.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

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

        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.agentDecisions[0].agentType").value("DETERMINISTIC_SPECIALIST_FALLBACK"))
                .andExpect(jsonPath("$.agentDecisions[0].metadata.fallbackOccurred").value(true))
                .andExpect(jsonPath("$.agentDecisions[0].metadata.fallbackReason", containsString("Connection timeout")))
                .andExpect(jsonPath("$.events[*].eventType", hasItem("LLM_FALLBACK_TRIGGERED")));
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

        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.agentDecisions[0].agentType").value("DETERMINISTIC_SPECIALIST_FALLBACK"))
                .andExpect(jsonPath("$.agentDecisions[0].metadata.fallbackOccurred").value(true))
                .andExpect(jsonPath("$.events[*].eventType", hasItem("LLM_FALLBACK_TRIGGERED")));
    }
}
