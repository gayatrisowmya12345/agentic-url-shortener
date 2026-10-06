package com.linkforge.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementInterpreterAgentTest {

    private RequirementInterpreterAgent agent;

    @BeforeEach
    void setUp() {
        agent = new RequirementInterpreterAgent();
    }

    @Test
    @DisplayName("Clear requirement produces acceptance criteria and marks clear")
    void interpretClearRequirement() {
        String requirement = "Build a URL shortener service that generates short links and redirects users to original URLs";

        RequirementInterpretationResult result = agent.interpret(requirement);

        assertThat(result.clear()).isTrue();
        assertThat(result.acceptanceCriteria()).isNotEmpty();
        assertThat(result.acceptanceCriteria()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(result.unansweredQuestions()).isEmpty();
        assertThat(result.metadata()).containsEntry("agent", RequirementInterpreterAgent.AGENT_NAME);
        assertThat(result.metadata()).containsEntry("type", RequirementInterpreterAgent.AGENT_TYPE);
        assertThat(result.rationale()).containsIgnoringCase("verified");
    }

    @Test
    @DisplayName("Ambiguous requirement 'make links faster and safer' records questions and marks unclear")
    void interpretAmbiguousRequirement() {
        String requirement = "make links faster and safer";

        RequirementInterpretationResult result = agent.interpret(requirement);

        assertThat(result.clear()).isFalse();
        assertThat(result.acceptanceCriteria()).isEmpty();
        assertThat(result.unansweredQuestions()).isNotEmpty();
        assertThat(result.unansweredQuestions()).anyMatch(q -> q.contains("faster") || q.contains("latency"));
        assertThat(result.unansweredQuestions()).anyMatch(q -> q.contains("safer") || q.contains("security"));
        assertThat(result.metadata()).containsEntry("agent", RequirementInterpreterAgent.AGENT_NAME);
        assertThat(result.metadata()).containsEntry("type", RequirementInterpreterAgent.AGENT_TYPE);
        assertThat(result.rationale()).containsIgnoringCase("qualitative");
    }

    @Test
    @DisplayName("Blank requirement is rejected with clarification prompt")
    void interpretBlankRequirement() {
        RequirementInterpretationResult result = agent.interpret("   ");

        assertThat(result.clear()).isFalse();
        assertThat(result.decisionSummary()).isEqualTo("REJECT_EMPTY_REQUIREMENT");
        assertThat(result.unansweredQuestions()).isNotEmpty();
    }
}
