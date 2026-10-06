package com.linkforge.service.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Environment-backed configuration properties for workflow security and authorization.
 */
@Component
@ConfigurationProperties(prefix = "linkforge.workflow.security")
public class WorkflowSecurityProperties {

    private boolean enabled = false;
    private String clarificationToken = "dev-clarification-token";
    private String approvalToken = "dev-approval-token";
    private String cancellationToken = "dev-cancellation-token";
    private String defaultSubmitter = "operator";
    private String defaultApprover = "authorized-approver";
    private String defaultCanceller = "operator";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getClarificationToken() {
        return clarificationToken;
    }

    public void setClarificationToken(String clarificationToken) {
        this.clarificationToken = clarificationToken;
    }

    public String getApprovalToken() {
        return approvalToken;
    }

    public void setApprovalToken(String approvalToken) {
        this.approvalToken = approvalToken;
    }

    public String getCancellationToken() {
        return cancellationToken;
    }

    public void setCancellationToken(String cancellationToken) {
        this.cancellationToken = cancellationToken;
    }

    public String getDefaultSubmitter() {
        return defaultSubmitter;
    }

    public void setDefaultSubmitter(String defaultSubmitter) {
        this.defaultSubmitter = defaultSubmitter;
    }

    public String getDefaultApprover() {
        return defaultApprover;
    }

    public void setDefaultApprover(String defaultApprover) {
        this.defaultApprover = defaultApprover;
    }

    public String getDefaultCanceller() {
        return defaultCanceller;
    }

    public void setDefaultCanceller(String defaultCanceller) {
        this.defaultCanceller = defaultCanceller;
    }
}
