package com.linkforge.service.security;

import org.springframework.stereotype.Service;

import java.security.MessageDigest;

/**
 * Service to validate authorization for workflow clarification submissions and plan approvals.
 */
@Service
public class WorkflowAuthorizationService {

    private final WorkflowSecurityProperties securityProperties;

    public WorkflowAuthorizationService(WorkflowSecurityProperties securityProperties) {
        this.securityProperties = securityProperties != null ? securityProperties : new WorkflowSecurityProperties();
    }

    public void authorizeClarification(String authHeader, String tokenHeader) {
        if (!securityProperties.isEnabled()) {
            return;
        }

        String presentedToken = extractToken(authHeader, tokenHeader);
        if (presentedToken == null || !secureEquals(presentedToken, securityProperties.getClarificationToken())) {
            throw new WorkflowAuthorizationException("Unauthorized: Valid clarification authorization token is required.");
        }
    }

    public void authorizePlanApproval(String authHeader, String tokenHeader) {
        if (!securityProperties.isEnabled()) {
            return;
        }

        String presentedToken = extractToken(authHeader, tokenHeader);
        if (presentedToken == null || !secureEquals(presentedToken, securityProperties.getApprovalToken())) {
            throw new WorkflowAuthorizationException("Unauthorized: Valid plan-approval authorization token is required.");
        }
    }

    public void authorizeCancellation(String authHeader, String tokenHeader) {
        if (!securityProperties.isEnabled()) {
            return;
        }

        String presentedToken = extractToken(authHeader, tokenHeader);
        if (presentedToken == null || !secureEquals(presentedToken, securityProperties.getCancellationToken())) {
            throw new WorkflowAuthorizationException("Unauthorized: Valid cancellation authorization token is required.");
        }
    }

    public String resolveSubmitter(String headerSubmitter, String bodySubmitter) {
        if (bodySubmitter != null && !bodySubmitter.isBlank()) {
            return bodySubmitter.trim();
        }
        if (headerSubmitter != null && !headerSubmitter.isBlank()) {
            return headerSubmitter.trim();
        }
        return securityProperties.getDefaultSubmitter();
    }

    public String resolveApprover(String headerApprover, String bodyApprover) {
        if (bodyApprover != null && !bodyApprover.isBlank()) {
            return bodyApprover.trim();
        }
        if (headerApprover != null && !headerApprover.isBlank()) {
            return headerApprover.trim();
        }
        return securityProperties.getDefaultApprover();
    }

    public String resolveCanceller(String headerCanceller, String bodyCanceller) {
        if (bodyCanceller != null && !bodyCanceller.isBlank()) {
            return bodyCanceller.trim();
        }
        if (headerCanceller != null && !headerCanceller.isBlank()) {
            return headerCanceller.trim();
        }
        return securityProperties.getDefaultCanceller();
    }

    private String extractToken(String authHeader, String tokenHeader) {
        if (tokenHeader != null && !tokenHeader.isBlank()) {
            return tokenHeader.trim();
        }
        if (authHeader != null && !authHeader.isBlank()) {
            String trimmed = authHeader.trim();
            if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return trimmed.substring(7).trim();
            }
            return trimmed;
        }
        return null;
    }

    private boolean secureEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }
}
