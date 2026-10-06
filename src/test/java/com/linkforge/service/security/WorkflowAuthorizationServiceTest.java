package com.linkforge.service.security;

import com.linkforge.domain.workflow.PlanHasher;
import com.linkforge.domain.workflow.PlannedTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowAuthorizationServiceTest {

    @Test
    void authorizationDisabledAllowsAllRequests() {
        WorkflowSecurityProperties properties = new WorkflowSecurityProperties();
        properties.setEnabled(false);
        WorkflowAuthorizationService service = new WorkflowAuthorizationService(properties);

        assertThatCode(() -> service.authorizeClarification(null, null)).doesNotThrowAnyException();
        assertThatCode(() -> service.authorizePlanApproval(null, null)).doesNotThrowAnyException();
    }

    @Test
    void authorizationEnabledValidatesTokens() {
        WorkflowSecurityProperties properties = new WorkflowSecurityProperties();
        properties.setEnabled(true);
        properties.setClarificationToken("clarify-secret-123");
        properties.setApprovalToken("approval-secret-456");
        WorkflowAuthorizationService service = new WorkflowAuthorizationService(properties);

        // Clarification token checks
        assertThatThrownBy(() -> service.authorizeClarification(null, null))
                .isInstanceOf(WorkflowAuthorizationException.class)
                .hasMessageContaining("Valid clarification authorization token is required");

        assertThatThrownBy(() -> service.authorizeClarification("Bearer wrong-token", null))
                .isInstanceOf(WorkflowAuthorizationException.class);

        assertThatCode(() -> service.authorizeClarification("Bearer clarify-secret-123", null))
                .doesNotThrowAnyException();

        assertThatCode(() -> service.authorizeClarification(null, "clarify-secret-123"))
                .doesNotThrowAnyException();

        // Approval token checks
        assertThatThrownBy(() -> service.authorizePlanApproval(null, null))
                .isInstanceOf(WorkflowAuthorizationException.class)
                .hasMessageContaining("Valid plan-approval authorization token is required");

        assertThatThrownBy(() -> service.authorizePlanApproval(null, "wrong-token"))
                .isInstanceOf(WorkflowAuthorizationException.class);

        assertThatCode(() -> service.authorizePlanApproval("Bearer approval-secret-456", null))
                .doesNotThrowAnyException();

        assertThatCode(() -> service.authorizePlanApproval(null, "approval-secret-456"))
                .doesNotThrowAnyException();
    }

    @Test
    void resolveSubmitterAndApproverPrecedence() {
        WorkflowSecurityProperties properties = new WorkflowSecurityProperties();
        properties.setDefaultSubmitter("default-sub");
        properties.setDefaultApprover("default-app");
        WorkflowAuthorizationService service = new WorkflowAuthorizationService(properties);

        assertThat(service.resolveSubmitter("hdr-user", "body-user")).isEqualTo("body-user");
        assertThat(service.resolveSubmitter("hdr-user", "")).isEqualTo("hdr-user");
        assertThat(service.resolveSubmitter(null, null)).isEqualTo("default-sub");

        assertThat(service.resolveApprover("hdr-lead", "body-lead")).isEqualTo("body-lead");
        assertThat(service.resolveApprover("hdr-lead", " ")).isEqualTo("hdr-lead");
        assertThat(service.resolveApprover(null, null)).isEqualTo("default-app");
    }

    @Test
    void planHasherComputesDeterministicAndUniqueHashes() {
        List<PlannedTask> tasks1 = List.of(
                new PlannedTask("task-1", "Design API", "Spec endpoints", List.of(), "PENDING", "API_SPECIALIST")
        );
        List<PlannedTask> tasks2 = List.of(
                new PlannedTask("task-1", "Design API", "Spec endpoints", List.of(), "PENDING", "API_SPECIALIST")
        );
        List<PlannedTask> tasks3 = List.of(
                new PlannedTask("task-1", "Design API", "Updated Spec endpoints", List.of(), "PENDING", "API_SPECIALIST")
        );

        String hash1 = PlanHasher.computePlanHash(tasks1);
        String hash2 = PlanHasher.computePlanHash(tasks2);
        String hash3 = PlanHasher.computePlanHash(tasks3);

        assertThat(hash1).isNotNull().isEqualTo(hash2);
        assertThat(hash1).isNotEqualTo(hash3);
        assertThat(PlanHasher.computePlanHash(List.of())).isEqualTo("empty-plan");
        assertThat(PlanHasher.computePlanHash(null)).isEqualTo("empty-plan");
    }
}
