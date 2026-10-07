package com.linkforge.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for releasing readiness approval or rejection.
 * Accepts either outcomeHash or planHash for interoperability.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReleaseApprovalRequest(
        @NotBlank(message = "Decision cannot be blank (APPROVED or REJECTED)")
        String decision,
        String outcomeHash,
        String planHash,
        String approver,
        String comments
) {
    public ReleaseApprovalRequest(String decision, String outcomeHash) {
        this(decision, outcomeHash, null, null, null);
    }

    public String effectiveHash() {
        if (outcomeHash != null && !outcomeHash.isBlank()) {
            return outcomeHash.trim();
        }
        if (planHash != null && !planHash.isBlank()) {
            return planHash.trim();
        }
        return null;
    }
}
